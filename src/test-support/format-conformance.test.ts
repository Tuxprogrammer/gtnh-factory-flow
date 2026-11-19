import { writeFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { conformancePath, readConformanceJson, readConformanceText } from "./conformance";
import { websiteGolden, type FormatValues } from "./format-conformance";

/**
 * The website's number text for the shared list in conformance/format/values.json, pinned to website-out.json so a
 * change to any formatter shows up as a change to that file. The mod's answers are pinned the same way by core-view's
 * FmtConformanceTest; compare.mjs builds COMPARISON.md from both. Rewrite with GTNH_WRITE_GOLDEN=1.
 */
describe("website number text against the shared value list", () => {
  const values = readConformanceJson("format", "values.json") as FormatValues;

  it("answers as website-out.json says", () => {
    const text = websiteGolden(values);
    if (process.env.GTNH_WRITE_GOLDEN) writeFileSync(conformancePath("format", "website-out.json"), text);
    expect(text).toBe(readConformanceText("format", "website-out.json"));
  });

  it("gives every row one answer per input", () => {
    const golden = JSON.parse(readConformanceText("format", "website-out.json")) as { rows: Record<string, string[]> };
    for (const row of values.rows) {
      const n = row.inputs.reduce((sum, g) => sum + values.groups[g].length, 0);
      expect(golden.rows[row.id]?.length, row.id).toBe(n);
    }
  });
});
