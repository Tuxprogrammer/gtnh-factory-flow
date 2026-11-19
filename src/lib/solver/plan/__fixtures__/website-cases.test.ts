/**
 * The website solver cases answer as they were recorded (`website-cases.ts`): the same file the Java kernel's
 * `WebsiteCasesTest` reads, so both engines are held to one answer per case, compared by the golden rules
 * (`compare.ts`, 1e-6). Rewrite with `node tools/conformance/solver/generate.mjs` after a change to either engine
 * that is meant to move an answer.
 */
import { beforeAll, describe, expect, it } from "vitest";

import { initLpEngine } from "../../lp-engine";
import { differences } from "./compare";
import { parseSolveInput } from "./load";
import { loadWebsiteCases, websiteCaseAnswer } from "./website-cases";

const cases = loadWebsiteCases();

describe("website solver cases", () => {
  beforeAll(async () => {
    await initLpEngine();
  });

  it("are there", () => {
    expect(cases.length).toBeGreaterThan(0);
  });

  it.each(cases.map((c) => [c.name, c] as const))(
    "%s",
    (_name, c) => {
      const diffs = differences(c.answer, websiteCaseAnswer(parseSolveInput(c.input)));
      expect(diffs, diffs.slice(0, 20).join("\n")).toEqual([]);
    },
    120_000,
  );
});
