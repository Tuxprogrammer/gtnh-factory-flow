import { afterEach, expect, it, vi } from "vitest";
import type { FactoryProject, ThroughputResult } from "@/lib/model/types";

/**
 * Manual recalculation is gone and plan boards never showed its switch, so a
 * browser that saved it "off" must lose that value on load, or every plan it
 * opens shows the empty board's books.
 */

const RETIRED_KEY = "gtnh-factory-flow.auto-solve.v1";

afterEach(() => {
  vi.unstubAllGlobals();
  vi.resetModules();
});

it("removes the retired manual-recalculation setting when it loads", async () => {
  const store = new Map<string, string>([[RETIRED_KEY, "off"]]);
  vi.stubGlobal("localStorage", {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => void store.set(key, value),
    removeItem: (key: string) => void store.delete(key),
  });
  vi.resetModules();
  await import("./solve-books");
  expect(store.has(RETIRED_KEY)).toBe(false);
});

it("loads when storage refuses access", async () => {
  vi.stubGlobal("localStorage", {
    removeItem: () => {
      throw new Error("SecurityError");
    },
  });
  vi.resetModules();
  await expect(import("./solve-books")).resolves.toHaveProperty("solveBooks");
});

/**
 * A worker that answers with an error must still end the wait: the canvas
 * gets books that are not stale and say the plan could not be solved, or the
 * "Working out the numbers" pill spins forever with nothing shown.
 */
it("ends the wait with a failure when the worker reports an error", async () => {
  class ErrorWorker {
    onmessage: ((event: { data: unknown }) => void) | null = null;
    onerror: ((event: unknown) => void) | null = null;
    postMessage(data: { key: string }) {
      queueMicrotask(() => this.onmessage?.({ data: { key: data.key, error: "boom" } }));
    }
  }
  vi.stubGlobal("Worker", ErrorWorker);
  vi.resetModules();
  const { registerBooksSink, solveBooks } = await import("./solve-books");
  const landed: ThroughputResult[] = [];
  registerBooksSink((result) => landed.push(result));
  const project = {
    id: "p",
    name: "p",
    planMode: true,
    nodes: [],
    edges: [],
    storages: [],
    recipes: [],
  };
  expect(solveBooks(project as unknown as FactoryProject).stale).toBe(true);
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(landed).toHaveLength(1);
  expect(landed[0]!.stale).toBeFalsy();
  expect(landed[0]!.plan?.failure).toBeDefined();
});
