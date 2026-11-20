import { mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { conformancePath, listConformance, readConformanceText } from "@/test-support/conformance";
import { readViewCases } from "@/test-support/view-corpus";
import {
  specialPartsJson,
  viewFiles,
  websiteSpecialParts,
  type SpecialCaseInput,
} from "./special-conformance";

/**
 * conformance/view/special/ holds what a special card shows beyond its recipe card, for its facts and its kind's, as the
 * core's input records and views. The website's own rules must give each stored view from its stored input (the core's
 * SpecialViews checks the same file from Java), and the file must be what the code makes now. Regenerate with
 * `GTNH_WRITE_VIEW=1 npx vitest run src/components/flow/clean/special/special-conformance.test.ts`, then
 * `node tools/conformance/manifest.mjs --write`.
 */
const made = viewFiles();

if (process.env.GTNH_WRITE_VIEW === "1") {
  for (const [name, text] of Object.entries(made)) {
    const file = path.join(conformancePath("view"), name);
    mkdirSync(path.dirname(file), { recursive: true });
    writeFileSync(file, text);
  }
}

describe("the special cards' conformance files", () => {
  it("are what the code makes now", () => {
    expect(listConformance("view", "special")).toEqual(
      Object.keys(made)
        .map((name) => name.slice("special/".length))
        .sort(),
    );
    for (const [name, text] of Object.entries(made)) {
      expect(readConformanceText("view", name) === text, `${name} differs: regenerate it`).toBe(true);
    }
  });
});

describe("the website's special card rules over the shared cases", () => {
  const { measure, cases } = readViewCases<SpecialCaseInput, unknown>("special", "cases");
  it("are measured six px a character", () => expect(measure).toBe("six-px"));
  it("has the holder cases", () => expect(cases.length).toBeGreaterThanOrEqual(25));
  it.each(cases.map((c) => [c.name, c] as const))("%s", (_name, c) => {
    expect(specialPartsJson(websiteSpecialParts(c.input))).toEqual(c.view);
  });
});
