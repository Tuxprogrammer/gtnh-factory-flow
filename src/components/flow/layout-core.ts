/**
 * The wire router, Arrange and the live-drag rule through the compiled core (jvm/core/core-layout, the website's own
 * router and Arrange translated line for line and held to them by conformance/layout), behind the layout flag.
 *
 * The flag is `ADOPT.layout` in `src/lib/core/adopt.ts` once that file lands; until then `LAYOUT_ADOPTED` below is
 * its stand-in, and a dev override set through adopt.ts's shared record (`__gtnhAdopt.set("layout", ...)`) already
 * counts. Nothing here waits for the core: `layoutCore()` is null until `loadCore()` has finished, or when it failed,
 * or when the flag is off, and the caller then runs the TypeScript. Pure and worker-safe.
 *
 * The router crosses as a board held in the core between calls (`CoreBoardRouter`): each call tells it only what
 * changed since the last (obstacles and requests put or gone, the order when it moved) and gets back only the routes
 * whose points changed. A drag beat therefore sends the moved card and its wires, not the board.
 */
import type { CoreExports } from "@/lib/core/exports.generated";
import { coreIfLoaded, coreInfo, loadCore } from "@/lib/core/loader";
import type { ArrangeJob, ArrangeJobResult, ArrangeProgress } from "@/lib/arrange-job";
import type {
  GridEndpoint,
  GridObstacle,
  GridPoint,
  GridRoutedEdge,
  GridRouteRequest,
  GridSide,
} from "./grid-edge-router";
import type { IncrementalStats, RouterSnapshot } from "./incremental-router";
import type { RouterTuning } from "./router-tuning";

/** Stand-in for adopt.ts's `layout` line: whether routing and Arrange go through the core when it is loaded. */
export const LAYOUT_ADOPTED = true;

// adopt.ts keeps its dev overrides in this record on globalThis; reading it here means one override governs both.
const ADOPT_OVERRIDES = Symbol.for("gtnh.core.adopt");

/** Whether the layout area computes through the core: a dev override if one is set, else the committed flag. */
export function layoutAdopted(): boolean {
  const g = globalThis as unknown as Record<symbol, Record<string, boolean | undefined> | undefined>;
  return g[ADOPT_OVERRIDES]?.layout ?? LAYOUT_ADOPTED;
}

/** Forces the area on or off for this thread (tests, a dev console), or back to the flag (undefined). Memory only. */
export function setLayoutAdoptOverride(value: boolean | undefined): void {
  const g = globalThis as unknown as Record<symbol, Record<string, boolean | undefined> | undefined>;
  const o = (g[ADOPT_OVERRIDES] ??= {});
  if (value === undefined) delete o.layout;
  else o.layout = value;
}

type Layout = CoreExports["LayoutExports"];

/** The core's layout exports when the area is on and the core is loaded on this thread, else null. */
export function layoutCore(): Layout | null {
  return layoutAdopted() ? (coreIfLoaded()?.LayoutExports ?? null) : null;
}

/**
 * The core for Arrange: as `layoutCore()`, but on the WebAssembly build only. The JavaScript build of the core arranges
 * one reference board differently from the TypeScript (the column challenger of "factory-flow/Untitled design" in
 * conformance/layout/arrange.json.gz; the JVM and the WebAssembly build agree with the TypeScript), so an engine without
 * WebAssembly GC keeps the TypeScript Arrange until that is found and fixed. Its router answers are the TypeScript's
 * and it routes.
 */
export function arrangeCore(): Layout | null {
  return coreInfo()?.target === "wasm-gc" ? layoutCore() : null;
}

/**
 * Loads the core for this thread when the area is on; resolves whether it is there. Never rejects: a core that is
 * not built or fails to start leaves the TypeScript in charge.
 */
export function loadLayoutCore(): Promise<boolean> {
  if (!layoutAdopted()) return Promise.resolve(false);
  return loadCore().then(
    () => true,
    () => false,
  );
}

/* ---------------------------------------------------------------------- */
/* The router                                                              */

const SIDE_INDEX: Record<GridSide, number> = { left: 0, right: 1, top: 2, bottom: 3 };

