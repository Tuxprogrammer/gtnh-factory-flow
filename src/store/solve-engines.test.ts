import { existsSync } from "node:fs";
import path from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { FactoryProject, ThroughputResult } from "@/lib/model/types";
import { node, project, recipe, wire } from "@/lib/solver/plan/adapter/test-kit";
import { settleSolveEngines } from "./solve-engines";

/**
 * The solve Worker's engine (`solve-engines.ts`): every solve waits for the compiled core, bounded; where it does not
 * load in time the website's own engine answers and the page says solving is degraded (`solve-health.ts`).
 */
vi.setConfig({ testTimeout: 60_000 });

const never = () => new Promise<boolean>(() => {});

describe("the engine a Worker solve waits for", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("waits for the core", async () => {
    expect(await settleSolveEngines({ core: async () => true })).toBe("core");
  });

  it("answers on the homegrown simplex when the core will not solve here (area off, load failed)", async () => {
    expect(await settleSolveEngines({ core: async () => false })).toBe("simplex");
  });

  it("treats a core load that throws as a failed load", async () => {
    expect(
      await settleSolveEngines({ core: () => Promise.reject(new Error("fetch failed")) }),
    ).toBe("simplex");
  });

  it("stops waiting for a core load that never answers", async () => {
    vi.useFakeTimers();
    let done = false;
    const settled = settleSolveEngines({ core: never, coreWaitMs: 10_000 }).then((e) => {
      done = true;
      return e;
    });
    await vi.advanceTimersByTimeAsync(9_999);
    expect(done).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
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


type Reply = { key: string; result?: ThroughputResult; error?: string; engine?: string };

/** The Worker module itself, in this thread, with the core's warm-up as given. */
async function worker(warm?: () => Promise<boolean>) {
  vi.resetModules();
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
  return { send };
}

describe("the solve Worker", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.doUnmock("@/lib/core/solve-adopt");
    vi.resetModules();
  });

  it.skipIf(!present)("solves through the core and says so", async () => {
    const { send } = await worker();
    const { solveEngine } = await import("@/lib/core/solve-adopt");
    const r = await send(chain());
    expect(r.error).toBeUndefined();
    expect(r.result!.nodes.p!.utilization).toBeGreaterThan(0);
    expect(solveEngine()).toBe("core");
    expect(r.engine).toBe("core");
  });

  it("still solves, on the website's own engine, when the core fails to load, and says so", async () => {
    const { send } = await worker(async () => false);
    // A core an earlier test loaded in this thread must not answer: the solve area off is a core that is not there.
    const { setAdoptOverride } = await import("@/lib/core/adopt");
    setAdoptOverride("solve", false);
    const r = await send(chain()).finally(() => setAdoptOverride("solve", undefined));
    expect(r.error).toBeUndefined();
    expect(r.result!.nodes.p!.utilization).toBeGreaterThan(0);
    expect(r.engine).toBe("website");
  });
});

describe("the page hears which engine solved", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.resetModules();
  });

  /** solve-books with a stand-in Worker that answers every plan with `engine`. */
  async function booksAnswering(engine: string) {
    vi.resetModules();
    class AnsweringWorker {
      onmessage: ((e: MessageEvent) => void) | null = null;
      onerror: unknown = null;
      postMessage(data: { key: string }) {
        queueMicrotask(() =>
          this.onmessage?.({
            data: { key: data.key, result: { nodes: {}, storages: {}, edges: {} }, engine },
          } as MessageEvent),
        );
      }
    }
    vi.stubGlobal("Worker", AnsweringWorker);
    const books = await import("./solve-books");
    const health = await import("./solve-health");
    return { solveBooks: books.solveBooks, health };
  }

  it("a reply from the stand-in marks solving degraded, and one from the core clears it", async () => {
    const plan = { ...chain(), planMode: true };
    const stand = await booksAnswering("website");
    stand.solveBooks(plan);
    await vi.waitFor(() => expect(stand.health.isSolveDegraded()).toBe(true));
    const core = await booksAnswering("core");
    core.health.setSolveDegraded(true);
    core.solveBooks({ ...plan, id: "other" });
    await vi.waitFor(() => expect(core.health.isSolveDegraded()).toBe(false));
  });
});
