import { existsSync } from "node:fs";
import path from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { FactoryProject, ThroughputResult } from "@/lib/model/types";
import { withAdopt } from "@/lib/core/adopt";
import { core, loadCore, resetCoreForTests } from "@/lib/core/loader";
import { initLpEngine } from "@/lib/solver/lp-engine";
import { node, project, recipe, wire } from "@/lib/solver/plan/adapter/test-kit";

/**
 * With no Worker the main thread solves, and it asks for the compiled core the way the Worker does
 * (`solve-books.ts` `requestMainThreadCore`). Two things hold: the request is made when, and only when, a solve
 * runs on the main thread in a browser; and once the core is loaded those solves go through it and answer what the
 * website's engine answers.
 */
vi.setConfig({ testTimeout: 60_000 });

const present = existsSync(path.join(process.cwd(), "public", "core", "manifest.json"));

/** q (pinned) -> p -> c (pinned, wants more than q gives): a small solved plan. */
function chain(): FactoryProject {
  return project({
    recipes: [
      recipe("rq", [], [["W", 1]]),
      recipe("rp", [["W", 1]], [["X", 1]]),
      recipe("rc", [["X", 1]], [["Y", 1]]),
    ],
    nodes: [
      node("q", "rq", { solvePin: 1, solvePinExact: true }),
      node("p", "rp"),
      node("c", "rc", { solvePin: 2, solvePinExact: true }),
    ],
    edges: [wire("w1", "q", "p", "W"), wire("w2", "p", "c", "X")],
  });
}

class IdleWorker {
  onmessage: unknown = null;
  onerror: unknown = null;
  postMessage() {}
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.doUnmock("@/lib/core/solve-adopt");
  vi.resetModules();
});

/** A fresh solve-books whose core request is observable. */
async function booksWithSpy() {
  const warm = vi.fn(async () => true);
  vi.resetModules();
  vi.doMock("@/lib/core/solve-adopt", () => ({ warmSolveCore: warm }));
  const books = await import("./solve-books");
  return { warm, solveBooks: books.solveBooks };
}

describe("the main thread asks for the core", () => {
  it("asks once, on the first solve that runs here with no Worker, in a browser", async () => {
    vi.stubGlobal("window", {});
    const { warm, solveBooks } = await booksWithSpy();
    solveBooks(chain());
    solveBooks(chain());
    await new Promise((r) => setTimeout(r, 0));
    expect(warm).toHaveBeenCalledTimes(1);
  });

  it("does not ask while a Worker is there to solve", async () => {
    vi.stubGlobal("window", {});
    vi.stubGlobal("Worker", IdleWorker);
    const { warm, solveBooks } = await booksWithSpy();
    solveBooks(chain());
    await new Promise((r) => setTimeout(r, 0));
    expect(warm).not.toHaveBeenCalled();
  });

  it("does not ask outside a browser", async () => {
    const { warm, solveBooks } = await booksWithSpy();
    solveBooks(chain());
    await new Promise((r) => setTimeout(r, 0));
    expect(warm).not.toHaveBeenCalled();
  });
});

describe.skipIf(!present)("a main-thread solve through the core", () => {
  it("goes through the core once it is loaded and answers what the website's engine answers", async () => {
    await initLpEngine();
    resetCoreForTests();
    try {
      vi.resetModules();
      const { solveBooks } = await import("./solve-books");
      const adopt = await import("@/lib/core/solve-adopt");
      const rest = (r: ThroughputResult) => {
        const o: Partial<ThroughputResult> = { ...r };
        delete o.generatedAt;
        return JSON.parse(JSON.stringify(o)) as unknown;
      };

      // Before the core has loaded the website's engine answers.
      expect(adopt.solveEngine()).toBe("website");
      const before = rest(await withAdopt({ solve: false }, async () => solveBooks(chain())));

      await loadCore({ modules: ["solver"] });
      const spy = vi.spyOn(core().SolverExports, "solverSolveCached");
      const after = rest(await withAdopt({ solve: true }, async () => solveBooks(chain())));
      expect(adopt.solveEngine()).toBe("core");
      expect(spy).toHaveBeenCalled();
      expect(after).toEqual(before);

      // Off, the same call never reaches the core.
      spy.mockClear();
      await withAdopt({ solve: false }, async () => solveBooks(chain()));
      expect(spy).not.toHaveBeenCalled();
    } finally {
      resetCoreForTests();
    }
  });
});