function flat(endpoints: readonly GridEndpoint[]): number[] {
  const out: number[] = [];
  for (const e of endpoints) out.push(e.x, e.y, SIDE_INDEX[e.side] ?? 0, e.penalty ?? 0, e.stubDepth ?? 0);
  return out;
}

function sameEndpoints(a: readonly GridEndpoint[], b: readonly GridEndpoint[]): boolean {
  if (a === b) return true;
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i += 1) {
    const x = a[i];
    const y = b[i];
    if (
      x.x !== y.x ||
      x.y !== y.y ||
      x.side !== y.side ||
      (x.penalty ?? 0) !== (y.penalty ?? 0) ||
      (x.stubDepth ?? 0) !== (y.stubDepth ?? 0)
    )
      return false;
  }
  return true;
}

function samePoints(a: readonly GridPoint[] | undefined, b: readonly GridPoint[] | undefined): boolean {
  if (a === b) return true;
  if (!a || !b || a.length !== b.length) return false;
  for (let i = 0; i < a.length; i += 1) if (a[i].x !== b[i].x || a[i].y !== b[i].y) return false;
  return true;
}

function sameIds(a: readonly string[] | undefined, b: readonly string[] | undefined): boolean {
  if (a === b) return true;
  if (!a || !b || a.length !== b.length) return false;
  for (let i = 0; i < a.length; i += 1) if (a[i] !== b[i]) return false;
  return true;
}

function sameRequest(a: GridRouteRequest, b: GridRouteRequest): boolean {
  return (
    a === b ||
    (a.order === b.order &&
      a.strokeWidth === b.strokeWidth &&
      a.sourceCardId === b.sourceCardId &&
      a.targetCardId === b.targetCardId &&
      !!a.sourceSquare === !!b.sourceSquare &&
      !!a.targetSquare === !!b.targetSquare &&
      samePoints(a.waypoints, b.waypoints) &&
      sameIds(a.exemptObstacleIds, b.exemptObstacleIds) &&
      sameIds(a.homeObstacleIds, b.homeObstacleIds) &&
      sameEndpoints(a.sources, b.sources) &&
      sameEndpoints(a.targets, b.targets))
  );
}

function compact(r: GridRouteRequest) {
  return {
    edgeId: r.edgeId,
    order: r.order,
    strokeWidth: r.strokeWidth,
    sources: flat(r.sources),
    targets: flat(r.targets),
    sourceCardId: r.sourceCardId,
    targetCardId: r.targetCardId,
    sourceSquare: r.sourceSquare,
    targetSquare: r.targetSquare,
    waypoints: r.waypoints,
    exemptObstacleIds: r.exemptObstacleIds,
    homeObstacleIds: r.homeObstacleIds,
  };
}

interface BoardAnswer {
  routes: Array<{ edgeId: string; points: number[] }>;
  gone: string[];
  stats: { fresh: boolean; routed: number; kept: number; crossings: number; pops: number; searches: number };
}

/** What crossed on the last call, for the bench and the shadow tests. */
export interface CrossingCost {
  sentBytes: number;
  receivedBytes: number;
  /** Time inside the core's export call, ms (parsing, routing and writing the answer). */
  coreMs: number;
  /** Time on this side building the delta and reading the answer, ms. */
  hostMs: number;
}

/**
 * `IncrementalRouter`'s solve on a board the core holds. Same answers (the Java is the TypeScript translated and held
 * to it by conformance/layout); the routes come back as points only, which is all the board draws.
 */
export class CoreBoardRouter {
  private handle: number;
  private obstacles = new Map<string, GridObstacle>();
  private obstacleOrder: string[] = [];
  private requests = new Map<string, GridRouteRequest>();
  private requestOrder: string[] = [];
  private tuningText: string | undefined;
  private held = new Map<string, GridPoint[]>();
  private restoredIds: Set<string> | undefined;
  /** The cost of the last solve. */
  lastCost: CrossingCost = { sentBytes: 0, receivedBytes: 0, coreMs: 0, hostMs: 0 };

  constructor(private readonly layout: Layout) {
    this.handle = layout.layoutBoardOpen();
  }

