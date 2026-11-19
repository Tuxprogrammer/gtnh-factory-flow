/**
 * What the website's formatters answer for the shared value list in `conformance/format/values.json`.
 * The vitest test checks the answers against `website-out.json`; `GTNH_WRITE_GOLDEN=1` rewrites that file.
 * `tools/conformance/format/compare.mjs` lines the answers up with the mod's.
 */
import { formatCompact, formatCompactStable, formatMachineListCount, formatPowerValue, formatRatioShare } from "@/lib/format/numbers";
import { energyPerUnit, energyPerUnitSuffix, setActiveRateUnit, type RateUnit } from "@/lib/format/rate-unit";
import { formatSlotRate, formatPct, formatTimes } from "@/components/flow/flow-explainers";
import { formatSupplyPercent } from "@/components/flow/edge-labels";
import { formatSolvedMachines } from "@/components/flow/clean/clean-model";

export interface FormatRow {
  id: string;
  inputs: string[];
  unit?: RateUnit;
  kind?: string;
}

export interface FormatValues {
  groups: Record<string, unknown[]>;
  rows: FormatRow[];
}

const SPECIAL: Record<string, number> = { NaN: Number.NaN, Infinity: Infinity, "-Infinity": -Infinity };

function toNumber(v: unknown): number {
  if (typeof v === "number") return v;
  if (typeof v === "string" && v in SPECIAL) return SPECIAL[v];
  throw new Error(`not a number: ${String(v)}`);
}

/** The inputs of a row, in order: its groups, one after another. */
export function rowInputs(values: FormatValues, row: FormatRow): unknown[] {
  return row.inputs.flatMap((g) => {
    const list = values.groups[g];
    if (!list) throw new Error(`row ${row.id} names no group ${g}`);
    return list;
  });
}

/** One row's answers, as the strings the board would draw. */
export function websiteRow(row: FormatRow, inputs: unknown[]): string[] {
  const num = (f: (n: number) => string) => inputs.map((v) => f(toNumber(v)));
  switch (row.id) {
    case "compact":
    case "compact.recipeOverlay":
      return num(formatCompact);
    case "brief":
      return num(formatCompactStable);
    case "power":
      return num((n) => formatPowerValue(n));
    case "machines":
    case "machines.recipeOverlay":
      return num(formatSolvedMachines);
    case "machines.list":
      return num(formatMachineListCount);
    case "percent.share":
      return num(formatRatioShare);
    case "percent.whole":
      return num(formatPct);
    case "percent.supply":
      return num(formatSupplyPercent);
    case "times":
      return num(formatTimes);
    case "euEach":
      return inputs.map((p) => {
        const [eu, perSecond] = p as [number, number];
        const r = energyPerUnit(eu, perSecond);
        return r === undefined ? "none" : String(r);
      });
    case "euEach.suffix":
      return inputs.map((k) => energyPerUnitSuffix(String(k)));
    default:
      if (row.id.startsWith("rate.")) {
        setActiveRateUnit(row.unit!);
        try {
          return num((n) => formatSlotRate(n, row.kind!));
        } finally {
          setActiveRateUnit("second");
        }
      }
      throw new Error(`no website formatter for row ${row.id}`);
  }
}

/** Every row's answers, as the golden file spells them: a header and one line per row. */
export function websiteGolden(values: FormatValues): string {
  const lines = values.rows.map((row) => `    ${JSON.stringify(row.id)}: ${JSON.stringify(websiteRow(row, rowInputs(values, row)))}`);
  return `{\n  "version": 1,\n  "rows": {\n${lines.join(",\n")}\n  }\n}\n`;
}
