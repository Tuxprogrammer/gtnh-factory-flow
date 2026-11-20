/**
 * The layout adoption's shadow run through the website's own worker plumbing (restructure/adoption.md): the same jobs
 * through `runRouteSolveJob` (encoded and decoded as the route worker gets them) and `runArrangeJob`, once on the
 * TypeScript and once with the layout area on and the compiled core loaded, on each target; every answer must be the
 * same. The conformance run already holds the Java to the TypeScript on the JVM; this holds the compiled Java, and the
 * board it keeps between calls (`CoreBoardRouter`), to it inside the website.
 */
import { afterAll, describe, expect, it } from "vitest";
import { gunzipSync } from "node:zlib";
import { readConformanceBytes, readConformanceJson } from "@/test-support/conformance";
import { loadCore, resetCoreForTests } from "@/lib/core/loader";
import { runArrangeJob, type ArrangeJob, type ArrangeJobResult, type ArrangeProgress } from "@/lib/arrange-job";
import {
  boardRouterSnapshot,
  decodeRouteSolveJob,
  encodeRouteSolveJob,
  resetBoardRouters,
  restoreBoardRouter,
  runRouteSolveJob,
  type RouteSolveJob,
} from "./grid-route-job";
import { cardRimDocks, LANE_CAPACITY, type GridEndpoint, type GridObstacle, type GridRouteRequest } from "./grid-edge-router";
import { setAdoptOverride } from "@/lib/core/adopt";
import { arrangeCore, coreLiveDrag, layoutCore, runArrangeJobOnCore } from "./layout-core";
import { drawn, layoutTargets } from "./layout-shadow.test-support";
import { DEFAULT_ROUTER_TUNING, type RouterTuning } from "./router-tuning";

const targets = (await layoutTargets()).map(([target]) => target);

afterAll(async () => {
  setAdoptOverride("layout", undefined);
  resetBoardRouters();
  resetCoreForTests();
  await loadCore().catch(() => undefined);
});

/** Runs `fn` on the TypeScript (target undefined) or with the area on and the core on `target` loaded. */
async function on<T>(target: string | undefined, fn: () => T): Promise<T> {
  resetBoardRouters();
  if (target === undefined) {
    setAdoptOverride("layout", false);
  } else {
    resetCoreForTests();
    await loadCore({ target: target as "wasm-gc" | "js" });
    setAdoptOverride("layout", true);
    expect(layoutCore()).not.toBeNull();
  }
  try {
    return fn();
  } finally {
    setAdoptOverride("layout", undefined);
  }
}

/** A job as the route worker receives it: encoded on the main thread, decoded in the worker. */
function viaWorker(job: RouteSolveJob) {
  return runRouteSolveJob(decodeRouteSolveJob(encodeRouteSolveJob(job)));
}

/* ---- the reference boards, dragged as the conformance run drags them */

interface Box {
  key: string;
  x: number;
  y: number;
  w: number;
  h: number;
  square?: boolean;
}
interface Wire {
  key: string;
  from: number;
  to: number;
  fromSides: number;
  toSides: number;
  width: number;
}
interface Board {
  name: string;
  boxes: Box[];
  wires: Wire[];
  moving: number;
}

const SIDE_BITS: Record<string, number> = { right: 1, bottom: 2, left: 4, top: 8 };
const sides = (rim: GridEndpoint[], allowed: number) =>
  (allowed & 15) === 15 || (allowed & 15) === 0 ? rim : rim.filter((e) => (allowed & SIDE_BITS[e.side]) !== 0);
const obstaclesOf = (boxes: Box[]): GridObstacle[] =>
  boxes.map((b) => ({ id: b.key, left: b.x, top: b.y, right: b.x + b.w, bottom: b.y + b.h }));
function requestsOf(boxes: Box[], wires: Wire[]): GridRouteRequest[] {
  const rims = obstaclesOf(boxes).map((o) => cardRimDocks(o));
  return wires.map((w, i) => ({
    edgeId: w.key,
    order: i,
    sources: sides(rims[w.from], w.fromSides),
    targets: sides(rims[w.to], w.toSides),
    sourceCardId: boxes[w.from].key,
    targetCardId: boxes[w.to].key,
    sourceSquare: boxes[w.from].square === true,
    targetSquare: boxes[w.to].square === true,
    strokeWidth: Math.min(w.width, LANE_CAPACITY),
  }));
}

const boards = (
  JSON.parse(gunzipSync(readConformanceBytes("layout", "boards.json.gz")).toString("utf8")) as { boards: Board[] }
).boards.filter((b) => b.wires.length <= 130);

