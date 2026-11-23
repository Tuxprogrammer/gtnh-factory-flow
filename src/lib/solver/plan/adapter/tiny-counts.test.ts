/**
 * A card or recipe slot that needs a tiny share of a machine still runs: a fast recipe on a big hatch makes a real
 * 0.05/s with a millionth of a machine. Idle is what the solver moves nothing on, never a count below a fixed number.
 */
import { afterAll, beforeAll, describe, expect, it } from "vitest";

import { CORE_LOAD_MS, useCoreSolver } from "@/test-support/core-solve";
import { calculateThroughput } from "../../throughput";
import { setSolveCore, typescriptSolveCore } from "./engine";
import { drawer, node, project, recipe, wire } from "./test-kit";

beforeAll(() => useCoreSolver(), CORE_LOAD_MS);
afterAll(() => setSolveCore(undefined));

/** a (k raw -> k i) feeds b (k i -> k p), asked 0.05 p/s: each needs 0.05 / k machines. */
const chain = (k: number) =>
  project({
    recipes: [recipe("ra", [["raw", k]], [["i", k]]), recipe("rb", [["i", k]], [["p", k]])],
    nodes: [node("a", "ra"), node("b", "rb")],
    storages: [drawer("raws", "raw"), drawer("pd", "p", { targetMode: "at-least", targetPerSecond: 0.05 })],
    edges: [
      wire("raws-a", "raws", "a", "raw", {}, { fromDrawer: true }),
      wire("a-b", "a", "b", "i"),
      wire("b-pd", "b", "pd", "p", {}, { toDrawer: true }),
    ],
  });

/** The same two recipes as two slots of one card, its first slot's output wired to its second slot's input. */
const sharedCard = (k: number) =>
  project({
    recipes: [recipe("ra", [["raw", k]], [["i", k]]), recipe("rb", [["i", k]], [["p", k]])],
    nodes: [node("s", "ra", { extraRecipes: [{ recipeId: "rb" }] })],
    storages: [drawer("raws", "raw"), drawer("pd", "p", { targetMode: "at-least", targetPerSecond: 0.05 })],
    edges: [
      wire("raws-s", "raws", "s", "raw", {}, { fromDrawer: true }),
      wire("s-s", "s", "s", "i", { from: 0, to: 1 }),
      wire("s-pd", "s", "pd", "p", { from: 1 }, { toDrawer: true }),
    ],
  });

describe.each([
  ["the core", () => setSolveCore(undefined)],
  ["the website's engine", () => setSolveCore(typescriptSolveCore)],
])("a tiny machine count is not idle (%s)", (_engine, use) => {
  it.each([1, 1e5, 1e7])("single cards at %d per craft read their real flows", (k) => {
    use();
    const result = calculateThroughput(chain(k), { generatedAt: "t" });
    expect(result.plan!.forced).toEqual([]);
    for (const id of ["a", "b"]) {
      const n = result.nodes[id]!;
      expect(n.theoreticalMachinesRequired, id).toBeCloseTo(0.05 / k, 15);
      expect(n.utilization, id).toBe(1);
      expect(n.status, id).toBe("balanced");
      expect(n.operationRatePerSecond * k, id).toBeCloseTo(0.05, 9);
      expect(n.euT / 30, id).toBeCloseTo(0.05 / k, 12);
    }
    expect(result.totalEuT / 60).toBeCloseTo(0.05 / k, 12);
    const p = result.resources["item:p" as keyof typeof result.resources]!;
    expect(p.producedPerSecond).toBeCloseTo(0.05, 9);
    expect(result.storages["pd"]!.producedPerSecond).toBeCloseTo(0.05, 9);
  });

  it("a shared card's slots at a millionth of a machine read their real flows", () => {
    use();
    const result = calculateThroughput(sharedCard(1e5), { generatedAt: "t" });
    expect(result.plan!.forced).toEqual([]);
    const p = result.resources["item:p" as keyof typeof result.resources]!;
    expect(p.producedPerSecond).toBeCloseTo(0.05, 9);
    expect(result.totalEuT / 60).toBeCloseTo(0.05 / 1e5, 12);
  });

  it("a card the plan does not need still reads idle", () => {
    use();
    const plan = chain(1e5);
    plan.recipes.push(recipe("rc", [["raw", 1]], [["q", 1]]));
    plan.nodes.push(node("c", "rc"));
    plan.edges.push(wire("raws-c", "raws", "c", "raw", {}, { fromDrawer: true }));
    const result = calculateThroughput(plan, { generatedAt: "t" });
    expect(result.nodes["c"]!.utilization).toBe(0);
    expect(result.nodes["c"]!.status).toBe("underutilized");
    expect(result.nodes["a"]!.utilization).toBe(1);
  });
});
