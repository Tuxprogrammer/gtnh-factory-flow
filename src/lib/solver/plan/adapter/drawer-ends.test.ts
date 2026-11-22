/**
 * A drawer on a wired port is the player's own end, not a trade (docs/up-to.md, "Drawers and buffers"), with or without
 * Up to pools. A loop is one machine step, so every port in it trades at one level; were a drawer priced as a trade
 * there, the least amount at that level would decide between the player's drawer and a forced flow. These plans are the
 * two shapes where that went wrong: joining drawers so a plan's own output feeds its own input.
 */
import { afterAll, beforeAll, describe, expect, it } from "vitest";

import { CORE_LOAD_MS, useCoreSolver } from "@/test-support/core-solve";
import { calculateThroughput } from "../../throughput";
import { setSolveCore, typescriptSolveCore } from "./engine";
import { drawer, node, project, recipe, wire } from "./test-kit";

beforeAll(() => useCoreSolver(), CORE_LOAD_MS);
afterAll(() => setSolveCore(undefined));

/**
 * a (r -> i), b (100 i -> p + 100 k), c (100 k -> 90 r) in a ring; a source drawer of r on a's wired input makes up the
 * 10 r a lap loses. Buying b's 100 i is one craft of b, drawing the 10 r ten crafts of a: as one trade level, the
 * plan bought the intermediate and left the ring idle.
 */
const ringWithSource = () =>
  project({
    recipes: [
      recipe("ra", [["r", 1]], [["i", 1]]),
      recipe("rb", [["i", 100]], [["p", 1], ["k", 100]]),
      recipe("rc", [["k", 100]], [["r", 90]]),
    ],
    nodes: [node("a", "ra"), node("b", "rb"), node("c", "rc")],
    storages: [drawer("rs", "r"), drawer("pd", "p", { targetMode: "at-least", targetPerSecond: 1 })],
    edges: [
      wire("rs-a", "rs", "a", "r", {}, { fromDrawer: true }),
      wire("c-a", "c", "a", "r"),
      wire("a-b", "a", "b", "i"),
      wire("b-c", "b", "c", "k"),
      wire("b-pd", "b", "pd", "p", {}, { toDrawer: true }),
    ],
  });

/**
 * m makes q (and g, which a product drawer asks 1/s of); n finishes q with f into p. f comes from a closed ring with no
 * starter: u (s + o -> h), v (h -> nn + f), w (nn -> s + 2 o), whose spare o goes to a byproduct drawer on w's wired
 * port. That drawer's port counted 2 steps ahead (the ring, then n), q's forced sale 1, so the plan idled the ring to
 * keep o out of its drawer and sold q unfinished.
 */
const closedRingWithByproduct = () =>
  project({
    recipes: [
      recipe("rm", [["raw", 1]], [["q", 1], ["g", 1]]),
      recipe("rn", [["q", 1], ["f", 1]], [["p", 1]]),
      recipe("ru", [["s", 1], ["o", 1]], [["h", 1]]),
      recipe("rv", [["h", 1]], [["nn", 1], ["f", 1]]),
      recipe("rw", [["nn", 1]], [["s", 1], ["o", 2]]),
    ],
    nodes: [node("m", "rm"), node("n", "rn"), node("u", "ru"), node("v", "rv"), node("w", "rw")],
    storages: [
      drawer("raws", "raw"),
      drawer("gd", "g", { targetMode: "at-least", targetPerSecond: 1 }),
      drawer("pd", "p"),
      drawer("ob", "o", { drainMode: "byproduct" }),
    ],
    edges: [
      wire("raws-m", "raws", "m", "raw", {}, { fromDrawer: true }),
      wire("m-gd", "m", "gd", "g", {}, { toDrawer: true }),
      wire("m-n", "m", "n", "q"),
      wire("v-n", "v", "n", "f"),
      wire("n-pd", "n", "pd", "p", {}, { toDrawer: true }),
      wire("u-v", "u", "v", "h"),
      wire("v-w", "v", "w", "nn"),
      wire("w-u-s", "w", "u", "s"),
      wire("w-u-o", "w", "u", "o"),
      wire("w-ob", "w", "ob", "o", {}, { toDrawer: true }),
    ],
  });

const rate = (result: ReturnType<typeof calculateThroughput>, id: string) =>
  result.nodes[id]!.operationRatePerSecond * Math.min(1, Math.max(0, result.nodes[id]!.utilization));

describe.each([
  ["the core", () => setSolveCore(undefined)],
  ["the website's engine", () => setSolveCore(typescriptSolveCore)],
])("a drawer on a wired port is the player's own end (%s)", (_engine, use) => {
  it("a ring draws what it loses from its source drawer rather than buy an intermediate", () => {
    use();
    const result = calculateThroughput(ringWithSource(), { generatedAt: "t" });
    expect(result.plan!.forced).toEqual([]);
    expect(rate(result, "a")).toBeCloseTo(100, 6);
    expect(rate(result, "b")).toBeCloseTo(1, 6);
    expect(rate(result, "c")).toBeCloseTo(1, 6);
    expect(result.storages["rs"]!.consumedPerSecond).toBeCloseTo(10, 6);
  });

  it("a closed ring runs to finish the line and puts its spare into its byproduct drawer", () => {
    use();
    const result = calculateThroughput(closedRingWithByproduct(), { generatedAt: "t" });
    expect(result.plan!.forced).toEqual([]);
    for (const id of ["m", "n", "u", "v", "w"]) expect(rate(result, id), id).toBeCloseTo(1, 6);
    expect(result.storages["pd"]!.producedPerSecond).toBeCloseTo(1, 6);
    expect(result.storages["ob"]!.producedPerSecond).toBeCloseTo(1, 6);
  });
});
