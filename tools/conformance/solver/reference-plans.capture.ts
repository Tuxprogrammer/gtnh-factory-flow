/**
 * The reference plans, opened on the plan board the way a landing plan is (parsed, normalized, a Build or Solve plan
 * converted), so capture-setup.ts records the solve input the website's adapter builds for each. Run only by
 * capture.config.ts (tools/conformance/solver/generate.mjs). Each plan solves with the recipes it carries, not a dataset.
 */
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

import { beforeAll, describe, expect, it } from "vitest";

import { parseFactoryProjectJson } from "@/lib/import-export/factory-json";
import { initLpEngine } from "@/lib/solver/lp-engine";
import { calculatePlanBoardThroughput } from "@/lib/solver/plan/adapter/open";

const ROOT = join(__dirname, "../../..");

/** Every plan file the two suites keep: the conformance plans, the board fixtures, the mod's imported website plans. */
const DIRS = [
  "conformance/plans/website",
  "conformance/plans/mod",
  "conformance/plans/valid",
  "src/components/flow/__fixtures__",
  "jvm/mod/src/test/resources/factory-flow",
  "examples",
];

const plans = DIRS.flatMap((dir) =>
  readdirSync(join(ROOT, dir))
    .filter((f) => f.endsWith(".json"))
    .sort()
    .map((f) => `${dir}/${f}`),
);

describe("reference plans", () => {
  beforeAll(async () => {
    await initLpEngine();
  });
  it.each(plans)("%s", (file) => {
    let project;
    try {
      project = parseFactoryProjectJson(readFileSync(join(ROOT, file), "utf8"));
    } catch {
      // Not a plan this build reads (a slim fixture, a part of one): nothing to capture.
      return;
    }
    const result = calculatePlanBoardThroughput(project);
    expect(result).toBeDefined();
  });
});
