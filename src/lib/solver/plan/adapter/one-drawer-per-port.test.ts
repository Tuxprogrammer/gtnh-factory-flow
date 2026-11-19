import { describe, expect, it } from "vitest";
import type { FactoryStorage } from "../../../model/types";
import { calculateThroughput } from "../../throughput";
import { drawer, node, project, recipe, wire } from "./test-kit";

/**
 * One output port wired to two drawers (fork issue 82's plan has one). The port's flow goes to the first drawer by wire
 * id, as the read-out shows it; the second gets nothing, so a rule on it is checked against that nothing and reads
 * unmet, instead of passing on flow it never receives.
 */
describe("a port wired to two drawers feeds only the first", () => {
  const plan = (first: Partial<FactoryStorage>, second: Partial<FactoryStorage>) =>
    project({
      recipes: [recipe("a", [], [["y", 1]])],
      nodes: [node("m", "a", { solvePin: 1 })],
      storages: [drawer("d1", "y", first), drawer("d2", "y", second)],
      edges: [
        wire("e1", "m", "d1", "y", {}, { toDrawer: true }),
        wire("e2", "m", "d2", "y", {}, { toDrawer: true }),
      ],
    });
  const atLeastHalf: Partial<FactoryStorage> = { targetMode: "at-least", targetPerSecond: 0.5 };

  it("a rule on the second drawer reads unmet: it gets 0 of 0.5", () => {
    const result = calculateThroughput(plan({}, atLeastHalf), { generatedAt: "t" });
    expect(result.storages["d1"]!.producedPerSecond).toBeCloseTo(1, 6);
    expect(result.storages["d2"]!.producedPerSecond).toBe(0);
    expect(result.plan!.unmetDrawers).toEqual(["d2"]);
    expect(result.plan!.notes).toContainEqual({
      message: "DRAWER_UNMET",
      args: ["y", { message: "RULE_AT_LEAST", args: [] }, 0.5, 0],
    });
  });

  it("both drawers at least 0.5: the first gets the port's 1, the second nothing and is unmet", () => {
    const result = calculateThroughput(plan(atLeastHalf, atLeastHalf), { generatedAt: "t" });
    expect(result.storages["d1"]!.producedPerSecond).toBeCloseTo(1, 6);
    expect(result.storages["d2"]!.producedPerSecond).toBe(0);
    expect(result.plan!.unmetDrawers).toEqual(["d2"]);
  });

  it("with no rule on the second drawer nothing is unmet and the read-out is unchanged", () => {
    const result = calculateThroughput(plan({}, {}), { generatedAt: "t" });
    expect(result.storages["d1"]!.producedPerSecond).toBeCloseTo(1, 6);
    expect(result.storages["d2"]!.producedPerSecond).toBe(0);
    expect(result.plan!.unmetDrawers ?? []).toEqual([]);
  });
});
