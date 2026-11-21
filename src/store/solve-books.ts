import { calculateThroughput } from "@/lib/solver";
import type { FactoryProject, ThroughputResult } from "@/lib/model/types";
import { getGameRates } from "@/lib/model/game-rates";
import { setSolveDegraded } from "./solve-health";

/**
 * The store's one door to the solver, sized to the board it is asked about.
 *
 * A small board solves synchronously: the result is back before the state
 * update lands. A BIG board must not run on the main thread (seconds to
 * minutes, on every edit and tab switch). It moves to a Web Worker: the
 * caller gets its previous books back immediately, flagged `stale`, and the
 * real result replaces `lastResult` through the sink when the worker lands.
 *
 * Finished big-board books are kept in a small content-keyed LRU, so
 * switching tabs between unchanged big plans is instant; the design store
 * re-reads a plan from IndexedDB on every switch, so the cache cannot key on
 * object identity.
 *
 * Rapid edits coalesce: one solve runs at a time, only the newest waiting
 * plan is kept, and a result for a plan no longer on the canvas is cached
 * but never shown. Environments without workers (SSR, vitest, old browsers)
 * keep the synchronous path for every size.
 */

/**
 * Nodes plus edges above which the solve leaves the main thread. Around this
 * size the homegrown simplex already takes 100ms+ per edit (the worker's
 * engine stays under 50ms), and main-thread time grows roughly cubically.
 */
const SYNC_SOLVE_LIMIT = 120;

/**
 * Size is not the whole story. One more reason to leave the main thread:
 * this plan's last MAIN-THREAD solve took longer than SLOW_SOLVE_MS (three
 * frames: past it every edit stutters, and in Firefox a hang that long clips
 * the board's sounds, see board-sounds.ts).
 */
const SLOW_SOLVE_MS = 50;
/**
 * SOLVE MODE is far heavier on the homegrown simplex: six dense solves over
 * every machine and wire at once, and pool mode (which rides on solve mode)
 * adds a pool drawer per resource. It can run several times slower than plan
 * mode there, while the worker's engine stays fast. Past this small size it
 * always goes to the worker rather than waiting for the slow-solve rule to
 * learn from a frozen tab.
 */
const SOLVE_MODE_SYNC_LIMIT = 40;

/**
 * The last MAIN-THREAD solve's wall time, and the plan it was measured on.
 * Only a synchronous solve may write it: the worker runs the compiled core, roughly ten
 * times faster than the main thread's simplex, so its timing says nothing
 * about how long the tab would freeze (letting it count would send a slow
 * plan back to the main thread). A slow plan therefore stays with the worker
 * for the session; another plan decides by its own size.
 */
let lastSolveDurationMs: number | undefined;
let lastSolveProjectId: string | undefined;

/**
 * Every edit solves; there is no manual recalculation. The old board toolbar's
 * switch for it saved "off" here, and plan boards show no such switch, so a
 * browser holding it showed every plan it opened at zero with no way out. The
 * stored value is removed on load so no browser stays stuck in it.
 */
try {
  if (typeof localStorage !== "undefined") {
    localStorage.removeItem("gtnh-factory-flow.auto-solve.v1");
  }
} catch {
  // Storage refused (a private window): nothing was stored either.
}

const BIG_BOOKS_CACHE_LIMIT = 8;
const bigBooksCache = new Map<string, ThroughputResult>();

interface SolveRequest {
  key: string;
  project: FactoryProject;
}

let sink: ((result: ThroughputResult) => void) | undefined;
let worker: Worker | undefined;
let workerBroken = false;
let inFlight: SolveRequest | undefined;
let queued: SolveRequest | undefined;
/** The content key of the plan the canvas currently shows, when it is big. */
let currentKey: string | undefined;
/** The last books handed out, big or small: what a stale placeholder wears. */
let lastBooks: ThroughputResult | undefined;

/** Where finished worker solves land; the factory store registers itself. */
export function registerBooksSink(apply: (result: ThroughputResult) => void) {
  sink = apply;
}

export function solveBooks(project: FactoryProject): ThroughputResult {
  const size = project.nodes.length + project.edges.length;
  const provenSlow =
    lastSolveDurationMs !== undefined &&
    lastSolveDurationMs > SLOW_SOLVE_MS &&
    lastSolveProjectId === project.id;
  const expectSlow =
    size > SYNC_SOLVE_LIMIT ||
    provenSlow ||
    ((project.solveMode === true || project.poolMode === true) && size > SOLVE_MODE_SYNC_LIMIT) ||
    // A plan solves on the compiled core, which the worker loads: the worker whenever there is one.
    project.planMode === true;
  if (!expectSlow || !workerAvailable()) {
    if (!workerAvailable()) requestMainThreadCore();
    const started = performance.now();
    const result = calculateThroughput(project);
    lastSolveDurationMs = performance.now() - started;
    lastSolveProjectId = project.id;
    currentKey = undefined;
    lastBooks = result;
    return result;
  }

  const key = booksContentKey(project);
  currentKey = key;
  const cached = bigBooksCache.get(key);
  if (cached) {
    // LRU bump.
    bigBooksCache.delete(key);
    bigBooksCache.set(key, cached);
    lastBooks = cached;
    return cached;
  }

  scheduleSolve({ key, project });
  // The previous books stand in while the worker runs. After a tab switch
  // they belong to another plan, whose node ids simply miss - the board reads
  // that as empty books until the real ones land, which is the honest state.
  const placeholder: ThroughputResult = lastBooks
    ? { ...lastBooks, stale: true }
    : emptyBooks();
  lastBooks = placeholder;
  return placeholder;
}

