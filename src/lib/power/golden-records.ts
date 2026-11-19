/**
 * The power conformance records: every source in the registry, its settings, its model at its defaults and at each
 * setting moved alone, and the two functions beside the sources that the in-game mod shares (the Amps dial's tier and
 * the turbine flow check). tools/audits/export-mod-power-goldens.ts writes them to conformance/power; the website's
 * power-goldens.test.ts and the core's PowerGoldensTest both read that one file.
 *
 * Record kinds, one JSON object per line:
 *   a source: { source, name, group, unlock, settings: [{ id, type, label, defaultKey | defaultValue, min, max, step,
 *     defaultOn, options: [key], optionLabels: [label], legacyKeys, enabledWhen }] }
 *   a case: { source, case, settings: { id: stored string }, model: { euPerTick, cardTier, inputs: [flow], outputs: [flow],
 *     stats: [{ label, value }], warnings: [...] } }, where a flow is { name, perSecond, unit, alternatives: [...] }
 *   a turbine check: { kind: "turbineFlow", source, settings, running, pin, turbines, may, check: null | {...} }
 *   a tier: { kind: "cardTier", euPerTick, tier }
 * Stored settings are strings as a plan keeps them. Non-finite numbers are the strings "Infinity", "-Infinity", "NaN".
 */
import type { Recipe } from "@/lib/model/types";
import { mayTurbineFlow, turbineFlowCheck, turbinesFor } from "./turbine-flow";
import { POWER_RECIPE_CATEGORY, powerCardTier } from "./power-recipe";
import { POWER_SOURCES } from "./registry";
import {
  buildPowerSettingsReader,
  type PowerFlowLine,
  type PowerSetting,
  type PowerSourceDefinition,
} from "./types";

function describeSetting(setting: PowerSetting) {
  const base = {
    id: setting.id,
    type: setting.type,
    label: setting.label,
    enabledWhen: setting.enabledWhen,
  };
  if (setting.type === "select") {
    return {
      ...base,
      defaultKey: setting.defaultKey,
      options: setting.options.map((option) => option.key),
      optionLabels: setting.options.map((option) => option.label),
      legacyKeys: setting.legacyKeys,
    };
  }
  if (setting.type === "number") {
    return {
      ...base,
      defaultValue: setting.defaultValue,
      min: setting.min,
      max: setting.max,
      step: setting.step,
    };
  }
  return { ...base, defaultOn: setting.defaultOn };
}

/** The stored values a setting is checked at, each moved alone from the defaults. */
function valuesOf(setting: PowerSetting): string[] {
  if (setting.type === "select") return setting.options.map((option) => option.key);
  if (setting.type === "toggle") return ["true", "false"];
  const middle =
    setting.min + Math.round((setting.max - setting.min) / 2 / setting.step) * setting.step;
  return [
    ...new Set(
      [setting.min, setting.defaultValue, middle, setting.max].map((value) => String(value)),
    ),
  ];
}

function variantsOf(source: PowerSourceDefinition): Record<string, string>[] {
  const variants: Record<string, string>[] = [{}];
  for (const setting of source.settings) {
    for (const value of valuesOf(setting)) variants.push({ [setting.id]: value });
  }
  return variants;
}

function powerRecipe(source: PowerSourceDefinition, euPerTick: number) {
  return {
    category: POWER_RECIPE_CATEGORY,
    power: { sourceId: source.id, euPerTick, stats: [] },
  } as unknown as Pick<Recipe, "power" | "category">;
}

function flowRecord(line: PowerFlowLine) {
  return {
    name: line.name,
    perSecond: line.perSecond,
    unit: line.unit,
    alternatives: line.alternatives ?? [],
  };
}

function modelRecord(source: PowerSourceDefinition, settings: Record<string, string>) {
  const result = source.compute(buildPowerSettingsReader(source, settings));
  return {
    euPerTick: result.euPerTick,
    cardTier: powerCardTier(powerRecipe(source, result.euPerTick)) ?? null,
    inputs: result.inputs.map(flowRecord),
    outputs: result.outputs.map(flowRecord),
    stats: result.stats.map((stat) => ({ label: stat.label, value: stat.value })),
    warnings: result.warnings ?? [],
  };
}

/** Cards that run part of a turbine, as [running, whole turbines]: the shapes the check tells apart. */
const TURBINE_SHARES: Array<[number, number | undefined]> = [
  [1, undefined],
  [0.575, undefined],
  [0.8, undefined],
  [2.4, undefined],
  [2, 3],
  [7.2, undefined],
  [0, undefined],
];

/** EU/t values at, just above and just below each voltage, plus the degenerate ones. */
const TIER_EU = [
  -5,
  0,
  0.5,
  1,
  7.99,
  8,
  8.01,
  32,
  33,
  128,
  129,
  512,
  513,
  2048,
  2049,
  8192,
  8193,
  32768,
  32769,
  131072,
  131073,
  524288,
  524289,
  2097152,
  2097153,
  8388608,
  8388609,
  33554432,
  33554433,
  134217728,
  134217729,
  536870912,
  536870913,
  2147483640,
  2147483647,
  2147483648,
  1e12,
  Infinity,
  NaN,
];

function turbineRecords(source: PowerSourceDefinition): unknown[] {
  if (!source.settings.some((setting) => setting.id === "flowMode")) return [];
  const records: unknown[] = [];
  for (const settings of variantsOf(source)) {
    for (const [running, pin] of TURBINE_SHARES) {
      const turbines = turbinesFor(running, pin);
      const recipe = powerRecipe(source, 0);
      records.push({
        kind: "turbineFlow",
        source: source.id,
        settings,
        running,
        pin: pin ?? null,
        turbines,
        may: mayTurbineFlow(recipe, running, turbines),
        check: turbineFlowCheck(recipe, settings, running, turbines) ?? null,
      });
    }
  }
  return records;
}

export interface PowerGoldens {
  lines: string[];
  sources: number;
  cases: number;
}

function finite(_key: string, value: unknown) {
  return typeof value === "number" && !Number.isFinite(value) ? String(value) : value;
}

/** Every record, in file order, as JSON lines. */
export function powerGoldenLines(): PowerGoldens {
  const lines: string[] = [];
  let cases = 0;
  for (const source of POWER_SOURCES) {
    lines.push(
      JSON.stringify({
        source: source.id,
        name: source.name,
        group: source.group,
        unlock: source.unlock,
        settings: source.settings.map(describeSetting),
      }),
    );
    for (const settings of variantsOf(source)) {
      lines.push(
        JSON.stringify(
          { source: source.id, case: cases++, settings, model: modelRecord(source, settings) },
          finite,
        ),
      );
    }
    for (const record of turbineRecords(source)) lines.push(JSON.stringify(record, finite));
  }
  const tierSource = POWER_SOURCES[0];
  for (const euPerTick of TIER_EU) {
    lines.push(
      JSON.stringify(
        {
          kind: "cardTier",
          euPerTick,
          tier: powerCardTier(powerRecipe(tierSource, euPerTick)) ?? null,
        },
        finite,
      ),
    );
  }
  return { lines, sources: POWER_SOURCES.length, cases };
}
