/**
 * Islands of two generated stress boards where the website's engine on HiGHS answers otherwise than the shared core
 * (`conformance/solver/highs-differs/cases.json.gz`; the JVM's `HighsDiffersTest` reads the same file). The core's
 * answer is the expected one: HiGHS reads its point to six digits (3.999996 for 4), splits an equal three-way share as
 * 6/6/0, leaves dust that turns an Up to reason, and finds one island infeasible that the core solves. Each case must
 * still differ on HiGHS, so the file never outlives its reason; the cases are the record for retiring HiGHS.
 */
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { gunzipSync } from "node:zlib";
import { beforeAll, describe, expect, it } from "vitest";

import { core, loadCore } from "@/lib/core/loader";
import { solveThroughCore } from "@/lib/core/solve-adopt";
import { conformancePath } from "@/test-support/conformance";

import { activeLpEngine, initLpEngine } from "../../lp-engine";
import { solvePlan } from "../solve-plan";
import { differences } from "./compare";
import { parseSolveInput } from "./load";
import { answerDigest, type WebsiteCase } from "./website-cases";

const cases = (
  JSON.parse(
    gunzipSync(readFileSync(conformancePath("solver", "highs-differs", "cases.json.gz"))).toString(
      "utf8",
    ),
  ) as { cases: WebsiteCase[] }
).cases;
const present = existsSync(path.join(process.cwd(), "public", "core", "manifest.json"));

describe("islands where HiGHS and the core differ", () => {
  beforeAll(async () => {
    await initLpEngine();
    if (present) await loadCore({ modules: ["solver"] });
  }, 120_000);

  it("are there", () => {
    expect(cases.length).toBeGreaterThanOrEqual(30);
    expect(
      cases.some((c) => "failed" in (answerDigest(solvePlan(parseSolveInput(c.input))) as object)),
      "an island HiGHS cannot solve",
    ).toBe(true);
  });

  it.each(cases.map((c) => [c.name, c] as const))(
    "%s",
    (_name, c) => {
      const input = parseSolveInput(c.input);
      if (present || process.env.GTNH_CORE_REQUIRED === "1") {
        const viaCore = answerDigest(solveThroughCore(core(), input)!);
        const d = differences(c.answer, viaCore);
        expect(d, d.slice(0, 20).join("\n")).toEqual([]);
      }
      expect(activeLpEngine()).toBe("highs");
      expect(
        differences(c.answer, answerDigest(solvePlan(input))),
        "HiGHS now agrees: drop the case",
      ).not.toEqual([]);
    },
    120_000,
  );
});
