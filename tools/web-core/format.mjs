// The number formatter of the compiled core against the website's own answers, for one loaded target.
// conformance/format/values.json lists the inputs of every row; website-out.json is what the website's TypeScript
// formatters return for them (written by src/test-support/format-conformance.test.ts). The JVM runs the same two files
// (FormatGoldenTest), so the website, the JVM and both web targets spell every figure the same.
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = fileURLToPath(new URL("../../", import.meta.url));
const read = (name) => JSON.parse(readFileSync(path.join(ROOT, "conformance/format", name), "utf8"));

/** Rows the website formats by hand and the core has no formatter for yet; the golden still pins the website's. */
const WEBSITE_ONLY = new Set(["percent.whole", "percent.supply", "times"]);

/** The number a value of values.json stands for ("NaN", "Infinity" and "-Infinity" are spelled as text there). */
function num(v) {
  return typeof v === "string" ? Number(v) : v;
}

/** What the compiled core answers for one input of one row, or throws for a row it has no formatter for. */
export function answer(f, row, input) {
  if (row.id.startsWith("rate.") && row.unit) return f.formatSlotRate(num(input), row.unit, row.kind);
  switch (row.id) {
    case "compact":
    case "compact.recipeOverlay":
    case "compact.edges":
      return f.formatCompact(num(input));
    case "brief":
    case "stable":
      return f.formatCompactStable(num(input));
    case "power":
    case "power.edges":
      return f.formatPowerValue(num(input), false);
    case "power.stable":
      return f.formatPowerValue(num(input), true);
    case "rate.plain":
    case "rate.plain.d0":
    case "rate.plain.d3":
      return f.formatRate(num(input), row.digits);
    case "thousands.text":
      return f.formatThousands(input);
    case "thousands.number":
      return f.formatThousandsOfNumber(num(input));
    case "trim":
      return f.formatTrimZeros(input);
    case "machines":
    case "machines.edges":
    case "machines.recipeOverlay":
      return f.formatSolvedMachines(num(input));
    case "machines.list":
    case "machines.list.edges":
      return f.formatMachineListCount(num(input));
    case "percent.share":
    case "percent.share.edges":
      return f.formatRatioShare(num(input));
    case "euEach": {
      const r = f.formatEnergyPerUnit(num(input[0]), num(input[1]));
      return Number.isNaN(r) ? "none" : String(r);
    }
    case "euEach.suffix":
      return f.formatEnergyPerUnitSuffix(input);
    default:
      throw new Error(`values.json has a row the core has no formatter for: ${row.id}`);
  }
}

/** Run every row of the golden; the groups are "format/<row id>". */
export function runFormat(api, tally) {
  const f = api.FormatExports;
  if (!f) {
    tally.check("format/exports-class", false, () => "the build has no FormatExports");
    return;
  }
  const values = read("values.json");
  const golden = read("website-out.json").rows;
  for (const row of values.rows) {
    if (WEBSITE_ONLY.has(row.id)) continue;
    const group = `format/${row.id}`;
    const inputs = row.inputs.flatMap((g) => values.groups[g]);
    const want = golden[row.id];
    tally.check(group, want !== undefined && want.length === inputs.length, () => `${row.id}: answers and inputs do not line up`);
    inputs.forEach((input, i) => {
      let got;
      try {
        got = answer(f, row, input);
      } catch (e) {
        got = `throws ${e?.message ?? e}`;
      }
      tally.check(group, got === want?.[i], () => `${JSON.stringify(input)}: ${got} (website ${want?.[i]})`);
    });
  }
}
