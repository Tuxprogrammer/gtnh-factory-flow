/**
 * An Up to pin no bigger than the card needs never fails the plan. `__fixtures__/up-to/oil-line-up-to-one.json` is a
 * shared oil line whose saltwater electrolyzer needs 0.3 machines: under Up to 1, 0.5, 0.31 and 2 it solves at the 0.3
 * it needs (the website's own engine on HiGHS once failed it under 1, 0.5 and 0.31: held values read off its rounded
 * points left least flow no point). On the compiled core, the path every plan solves on.
 */
import { readFileSync } from "node:fs";
import path from "node:path";

import { beforeAll, describe, expect, it } from "vitest";

import { CORE_LOAD_MS, coreSolve, useCoreSolver } from "@/test-support/core-solve";
import type { SolveInput } from "./types";

const ELECTROLYZER = "node-2964b946-4f03-45bc-b142-ee4b5da3a671";

const oilLine = (capacity: number): SolveInput => {
  const plan = JSON.parse(
    readFileSync(path.join(__dirname, "__fixtures__", "up-to", "oil-line-up-to-one.json"), "utf8"),
  ) as SolveInput;
  return {
    ...plan,
    pools: plan.pools.map((p) => (p.machines.includes(ELECTROLYZER) ? { ...p, capacity } : p)),
  };
};

describe("an Up to pin no bigger than it needs never fails the plan", () => {
  describe("on the core", () => {
    beforeAll(() => useCoreSolver(), CORE_LOAD_MS);

    it.each([1, 0.5, 0.31, 2])("Up to %s solves at the 0.3 machines it needs", (capacity) => {
      const answer = coreSolve(oilLine(capacity));
      expect(answer.kind).toBe("solved");
      if (answer.kind !== "solved") return;
      expect(answer.solution.notes.map((n) => n.message)).not.toContain("BALANCE_FAILED");
      expect(answer.solution.machineCounts[ELECTROLYZER]).toBeCloseTo(0.3, 5);
    });
  });
});
