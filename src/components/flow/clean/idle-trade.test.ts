import { describe, expect, it } from "vitest";
import { calculateThroughput } from "@/lib/solver/throughput";
import { drawer, node, project, recipe, wire } from "@/lib/solver/plan/adapter/test-kit";
import { idleTradeOf, mayIdleTrade } from "./idle-trade";

/**
 * k (Any) would turn Q and Z into V, but Z is capped at none, so a (Exactly 1) takes its V from a source drawer and
 * makes S. c (Any) would turn S into Q, which only goes back to k. Running c would force Q out, so the plan sends S
 * out at a instead and c runs none.
 */
function idleChain() {
  return project({
    recipes: [
      recipe(
        "rk",
        [
          ["Q", 1],
          ["Z", 1],
        ],
        [["V", 1]],
      ),
      recipe(
        "ra",
        [["V", 1]],
        [
          ["S", 1],
          ["P", 1],
        ],
      ),
      recipe("rc", [["S", 1]], [["Q", 1]]),
    ],
    nodes: [
      node("k", "rk"),
      node("a", "ra", { solvePin: 1, solvePinExact: true }),
      node("c", "rc"),
    ],
    storages: [
      drawer("dv", "V"),
      drawer("dz", "Z", { targetMode: "at-most", targetPerSecond: 0 }),
      drawer("dp", "P"),
    ],
    edges: [
      wire("w1", "k", "a", "V"),
      wire("w2", "a", "c", "S"),
      wire("w3", "c", "k", "Q"),
      wire("w4", "dv", "a", "V", {}, { fromDrawer: true }),
      wire("w5", "dz", "k", "Z", {}, { fromDrawer: true }),
      wire("w6", "a", "dp", "P", {}, { toDrawer: true }),
    ],
  });
}

/** a (Exactly 1) makes S for c (Any), whose Q goes to d, already fed in full by e: here the plan runs c. */
function runningChain() {
  return project({
    recipes: [
      recipe("ra", [], [["S", 1]]),
      recipe("rc", [["S", 1]], [["Q", 1]]),
      recipe("re", [], [["Q", 1]]),
      recipe("rd", [["Q", 1]], []),
    ],
    nodes: [
      node("a", "ra", { solvePin: 1, solvePinExact: true }),
      node("c", "rc"),
      node("e", "re", { solvePin: 1, solvePinExact: true }),
      node("d", "rd", { solvePin: 1, solvePinExact: true }),
    ],
    edges: [wire("w1", "a", "c", "S"), wire("w2", "c", "d", "Q"), wire("w3", "e", "d", "Q")],
  });
}

const solve = (p: ReturnType<typeof project>) => calculateThroughput(p, { generatedAt: "t" });

describe("a card on Any left at zero while its input is sent out", () => {
  it("names the output running it would force out, the input sent out instead and where", () => {
    const result = solve(idleChain());
    expect(result.nodes.c?.theoreticalMachinesRequired).toBe(0);
    expect(result.plan!.forced.map((f) => f.portKey)).toContain("a|o|output:item:S");
    expect(mayIdleTrade(result.plan, "c")).toBe(true);
    expect(idleTradeOf(result.plan, "c", new Set(["item:S"]))).toEqual({
      output: "item:Q",
      input: "item:S",
      makerId: "a",
    });
  });

  it("says nothing for the input it does not take, or for a card the plan runs", () => {
    const idle = solve(idleChain()).plan;
    expect(idleTradeOf(idle, "c", new Set(["item:V"]))).toBeUndefined();
    const running = solve(runningChain());
    expect(running.nodes.c?.theoreticalMachinesRequired).toBe(1);
    expect(idleTradeOf(running.plan, "c", new Set(["item:S"]))).toBeUndefined();
  });
});
