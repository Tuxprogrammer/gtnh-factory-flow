import { existsSync } from "node:fs";
import path from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { FactoryProject, ThroughputResult } from "@/lib/model/types";
import { node, project, recipe, wire } from "@/lib/solver/plan/adapter/test-kit";
import { settleSolveEngines } from "./solve-engines";

/**
 * The solve Worker's engines (`solve-engines.ts`): where the core solves, a solve waits for it and HiGHS is never
 * fetched; where it does not (no WebAssembly GC, a failed or hung load) HiGHS loads; no wait is unbounded.
 */
vi.setConfig({ testTimeout: 60_000 });

const never = () => new Promise<boolean>(() => {});

describe("the engines a Worker solve waits for", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("waits for the core and never fetches HiGHS when the core solves", async () => {
    const highs = vi.fn(async () => true);
    expect(await settleSolveEngines({ core: async () => true, highs })).toBe("core");
    expect(highs).not.toHaveBeenCalled();
  });

  it("loads HiGHS when the core will not solve here (no WebAssembly GC, area off, load failed)", async () => {
    const highs = vi.fn(async () => true);
    expect(await settleSolveEngines({ core: async () => false, highs })).toBe("highs");
    expect(highs).toHaveBeenCalledTimes(1);
  });

  it("treats a core load that throws as a failed load", async () => {
    const highs = vi.fn(async () => true);
    expect(
      await settleSolveEngines({ core: () => Promise.reject(new Error("fetch failed")), highs }),
    ).toBe("highs");
  });

  it("stops waiting for a core load that never answers, then loads HiGHS", async () => {
    vi.useFakeTimers();
    const highs = vi.fn(async () => true);
    const settled = settleSolveEngines({ core: never, highs, coreWaitMs: 10_000 });
    await vi.advanceTimersByTimeAsync(9_999);
    expect(highs).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    expect(await settled).toBe("highs");
  });

  it("answers on the homegrown simplex when neither loads in time", async () => {
    vi.useFakeTimers();
    const settled = settleSolveEngines({
      core: never,
      highs: never,
      coreWaitMs: 10,
      highsWaitMs: 20,
    });
    await vi.advanceTimersByTimeAsync(30);
    expect(await settled).toBe("simplex");
  });
});

const present = existsSync(path.join(process.cwd(), "public", "core", "manifest.json"));

/** q (pinned) -> p -> c: a small solved plan. */
function chain(): FactoryProject {
  return project({
    recipes: [recipe("rq", [], [["W", 1]]), recipe("rp", [["W", 1]], [["X", 1]])],
    nodes: [node("q", "rq", { solvePin: 1, solvePinExact: true }), node("p", "rp")],
    edges: [wire("w1", "q", "p", "W")],
  });
}

type Reply = { key: string; result?: ThroughputResult; error?: string };

/** The Worker module itself, in this thread, with HiGHS's loader observed and the core's warm-up as given. */
async function worker(warm?: () => Promise<boolean>) {
  vi.resetModules();
  const initLpEngine = vi.fn(async () => true);
  vi.doMock("@/lib/solver/lp-engine", async (original) => ({
    ...(await original<typeof import("@/lib/solver/lp-engine")>()),
    initLpEngine,
  }));
  if (warm)
    vi.doMock("@/lib/core/solve-adopt", async (original) => ({
      ...(await original<typeof import("@/lib/core/solve-adopt")>()),
      warmSolveCore: warm,
    }));
  let reply: ((r: Reply) => void) | undefined;
  const scope: { onmessage?: (e: MessageEvent) => Promise<void>; postMessage: (r: Reply) => void } =
    {
      postMessage: (r) => reply?.(r),
    };
  vi.stubGlobal("self", scope);
  await import("./solve-books.worker");
  const send = (p: FactoryProject) =>
    new Promise<Reply>((resolve) => {
      reply = resolve;
      void scope.onmessage!({ data: { key: "k", project: p } } as MessageEvent);
    });
  return { initLpEngine, send };
}

describe("the solve Worker", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.doUnmock("@/lib/solver/lp-engine");
    vi.doUnmock("@/lib/core/solve-adopt");
    vi.resetModules();
  });

  it.skipIf(!present)("solves through the core without fetching HiGHS", async () => {
    const { initLpEngine, send } = await worker();
    const { solveEngine } = await import("@/lib/core/solve-adopt");
    const r = await send(chain());
    expect(r.error).toBeUndefined();
    expect(r.result!.nodes.p!.utilization).toBeGreaterThan(0);
    expect(solveEngine()).toBe("core");
    expect(initLpEngine).not.toHaveBeenCalled();
  });

  it("still solves, with HiGHS, when the core fails to load", async () => {
    const { initLpEngine, send } = await worker(async () => false);
    const r = await send(chain());
    expect(r.error).toBeUndefined();
    expect(r.result!.nodes.p!.utilization).toBeGreaterThan(0);
    expect(initLpEngine).toHaveBeenCalledTimes(1);
  });
});
