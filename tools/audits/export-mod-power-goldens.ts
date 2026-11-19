/**
 * Power goldens for the in-game mod's copy of the power sources (jvm/core/core-power): every source in this
 * website's registry, its settings, and its model at its defaults and at each setting moved alone through its
 * options (a select's every key, a toggle's both sides, a number's minimum, default, middle and maximum). A Java run
 * against the file lists every source, setting and number the two sides compute differently.
 *
 *   npx tsx tools/audits/export-mod-power-goldens.ts --out <file.jsonl.gz>
 *
 * FORMAT: gzip, UTF-8, one JSON object per line. A source's first line describes it:
 *   { source, name, group, unlock, settings: [{ id, type, label, defaultKey | defaultValue, min, max, step,
 *     defaultOn, options: [key], legacyKeys, enabledWhen }] }
 * and every line after it is one case:
 *   { source, case, settings: { id: stored string }, model: { euPerTick, inputs: [{ name, perSecond, unit }],
 *     outputs: [...], stats: [{ label, value }], warnings: [...] } }
 * Stored settings are strings as a plan keeps them (a number as JavaScript prints it, a toggle "true" or "false").
 * Non-finite numbers are the strings "Infinity", "-Infinity" and "NaN".
 */
import fs from "node:fs";
import zlib from "node:zlib";
import { POWER_SOURCES } from "../../src/lib/power/registry";
import {
  buildPowerSettingsReader,
  type PowerSetting,
  type PowerSourceDefinition,
} from "../../src/lib/power/types";

function arg(name: string): string | undefined {
  const at = process.argv.indexOf(`--${name}`);
  return at > 0 ? process.argv[at + 1] : undefined;
}

function describe(setting: PowerSetting) {
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

function model(source: PowerSourceDefinition, settings: Record<string, string>) {
  const result = source.compute(buildPowerSettingsReader(source, settings));
  const flow = (line: { name: string; perSecond: number; unit: string }) => ({
    name: line.name,
    perSecond: line.perSecond,
    unit: line.unit,
  });
  return {
    euPerTick: result.euPerTick,
    inputs: result.inputs.map(flow),
    outputs: result.outputs.map(flow),
    stats: result.stats.map((stat) => ({ label: stat.label, value: stat.value })),
    warnings: result.warnings ?? [],
  };
}

function finite(_key: string, value: unknown) {
  return typeof value === "number" && !Number.isFinite(value) ? String(value) : value;
}

function main() {
  const out = arg("out");
  if (!out) throw new Error("Expected --out <file.jsonl.gz>");
  const lines: string[] = [];
  let cases = 0;
  for (const source of POWER_SOURCES) {
    lines.push(
      JSON.stringify({
        source: source.id,
        name: source.name,
        group: source.group,
        unlock: source.unlock,
        settings: source.settings.map(describe),
      }),
    );
    const variants: Record<string, string>[] = [{}];
    for (const setting of source.settings) {
      for (const value of valuesOf(setting)) variants.push({ [setting.id]: value });
    }
    for (const settings of variants) {
      lines.push(
        JSON.stringify(
          { source: source.id, case: cases++, settings, model: model(source, settings) },
          finite,
        ),
      );
    }
  }
  fs.writeFileSync(out, zlib.gzipSync(`${lines.join("\n")}\n`));
  console.log(`${POWER_SOURCES.length} sources, ${cases} cases written to ${out}`);
}

main();
