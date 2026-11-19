import { describe, expect, it } from "vitest";
import { readConformanceText } from "@/test-support/conformance";
import { allViewFiles, VIEW_FAMILIES } from "@/test-support/view-families";
import { viewTreeFiles } from "@/test-support/view-corpus";

/**
 * The view golden corpus as an institution (`conformance/view/README.md`): the tree is exactly what the record
 * families make now, and every file is an envelope that names its record, its set and the text measure it was computed
 * with. Regenerate with `npx tsx tools/conformance/view/generate.ts`, then `node tools/conformance/manifest.mjs --write`.
 */
describe("the view corpus", () => {
  const made = allViewFiles();

  it("is what the record families make now, file for file", () => {
    expect(viewTreeFiles()).toEqual(Object.keys(made).sort());
    for (const [name, text] of Object.entries(made)) {
      expect(readConformanceText("view", ...name.split("/")) === text, `${name} differs: regenerate it`).toBe(true);
    }
  });

  it("keeps one file per record and set, in a directory of the record's name", () => {
    expect(VIEW_FAMILIES.length).toBeGreaterThan(0);
    for (const name of Object.keys(made)) expect(name, name).toMatch(/^[a-z]+\/[a-z0-9-]+\.json$/);
  });

  it.each(Object.entries(made))("%s is an envelope that names its record, set and measure", (name, text) => {
    const file = JSON.parse(text) as Record<string, unknown>;
    const [record, set] = [name.split("/")[0], name.split("/")[1]!.replace(/\.json$/, "")];
    expect(Object.keys(file).slice(0, 3)).toEqual(["record", "set", "measure"]);
    expect(file.record).toBe(record);
    expect(file.set).toBe(set);
    expect(["none", "six-px"]).toContain(file.measure);
    expect((file.cases === undefined) !== (file.vectors === undefined)).toBe(true);
  });

  it("has no null in a case: an optional is a missing key (a vector may name an absent argument as null)", () => {
    for (const [name, text] of Object.entries(made)) {
      if ("cases" in JSON.parse(text)) expect(text, name).not.toContain("null");
    }
  });
});
