/**
 * Runs the plan solve over the whole corpus and compares each answer to its golden (spec 6.2): the compiled core, the
 * path the solve Worker takes (`src/test-support/core-solve.ts`).
 */
import { beforeAll, describe, expect, it } from "vitest";

import { CORE_LOAD_MS, coreSolve, useCoreSolver } from "@/test-support/core-solve";
import { differences } from "./compare";
import { digest } from "./digest";
import { caseNames, loadGolden, loadInput } from "./load";

describe("golden corpus", () => {
  describe("on the core", () => {
    beforeAll(() => useCoreSolver(), CORE_LOAD_MS);
    for (const name of caseNames()) {
      // platline-x10 is a bench case in the mod; it is slow, so it runs only when asked for.
      const run = name === "platline-x10" && !process.env.CORPUS_BENCH ? it.skip : it;
      run(
        name,
        () => {
          const golden = loadGolden(name);
          const got = digest(coreSolve(loadInput(name)));
          // the platline-x10 golden was recorded without the `unneeded` key (see README)
          if (!("unneeded" in golden.solution)) delete got.solution.unneeded;
          const diffs = differences(golden, got);
          expect(diffs, diffs.slice(0, 20).join("\n")).toEqual([]);
        },
        120_000,
      );
    }
  });
});
