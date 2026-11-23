/**
 * A stored leftover split names each place by one of its ports. A place is a gate: every port on one side of a wire
 * network, and the solver names it by its lowest port. Combining twin cards renames their ports, so the lowest port of
 * a shared gate can become another twin's; the stored split must still name the same place.
 */
import { afterAll, beforeAll, describe, expect, it } from "vitest";

import { CORE_LOAD_MS, useCoreSolver } from "@/test-support/core-solve";
import { calculateThroughput } from "../../throughput";
import { setSolveCore, typescriptSolveCore } from "./engine";
import { drawer, node, project, recipe, wire } from "./test-kit";
import type { FactoryNode } from "../../../model/types";

beforeAll(() => useCoreSolver(), CORE_LOAD_MS);
afterAll(() => setSolveCore(undefined));

const recipes = [
  recipe("rm", [["raw", 1]], [["c", 10], ["g", 1]]),
  recipe("ru", [["c", 15]], [["d", 1]]),
  recipe("rv", [["d", 1]], [["p", 1]]),
];
const storages = [
  drawer("raws", "raw"),
  drawer("gd", "g", { targetMode: "exact", targetPerSecond: 1 }),
  drawer("pd", "p", { targetMode: "exact", targetPerSecond: 1 }),
];
const upstream = [
  wire("raws-m", "raws", "m", "raw", {}, { fromDrawer: true }),
  wire("m-gd", "m", "gd", "g", {}, { toDrawer: true }),
  wire("m-u", "m", "u", "c"),
];
/** The player put the whole 5 c shortfall on the d side, at the twins' shared input. */
const split = (dPort: string) => ({
  [["u|i|input:item:c", dPort].sort().join("\u001f")]: { "u|i|input:item:c": 0, [dPort]: 1 },
});

/** m makes 10 c, u needs 15 for the 1 d that twins v1 and v2 (d -> p) finish: 5 c are bought, or 1/3 d. */
const twins = () =>
  project({
    recipes,
    nodes: [node("m", "rm"), node("u", "ru"), node("v1", "rv"), node("v2", "rv")],
    storages,
    edges: [
      ...upstream,
      wire("u-v1", "u", "v1", "d"),
      wire("u-v2", "u", "v2", "d"),
      wire("v1-pd", "v1", "pd", "p", {}, { toDrawer: true }),
      wire("v2-pd", "v2", "pd", "p", {}, { toDrawer: true }),
    ],
    extra: { spareShares: split("v1|i|input:item:d") },
  });

/** The same twins combined into v2: v1 is now v2's second slot, and the stored split moved with it. */
const combined = () =>
  project({
    recipes,
    nodes: [node("m", "rm"), node("u", "ru"), node("v2", "rv", { extraRecipes: [{ recipeId: "rv" }] } as Partial<FactoryNode>)],
    storages,
    edges: [
      ...upstream,
      wire("u-v2", "u", "v2", "d"),
      wire("u-v2r1", "u", "v2", "d", { to: 1 }),
      wire("v2-pd", "v2", "pd", "p", {}, { toDrawer: true }),
      wire("v2r1-pd", "v2", "pd", "p", { from: 1 }, { toDrawer: true }),
    ],
    extra: { spareShares: split("v2#r1|i|input:item:d") },
  });

const bought = (result: ReturnType<typeof calculateThroughput>, resource: string) =>
  result.plan!.forced.filter((f) => f.resourceKey === resource).reduce((total, f) => total + f.perSecond, 0);

describe.each([
  ["the core", () => setSolveCore(undefined)],
  ["the website's engine", () => setSolveCore(typescriptSolveCore)],
])("a stored leftover split keeps its place when twin cards are combined (%s)", (_engine, use) => {
  it.each([
    ["two twin cards", twins],
    ["the twins combined into one card", combined],
  ])("%s: the split holds", (_name, plan) => {
    use();
    const result = calculateThroughput(plan(), { generatedAt: "t" });
    const leftovers = result.plan!.leftovers;
    expect(leftovers.length).toBe(1);
    expect(leftovers[0]!.custom).toBe(true);
    expect(bought(result, "item:c")).toBeCloseTo(0, 6);
    expect(bought(result, "item:d")).toBeCloseTo(1 / 3, 6);
  });
});