/** Fresh, three rough beats with the busiest box a cell further each, the drop, then a wire removed. */
function dragJobs(board: Board, boardKey: string): RouteSolveJob[] {
  const jobs: RouteSolveJob[] = [];
  for (let beat = 0; beat <= 4; beat += 1) {
    const shift = Math.min(beat, 3) * 20;
    const boxes = board.boxes.map((b, i) => (i === board.moving ? { ...b, x: b.x + shift } : b));
    jobs.push({
      signature: `${beat}`,
      seq: beat,
      obstacles: obstaclesOf(boxes),
      requests: requestsOf(boxes, board.wires),
      tuning: DEFAULT_ROUTER_TUNING,
      thorough: beat === 0 || beat === 4,
      boardKey,
    });
  }
  const last = jobs[jobs.length - 1];
  jobs.push({ ...last, seq: 5, requests: last.requests.slice(1), thorough: true });
  return jobs;
}

describe.skipIf(targets.length === 0)("layout through the core in the route worker's plumbing", () => {
  it.each(boards.map((b) => [b.name, b] as const))("drags %s the same", async (_name, board) => {
    const want = await on(undefined, () => dragJobs(board, "plan").map((job) => drawn(viaWorker(job).routes)));
    for (const target of targets) {
      const got = await on(target, () => dragJobs(board, "plan").map((job) => drawn(viaWorker(job).routes)));
      expect(got, target).toEqual(want);
    }
  }, 120_000);

  it("solves every router test board the same", async () => {
    const data = readConformanceJson("layout", "router-tests.json") as {
      solves: Array<{ name: string; obstacles: GridObstacle[]; requests: GridRouteRequest[]; tuning?: RouterTuning; pinned?: unknown[] }>;
    };
    const cases = data.solves.filter((c) => !c.pinned?.length);
    expect(cases.length).toBeGreaterThan(40);
    const jobs = (key: string) =>
      cases.map((c, i) => ({
        signature: c.name,
        seq: i,
        obstacles: c.obstacles,
        requests: c.requests,
        tuning: c.tuning,
        thorough: true,
        boardKey: `${key}${i}`,
      }));
    const want = await on(undefined, () => jobs("ts").map((job) => drawn(viaWorker(job).routes)));
    for (const target of targets) {
      const got = await on(target, () => jobs(target).map((job) => drawn(viaWorker(job).routes)));
      expect(got, target).toEqual(want);
    }
  });

  it("routes a board whose dock order ties in cycles the same (conformance/layout/tie-board.json.gz)", async () => {
    // Many ends at one card tie on their exit and heading, and the far-card mirror rule makes the order cyclic: the
    // result is then the sort algorithm's, and the core must sort as V8 does (core-layout JsSort).
    const board = JSON.parse(gunzipSync(readConformanceBytes("layout", "tie-board.json.gz")).toString("utf8")) as {
      obstacles: GridObstacle[];
      requests: GridRouteRequest[];
    };
    const job = (key: string): RouteSolveJob => ({
      signature: "tie",
      seq: 1,
      obstacles: board.obstacles,
      requests: board.requests,
      tuning: { ...DEFAULT_ROUTER_TUNING, negotiationRounds: 0 },
      thorough: true,
      boardKey: key,
    });
    const want = await on(undefined, () => drawn(viaWorker(job("ts")).routes));
    for (const target of targets) {
      const got = await on(target, () => drawn(viaWorker(job(target)).routes));
      expect(got, target).toEqual(want);
    }
  }, 120_000);

  it("stores and restores routes across the two routers (route-memory.ts)", async () => {
    const board = boards.find((b) => b.wires.length >= 20)!;
    const [first] = dragJobs(board, "plan");
    const tsStored = await on(undefined, () => {
      viaWorker(first);
      return boardRouterSnapshot("plan")!;
    });
    for (const target of targets) {
      const coreStored = await on(target, () => {
        const routes = viaWorker(first);
        const stored = boardRouterSnapshot("plan")!;
        // A plan stored by the TypeScript opens on the core with every route kept.
        resetBoardRouters();
        restoreBoardRouter("plan", tsStored);
        expect(drawn(viaWorker(first).routes)).toEqual(drawn(routes.routes));
        return stored;
      });
      expect(JSON.parse(JSON.stringify(coreStored)), target).toEqual(JSON.parse(JSON.stringify(tsStored)));
      // And one stored by the core opens on the TypeScript.
      await on(undefined, () => {
        restoreBoardRouter("plan", coreStored);
        expect(drawn(viaWorker(first).routes)).toEqual(drawn(viaWorker({ ...first, boardKey: "fresh" }).routes));
      });
    }
  });
});

/* ---- Arrange */