  solve(
    obstacles: GridObstacle[],
    requests: GridRouteRequest[],
    tuning: RouterTuning,
    options: { thorough: boolean; stats?: IncrementalStats },
  ): Map<string, GridRoutedEdge> {
    const started = performance.now();
    const delta: Record<string, unknown> = { thorough: options.thorough };
    const tuningText = JSON.stringify(tuning);
    if (tuningText !== this.tuningText) {
      delta.tuning = tuning;
      this.tuningText = tuningText;
    }

    const nextObstacles = new Map<string, GridObstacle>();
    const putObstacles: GridObstacle[] = [];
    for (const o of obstacles) {
      nextObstacles.set(o.id, o);
      const before = this.obstacles.get(o.id);
      if (
        !before ||
        before.left !== o.left ||
        before.top !== o.top ||
        before.right !== o.right ||
        before.bottom !== o.bottom
      )
        putObstacles.push({ id: o.id, left: o.left, top: o.top, right: o.right, bottom: o.bottom });
    }
    const goneObstacles = this.obstacleOrder.filter((id) => !nextObstacles.has(id));
    const obstacleOrder = [...nextObstacles.keys()];
    if (putObstacles.length) delta.obstacles = putObstacles;
    if (goneObstacles.length) delta.obstaclesGone = goneObstacles;
    if (!sameOrderAfter(this.obstacleOrder, goneObstacles, obstacleOrder)) delta.obstacleOrder = obstacleOrder;

    const nextRequests = new Map<string, GridRouteRequest>();
    const putRequests: ReturnType<typeof compact>[] = [];
    for (const r of requests) {
      nextRequests.set(r.edgeId, r);
      const before = this.requests.get(r.edgeId);
      if (!before || !sameRequest(before, r)) putRequests.push(compact(r));
    }
    const goneRequests = this.requestOrder.filter((id) => !nextRequests.has(id));
    const requestOrder = [...nextRequests.keys()];
    if (putRequests.length) delta.requests = putRequests;
    if (goneRequests.length) delta.requestsGone = goneRequests;
    if (!sameOrderAfter(this.requestOrder, goneRequests, requestOrder)) delta.requestOrder = requestOrder;

    const sent = JSON.stringify(delta);
    const coreStarted = performance.now();
    const text = this.layout.layoutBoardRoute(this.handle, sent);
    const coreMs = performance.now() - coreStarted;
    // Only after the core took the delta: a call that threw leaves the last board as what was told.
    this.obstacles = nextObstacles;
    this.obstacleOrder = obstacleOrder;
    this.requests = nextRequests;
    this.requestOrder = requestOrder;
    this.restoredIds = undefined;

    const answer = JSON.parse(text) as BoardAnswer;
    for (const id of answer.gone) this.held.delete(id);
    for (const route of answer.routes) {
      const flatPoints = route.points;
      const points: GridPoint[] = new Array(flatPoints.length / 2);
      for (let i = 0; i < points.length; i += 1) points[i] = { x: flatPoints[2 * i], y: flatPoints[2 * i + 1] };
      this.held.set(route.edgeId, points);
    }
    const out = new Map<string, GridRoutedEdge>();
    for (const id of requestOrder) {
      const points = this.held.get(id);
      if (points) out.set(id, { edgeId: id, points });
    }
    const stats = options.stats;
    if (stats) {
      stats.fresh = answer.stats.fresh;
      stats.routed = answer.stats.routed;
      stats.kept = answer.stats.kept;
      stats.crossings = answer.stats.crossings;
      stats.pops = answer.stats.pops;
      stats.searches = answer.stats.searches;
    }
    const total = performance.now() - started;
    this.lastCost = { sentBytes: sent.length, receivedBytes: text.length, coreMs, hostMs: total - coreMs };
    return out;
  }

  /** The state as incremental-router.ts's plain data (route-memory.ts stores it), or undefined before a solve. */
  snapshot(): RouterSnapshot | undefined {
    const text = this.layout.layoutBoardSnapshot(this.handle);
    return text === null ? undefined : (JSON.parse(text) as RouterSnapshot);
  }

  /** Takes up a stored state (written by either router): the next solve tells the core the whole board again. */
  restore(snapshot: RouterSnapshot) {
    this.layout.layoutBoardRestore(this.handle, JSON.stringify(snapshot));
    this.forgetTold();
    this.restoredIds = new Set(snapshot.obstacles.map((o) => o.id));
  }

