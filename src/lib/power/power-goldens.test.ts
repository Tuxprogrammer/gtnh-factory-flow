import { gunzipSync } from "node:zlib";
import { describe, expect, it } from "vitest";
import { readConformanceBytes } from "@/test-support/conformance";
import { powerGoldenLines } from "./golden-records";

/**
 * conformance/power/power-goldens.jsonl.gz is what the website's power sources compute, and what the core power module's
 * PowerGoldensTest must reproduce. This test keeps the file honest: a change to a source, a setting, a label, the turbine
 * flow check or the card tier fails here until the file is regenerated
 * (npx tsx tools/audits/export-mod-power-goldens.ts --out conformance/power/power-goldens.jsonl.gz, then
 * node tools/conformance/manifest.mjs --write).
 */
describe("power goldens", () => {
  const file = gunzipSync(readConformanceBytes("power", "power-goldens.jsonl.gz"))
    .toString("utf8")
    .split("\n")
    .filter((line) => line.length > 0);
  const { lines } = powerGoldenLines();

  it("has as many records as the sources make now", () => {
    expect(file.length).toBe(lines.length);
  });

  it("matches every record the sources make now", () => {
    const wrong = lines.filter((line, at) => line !== file[at]);
    expect(wrong.slice(0, 3)).toEqual([]);
  });

  it("covers the boiler's wood fuels, the turbine check and the tier boundaries", () => {
    const text = file.join("\n");
    expect(text).toContain('"Oak Log"');
    expect(text).toContain('"minecraft:log2@32767"');
    expect(text).toContain('"kind":"turbineFlow"');
    expect(text).toContain('"kind":"cardTier"');
    // The plasma turbines' fuel list is the game's plasma fuel map: a heavy plasma it lacked is in.
    expect(text).toContain('"Uranium 232 Plasma"');
  });
});
