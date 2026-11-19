import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { buildComparison, inputsOf, load } from "./compare.mjs";

const values = { groups: { a: [1, 2], b: [[8, 0.5]] }, rows: [] };

describe("the format comparison", () => {
  it("is what the three answer files make", () => {
    const have = readFileSync(new URL("../../../conformance/format/COMPARISON.md", import.meta.url), "utf8");
    expect(have).toBe(load());
  });

  it("lists a row's inputs group by group", () => {
    expect(inputsOf(values, { id: "x", inputs: ["a", "b"] })).toEqual([1, 2, [8, 0.5]]);
    expect(() => inputsOf(values, { id: "x", inputs: ["c"] })).toThrow(/no group c/);
  });

  it("shows only the inputs the two sides spell differently, and says so for a row the mod lacks", () => {
    const v = {
      groups: { a: [1, 2, 3] },
      rows: [
        { id: "r", what: "w", inputs: ["a"], web: "wf", mod: "Fmt.r" },
        { id: "s", what: "w", inputs: ["a"], web: "wf", mod: null },
      ],
    };
    const web = { rows: { r: ["1", "2", "3"], s: ["1", "2", "3"] } };
    const mods = { "mod-out.json": { rows: { r: ["1", "2.0", "3"] } }, "mod-recipe-overlay-out.json": { rows: {} } };
    const text = buildComparison(v, web, mods);
    expect(text).toContain("agree on 2 of 3 inputs");
    expect(text).toContain("| `2` | `2` | `2.0` |");
    expect(text).toContain("The mod has no formatter for this");
  });

  it("refuses an answer file that is short", () => {
    const v = { groups: { a: [1, 2] }, rows: [{ id: "r", what: "w", inputs: ["a"], web: "wf", mod: "Fmt.r" }] };
    const mods = { "mod-out.json": { rows: { r: ["1"] } }, "mod-recipe-overlay-out.json": { rows: {} } };
    expect(() => buildComparison(v, { rows: { r: ["1", "2"] } }, mods)).toThrow(/mod-out.json has no 2 answers/);
  });
});
