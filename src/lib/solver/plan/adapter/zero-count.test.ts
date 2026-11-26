/**
 * A plan card's stored count is not a control: the solver chooses how many machines run. A card stored at 0 machines
 * (the mod's export writes the count it solved, 0 for a card it did not need) must solve exactly as the same card at
 * 1: its rate, EU/t and port flows are the solve's, never dropped while the drawer it feeds fills.
 */
import { afterAll, beforeAll, describe, expect, it } from "vitest";

import type { FactoryProject } from "../../../model/types";
import { CORE_LOAD_MS, useCoreSolver } from "@/test-support/core-solve";
import { calculateThroughput } from "../../throughput";
import { setSolveCore, typescriptSolveCore } from "./engine";
import { drawer, node, project, recipe, wire } from "./test-kit";

beforeAll(() => useCoreSolver(), CORE_LOAD_MS);
afterAll(() => setSolveCore(undefined));

/** raws -> a (2 raw -> 1 i) -> b (1 i -> 3 p + 1 q) -> p drawer at least 6/s, q to a byproduct drawer. */
const plan = (countA: number, countB: number, shared = false): FactoryProject =>
  project({
    recipes: [recipe("ra", [["raw", 2]], [["i", 1]]), recipe("rb", [["i", 1]], [["p", 3], ["q", 1]])],
    nodes: shared
      ? [node("a", "ra", { machineCount: countA, extraRecipes: [{ recipeId: "rb" }] })]
      : [node("a", "ra", { machineCount: countA }), node("b", "rb", { machineCount: countB })],
    storages: [
      drawer("raws", "raw"),
      drawer("pd", "p", { targetMode: "at-least", targetPerSecond: 6 }),
      drawer("qd", "q"),
    ],
    edges: shared
      ? [
          wire("raws-a", "raws", "a", "raw", {}, { fromDrawer: true }),
          wire("a-a", "a", "a", "i", { from: 0, to: 1 }),
          wire("a-pd", "a", "pd", "p", { from: 1 }, { toDrawer: true }),
          wire("a-qd", "a", "qd", "q", { from: 1 }, { toDrawer: true }),
        ]
      : [
          wire("raws-a", "raws", "a", "raw", {}, { fromDrawer: true }),
          wire("a-b", "a", "b", "i"),
          wire("b-pd", "b", "pd", "p", {}, { toDrawer: true }),
          wire("b-qd", "b", "qd", "q", {}, { toDrawer: true }),
        ],
  });

/** What the board reads off a solve: per card its count, rate, EU/t, utilization and flows; the totals; the drawers. */
function reading(project: FactoryProject) {
  const result = calculateThroughput(project, { generatedAt: "t" });
  const cards = Object.fromEntries(
    Object.values(result.nodes).map((n) => [
      n.nodeId,
      {
        machines: n.theoreticalMachinesRequired,
        rate: n.operationRatePerSecond,
        euT: n.euT,
        utilization: n.utilization,
        status: n.status,
        inputs: Object.fromEntries(Object.entries(n.inputs).map(([k, f]) => [k, f.amountPerSecond])),
        outputs: Object.fromEntries(Object.entries(n.outputs).map(([k, f]) => [k, f.amountPerSecond])),
      },
    ]),
  );
  const drawers = Object.fromEntries(
    Object.values(result.storages).map((s) => [s.storageId, [s.producedPerSecond, s.consumedPerSecond]]),
  );
  const resources = Object.fromEntries(
    Object.entries(result.resources).map(([k, r]) => [k, [r.producedPerSecond, r.consumedPerSecond]]),
  );
  const edges = Object.fromEntries(Object.entries(result.edges).map(([k, e]) => [k, e.transferredPerSecond]));
  return { cards, drawers, resources, edges, totalEuT: result.totalEuT };
}

describe.each([
  ["the core", () => setSolveCore(undefined)],
  ["the website's engine", () => setSolveCore(typescriptSolveCore)],
])("a card stored at 0 machines (%s)", (_engine, use) => {
  it("solves as the same card stored at 1", () => {
    use();
    const one = reading(plan(1, 1));
    // a at 2 i/s needs 2 machines, b at 2 crafts/s needs 2: the counts are the solve's.
    expect(one.cards.a!.machines).toBeCloseTo(2, 9);
    expect(one.cards.b!.machines).toBeCloseTo(2, 9);
    expect(one.cards.b!.euT).toBeCloseTo(60, 9);
    for (const [a, b] of [
      [0, 1],
      [1, 0],
      [0, 0],
    ] as const) {
      expect(reading(plan(a, b)), `counts ${a}, ${b}`).toEqual(one);
    }
  });

  it("a shared card stored at 0 solves as at 1", () => {
    use();
    const one = reading(plan(1, 1, true));
    expect(one.totalEuT).toBeCloseTo(120, 9);
    expect(reading(plan(0, 0, true))).toEqual(one);
  });

  it("a card the plan does not need still reads idle at 0", () => {
    use();
    const p = plan(0, 0);
    p.recipes.push(recipe("rc", [["raw", 1]], [["z", 1]]));
    p.nodes.push(node("c", "rc", { machineCount: 0 }));
    p.edges.push(wire("raws-c", "raws", "c", "raw", {}, { fromDrawer: true }));
    const result = calculateThroughput(p, { generatedAt: "t" });
    expect(result.nodes["c"]!.utilization).toBe(0);
    expect(result.nodes["a"]!.utilization).toBe(1);
    expect(result.totalEuT).toBeCloseTo(120, 9);
  });
});

describe("a Build card stored at 0 machines", () => {
  it("is not built: it runs nothing and draws nothing", () => {
    const build = { ...plan(0, 1), planMode: false, solveMode: false };
    const result = calculateThroughput(build, { generatedAt: "t" });
    expect(result.nodes["a"]!.operationRatePerSecond).toBe(0);
    expect(result.nodes["a"]!.euT).toBe(0);
    expect(result.edges["raws-a"]!.transferredPerSecond).toBe(0);
  });
});