interface ArrangeBoard {
  name: string;
  cards: ArrangeJob["cards"];
  wires: ArrangeJob["wires"];
  taste?: ArrangeJob["taste"];
  judgeInput: NonNullable<ArrangeJob["judgeInput"]>;
}
const arrangeData = JSON.parse(gunzipSync(readConformanceBytes("layout", "arrange.json.gz")).toString("utf8")) as {
  tests: Array<{ name: string; call: string; input: Omit<ArrangeJob, "seq"> }>;
  boards: ArrangeBoard[];
};

/**
 * A judged arrange's polish stops on a wall clock as well as its count; a small count keeps every run on the count, so
 * both sides do the same work whatever the machine's speed.
 */
const POLISH_COUNT = 12;

function judgedJob(board: ArrangeBoard): ArrangeJob {
  return {
    seq: 1,
    cards: board.cards,
    wires: board.wires,
    taste: board.taste,
    judgeInput: { ...board.judgeInput, tuning: { ...board.judgeInput.tuning, polishBudget: POLISH_COUNT } },
  };
}

describe.skipIf(targets.length === 0)("Arrange through the core in the arrange worker's job", () => {
  it("arranges every board-arrange test input the same", async () => {
    const inputs = arrangeData.tests.filter((t) => t.call === "arrangeBoard").map((t, i) => ({ ...t.input, seq: i }));
    expect(inputs.length).toBeGreaterThan(10);
    const want = await on(undefined, () => inputs.map((job) => runArrangeJob(job).result));
    for (const target of targets) {
      const got = await on(target, () => inputs.map((job) => runArrangeJobOnCore(layoutCore()!, job).result));
      expect(got, target).toEqual(want);
    }
  }, 120_000);

  /**
   * Boards the JavaScript build of the core arranges differently from the TypeScript (the JVM and the WebAssembly
   * build agree with it), so the worker keeps the TypeScript Arrange on that build (`arrangeCore`). The cause is
   * TeaVM's JavaScript printer reordering `sum += a + b + c` in the optimiser's score (layout-core.ts `arrangeCore`);
   * the kernel's ExactMathPlugin fixes it, and then this entry fails: delete it and let `arrangeCore` take both builds.
   */
  const KNOWN_JS_ARRANGE = new Set(["factory-flow/Untitled design"]);

  const small = arrangeData.boards.filter((b) => b.cards.length <= 12);
  it.each(small.map((b) => [b.name, b] as const))("arranges %s the same, whole and in stages", async (name, board) => {
    const job = judgedJob(board);
    type Arrange = (job: ArrangeJob, onProgress?: (p: ArrangeProgress) => void) => ArrangeJobResult;
    const run = (arrange: Arrange) => () => {
      const progress: string[] = [];
      const whole = arrange(job, (p) => progress.push(`${p.step} ${p.stage}`));
      const columns = arrange({ ...job, stage: "columns" });
      const free = arrange({ ...job, stage: "free" });
      const candidates = { plain: columns.columns!.plain, challenger: columns.columns!.challenger, free: free.result };
      const polish = [0, 1].map((which) => {
        const p = arrange({ ...job, stage: "polish", candidates, which: which as 0 | 1 });
        return { result: p.result, points: p.polishedPoints, crossings: p.polishedCrossings };
      });
      return { whole: whole.result, progress: [...new Set(progress)], columns: columns.columns, free: free.result, polish };
    };
    const want = await on(undefined, run(runArrangeJob));
    for (const target of targets) {
      const got = await on(target, run((j, cb) => runArrangeJobOnCore(layoutCore()!, j, cb)));
      if (target === "js" && KNOWN_JS_ARRANGE.has(name)) expect(got, target).not.toEqual(want);
      else expect(got, target).toEqual(want);
      // The arrange worker's job takes the core's Arrange on the WebAssembly build only.
      expect(await on(target, () => arrangeCore() !== null), target).toBe(target === "wasm-gc");
    }
  }, 300_000);
});

/* ---- the live-drag rule */

describe.skipIf(targets.length === 0)("the core's live-drag rule", () => {
  it("routes the first moving frame, throttles the next, holds big boards and slow sizes", async () => {
    await on(targets[0], () => {
      const gate = coreLiveDrag()!;
      gate.start();
      expect(gate.frame(40, true, 1000)).toBe("route");
      expect(gate.frame(40, true, 1020)).toBe("wait");
      expect(gate.dueAt()).toBe(1050);
      expect(gate.frame(40, true, 1050)).toBe("route");
      expect(gate.frame(40, false, 2000)).toBe("hold");
      expect(gate.frame(1001, true, 3000)).toBe("hold");
      gate.meter(100, 37);
      expect(gate.frame(80, true, 4000)).toBe("hold");
      expect(gate.frame(79, true, 4100)).toBe("route");
      gate.start();
      expect(gate.frame(10, true, 4110)).toBe("route");
      gate.dispose();
    });
  });
});