  knows(id: string): boolean {
    return this.restoredIds ? this.restoredIds.has(id) : this.obstacles.has(id);
  }

  reset() {
    this.layout.layoutBoardClose(this.handle);
    this.handle = this.layout.layoutBoardOpen();
    this.forgetTold();
    this.restoredIds = undefined;
  }

  /** Lets the core's board go; the router is unusable after. */
  dispose() {
    this.layout.layoutBoardClose(this.handle);
    this.handle = -1;
  }

  private forgetTold() {
    this.obstacles = new Map();
    this.obstacleOrder = [];
    this.requests = new Map();
    this.requestOrder = [];
    this.tuningText = undefined;
    this.held = new Map();
  }
}

/**
 * Whether the core's order, after it drops the gone ids and appends new ones (a map's put), already is `next`: then
 * the order need not cross.
 */
function sameOrderAfter(before: readonly string[], gone: readonly string[], next: readonly string[]): boolean {
  const goneSet = gone.length ? new Set(gone) : undefined;
  let j = 0;
  for (const id of before) {
    if (goneSet?.has(id)) continue;
    if (next[j] !== id) return false;
    j += 1;
  }
  // The rest of `next` are new ids, put in this order.
  return true;
}

/* ---------------------------------------------------------------------- */
/* Arrange                                                                 */

/** `runArrangeJob` through the core: the same job in, the same answer out. */
export function runArrangeJobOnCore(
  layout: Layout,
  job: ArrangeJob,
  onProgress?: (progress: ArrangeProgress) => void,
): ArrangeJobResult {
  const started = performance.now();
  const { seq } = job;
  // Exactly the fields runArrangeJob reads: the worker's arrange never carries ink or a polish budget of its own.
  const sent = {
    cards: job.cards,
    wires: job.wires,
    taste: job.taste,
    origin: job.origin,
    judgeInput: job.judgeInput,
    stage: job.stage,
    candidates: job.stage === "polish" ? job.candidates : undefined,
    which: job.which,
  };
  const sink = {
    progress: (step: number, stage: string, done: number, total: number) =>
      onProgress?.({ seq, step, stage, done, total }),
  };
  const out = JSON.parse(layout.layoutArrange(JSON.stringify(sent), sink)) as Omit<
    ArrangeJobResult,
    "seq" | "arrangeMs"
  >;
  const result: ArrangeJobResult = { seq, result: out.result, arrangeMs: performance.now() - started };
  if (out.columns) result.columns = out.columns;
  if (out.polishedPoints !== undefined) result.polishedPoints = out.polishedPoints;
  if (out.polishedCrossings !== undefined) result.polishedCrossings = out.polishedCrossings;
  return result;
}

/* ---------------------------------------------------------------------- */
/* The live-drag rule                                                      */

export type LiveDragBeat = "route" | "wait" | "hold";
const BEATS: readonly LiveDragBeat[] = ["route", "wait", "hold"];

/** The core's live-drag rule for one board (core.layout.LiveDrag). */
export interface LiveDragGate {
  /** A card is picked up: its first moving frame routes at once. */
  start(): void;
  frame(edgeCount: number, movesObstacles: boolean, nowMs: number): LiveDragBeat;
  /** When a waiting beat may route, on performance.now()'s clock. */
  dueAt(): number;
  /** A beat that held up the frame took this long. */
  meter(edgeCount: number, tookMs: number): void;
  dispose(): void;
}

/** The board's live-drag rule from the core, or null while the core is not there (the board keeps its own). */
export function coreLiveDrag(): LiveDragGate | null {
  const layout = layoutCore();
  if (!layout) return null;
  let handle = layout.layoutDragOpen();
  return {
    start: () => layout.layoutDragStart(handle),
    frame: (edgeCount, movesObstacles, nowMs) =>
      BEATS[layout.layoutDragFrame(handle, edgeCount, movesObstacles, nowMs)] ?? "hold",
    dueAt: () => layout.layoutDragDueAt(handle),
    meter: (edgeCount, tookMs) => layout.layoutDragMeter(handle, edgeCount, tookMs),
    dispose: () => {
      if (handle < 0) return;
      layout.layoutDragClose(handle);
      handle = -1;
    },
  };
}