/**
 * The plan as the solver sees it. The `view` block is how the board is DRAWN
 * and never reaches the solver, so it must not invalidate finished books; the
 * design store also restamps it on every save, which would defeat the cache.
 */
function booksContentKey(project: FactoryProject): string {
  const { view: _view, ...solved } = project;
  // The dataset's rate factors move the books too, and a worker solve has to be sent them.
  return JSON.stringify({ solved, rates: getGameRates() });
}

function workerAvailable(): boolean {
  return !workerBroken && typeof Worker !== "undefined";
}

let coreRequested = false;

/**
 * With no Worker every solve runs here, so the main thread asks for the compiled core the way the Worker does: the
 * solve adoption (`@/lib/core/solve-adopt`) loads the solver module and installs the bridge the plan solve reads, and
 * until it is ready the website's engine answers. Only in a browser (a server render and the test runner keep the
 * website's engine), once, and only where a solve would otherwise run here: with a Worker the main thread never loads
 * the solver, which the Worker owns.
 */
function requestMainThreadCore() {
  if (coreRequested || typeof window === "undefined") return;
  coreRequested = true;
  import("@/lib/core/solve-adopt")
    .then((m) => m.warmSolveCore())
    .then((ready) => setSolveDegraded(!ready))
    .catch(() => setSolveDegraded(true));
}

function scheduleSolve(request: SolveRequest) {
  if (inFlight) {
    // Only the newest waiting plan matters; intermediates were superseded.
    queued = request;
    return;
  }
  inFlight = request;
  try {
    getWorker().postMessage({ key: request.key, project: request.project, rates: getGameRates() });
  } catch (error) {
    console.error("solve worker failed to start; solving on the main thread", error);
    workerBroken = true;
    inFlight = undefined;
    deliver(request.key, calculateThroughput(request.project));
  }
}

function getWorker(): Worker {
  if (!worker) {
    worker = new Worker(new URL("./solve-books.worker.ts", import.meta.url));
    worker.onmessage = (
      event: MessageEvent<{
        key: string;
        result?: ThroughputResult;
        error?: string;
        solveMs?: number;
        engine?: "core" | "website";
      }>,
    ) => {
      const { key, result, error, engine } = event.data;
      if (engine) setSolveDegraded(engine !== "core");
      inFlight = undefined;
      if (result) {
        // `solveMs` is deliberately not read: see lastSolveDurationMs.
        deliver(key, result);
      } else {
        console.error("solve worker error:", error);
        // The stale placeholder must not outlive the solve: the plan reads as unsolved, never as still working.
        deliver(key, failedBooks(error));
      }
      if (queued) {
        const next = queued;
        queued = undefined;
        // A queued repeat of what just finished (or of anything already
        // solved) serves from the cache instead of solving twice.
        const cached = bigBooksCache.get(next.key);
        if (cached) {
          if (next.key === currentKey) {
            lastBooks = cached;
            sink?.(cached);
          }
        } else {
          scheduleSolve(next);
        }
      }
    };
    worker.onerror = (event) => {
      // A worker that cannot run its script would fail every solve silently;
      // fall back to the blocking path rather than showing stale books forever.
      console.error("solve worker broke; falling back to main-thread solves", event.message);
      workerBroken = true;
      const retry = queued ?? inFlight;
      inFlight = undefined;
      queued = undefined;
      if (retry) {
        deliver(retry.key, calculateThroughput(retry.project));
      }
    };
  }
  return worker;
}

function deliver(key: string, result: ThroughputResult) {
  bigBooksCache.set(key, result);
  while (bigBooksCache.size > BIG_BOOKS_CACHE_LIMIT) {
    const oldest = bigBooksCache.keys().next().value;
    if (oldest === undefined) {
      break;
    }
    bigBooksCache.delete(oldest);
  }
  if (key === currentKey) {
    lastBooks = result;
    sink?.(result);
  }
}

/** Books for a solve that threw: nothing solved, and the board's "couldn't solve this plan" notice. */
function failedBooks(error: string | undefined): ThroughputResult {
  return {
    ...emptyBooks(),
    stale: false,
    plan: {
      forced: [],
      leftovers: [],
      unneeded: [],
      unanchored: [],
      terminals: [],
      notes: [],
      openGates: 0,
      failure: { message: "BALANCE_FAILED", args: [error ?? "solve worker error"] },
    },
  };
}

function emptyBooks(): ThroughputResult {
  return {
    nodes: {},
    storages: {},
    resources: {},
    edges: {},
    totalEuT: 0,
    totalEuPerSecond: 0,
    bottlenecks: [],
    externalInputs: [],
    unconsumedOutputs: [],
    generatedAt: new Date().toISOString(),
    stale: true,
  };
}
