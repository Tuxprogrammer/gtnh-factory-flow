import { existsSync } from "node:fs";
import path from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";

import { differences } from "@/lib/solver/plan/__fixtures__/compare";
import { digest } from "@/lib/solver/plan/__fixtures__/digest";
import { caseNames, loadGolden, loadInput } from "@/lib/solver/plan/__fixtures__/load";
import { getSolveCore } from "@/lib/solver/plan/adapter/engine";
import { core, loadCore, resetCoreForTests } from "./loader";
import { solveEngine, solverTarget, solveThroughCore } from "./solve-adopt";

/**
 * An engine without WebAssembly GC loads the core's JavaScript build, and the plan solve runs on it: slower, the same
 * answers. The solver module pinned to the JavaScript target, the solve's seam picks the core, and every corpus case
 * answers its golden (the JVM's answer) through the path the solve Worker takes.
 */
const present = existsSync(path.join(process.cwd(), "public", "core", "manifest.json"));

describe("the plan solve on the core's JavaScript build", () => {
  it("has the core when CI requires it", () => {
    if (process.env.GTNH_CORE_REQUIRED === "1") expect(present).toBe(true);
  });

  describe.skipIf(!present)("loaded", () => {
    beforeAll(async () => {
      resetCoreForTests();
      await loadCore({ modules: ["solver"], target: "js" });
    }, 120_000);
    afterAll(() => resetCoreForTests());

    it("solves through the core", () => {
      expect(solverTarget()).toBe("js");
      expect(solveEngine()).toBe("core");
      const input = loadInput("benzene");
      expect(digest(getSolveCore()(input))).toEqual(digest(solveThroughCore(core(), input)!));
    });

    for (const name of caseNames()) {
      // platline-x10 is the mod's bench case: slow, and the web-core job runs it on both targets already.
      (name === "platline-x10" ? it.skip : it)(
        `answers the golden: ${name}`,
        () => {
          const answer = solveThroughCore(core(), loadInput(name));
          expect(answer).not.toBeNull();
          const golden = loadGolden(name);
          const got = digest(answer!);
          if (!("unneeded" in golden.solution)) delete got.solution.unneeded;
          const diffs = differences(golden, got);
          expect(diffs, diffs.slice(0, 20).join("\n")).toEqual([]);
        },
        120_000,
      );
    }
  });
});
