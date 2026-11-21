/**
 * The website solver cases answer as they were recorded (`website-cases.ts`): the same file the Java kernel's
 * `WebsiteCasesTest` reads, so the compiled core and the kernel are held to one answer per case, compared by the golden
 * rules (`compare.ts`, 1e-6). Rewrite with `node tools/conformance/solver/generate.mjs` after a change to the core
 * that is meant to move an answer.
 */
import { beforeAll, describe, expect, it } from "vitest";

import { CORE_LOAD_MS, coreCaseAnswer, useCoreSolver } from "@/test-support/core-solve";
import { differences } from "./compare";
import { parseSolveInput } from "./load";
import { loadWebsiteCases } from "./website-cases";

const cases = loadWebsiteCases();

describe("website solver cases", () => {
  it("are there", () => {
    expect(cases.length).toBeGreaterThan(0);
  });

  describe("on the core", () => {
    beforeAll(() => useCoreSolver(), CORE_LOAD_MS);

    it.each(cases.map((c) => [c.name, c] as const))(
      "%s",
      (_name, c) => {
        const diffs = differences(c.answer, coreCaseAnswer(parseSolveInput(c.input)));
        expect(diffs, diffs.slice(0, 20).join("\n")).toEqual([]);
      },
      120_000,
    );
  });
});
