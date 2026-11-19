/**
 * Machine goldens for the in-game mod's copy of the machine maths (jvm/core/core-machines, MachineGoldensTest): this
 * website's own functions run over real dataset recipes and a spread of cards, written in the format the JUnit reads.
 * A Java run against a fresh file lists every place the two sides compute a card differently.
 *
 *   npx tsx tools/audits/export-mod-machine-goldens.ts --out <file.jsonl.gz> [--dataset <version id>]
 *   npx tsx tools/audits/export-mod-machine-goldens.ts --out <file.jsonl.gz> --replay <goldens.jsonl.gz>
 *
 * Dataset mode reads public/datasets/gtnh (the manifest's latest stable version unless --dataset names one): per
 * recipe map the recipes at its extremes (least and most EU/t, shortest and longest, least energy, lowest and highest
 * tier, largest special value), every recipe of the fusion, Extreme Entity Crusher, Precise Assembler and Neutron
 * Activator maps, and per recipe every machine that runs it, across tiers, hatches and amps, coils and every option
 * of every control. Replay mode keeps another golden file's recipes and cards and recomputes only the answers, so a
 * difference is the code's alone.
 *
 * FORMAT: gzip, UTF-8, one JSON object per line:
 *   { case, group ("map:<map>" or a special machine), why (the tags that picked the recipe), variant (a label),
 *     recipe (the client recipe, on the recipe's first case only), recipeId, node (the card's fields),
 *     runtimeVariant (the game's ladder answered), expected: { the numbers MachineGoldensTest compares } }
 * Numbers are JSON numbers, except non-finite ones, which are the strings "Infinity", "-Infinity" and "NaN".
 */
import fs from "node:fs";
import path from "node:path";
import readline from "node:readline";
import zlib from "node:zlib";
import type { FactoryNode, MachineHandler, Recipe } from "../../src/lib/model/types";
import {
  applyMachineHandlerToRecipe,
  getRecipeCoilTierControl,
  getRecipeMachineConfigTierControls,
  getRecipeMachineHandlers,
} from "../../src/lib/model/recipe-rules";
import { applyRecipeInputOverrides } from "../../src/lib/model/recipe-input-overrides";
import {
  GT_VOLTAGE_TIERS,
  getRecipeMinimumVoltageTier,
  getVoltageTierIndex,
} from "../../src/lib/model/tiers";
import { getOverclockedRecipeStats } from "../../src/lib/solver/overclock";
import {
  getMachineDurationMultiplier,
  getMachineEutMultiplier,
  getMachineOutputMultiplier,
  getMachineParallelMultiplier,
  getMachineStructuralParallels,
} from "../../src/lib/solver/machine-effects";
import {
  getRuntimeCalculationOutputs,
  selectRuntimeCalculationVariant,
} from "../../src/lib/solver/runtime-calculation";
import {
  getEffectiveVoltageOrdinal,
  getNodePowerAmps,
  getNodeRunTier,
  isMultiblockRecipe,
} from "../../src/lib/solver/power";
import { getHeatDiscountMultiplier } from "../../src/lib/solver/heat";
import {
  getNodePowerReport,
  getNodeSteamReport,
  hasPowerReport,
} from "../../src/lib/solver/power-report";
import { getDatasetRecipe } from "../../src/lib/server/dataset-query";

type Tier = (typeof GT_VOLTAGE_TIERS)[number]["tier"];
type Node = Partial<FactoryNode> & {
  machineCount: number;
  parallel: number;
  overclockTier: string;
};
interface Case {
  group: string;
  why: string[];
  variant: string;
  recipe: Recipe;
  node: Node;
}

const TIERS = GT_VOLTAGE_TIERS.map((entry) => entry.tier);
const voltage = (tier: number) => GT_VOLTAGE_TIERS[tier].maxEuT;
const tierAt = (index: number): Tier => TIERS[Math.max(0, Math.min(TIERS.length - 1, index))];
const EV = TIERS.indexOf("EV");
const IV = TIERS.indexOf("IV");
const UV = TIERS.indexOf("UV");
const AMPS = [1, 2, 4, 16];
/** Maps where every recipe is a case, under a group of their own (the old file's groups). */
const SPECIAL_GROUPS: Record<string, string> = {
  "Fusion Reactor": "fusion",
  "Extreme Entity Crusher": "eec",
  "Precise Assembler": "precise-assembler",
  "Neutron Activator": "neutron-activator",
};
/** Maps also checked as a group of their own, on top of their map's extremes (they are many recipes). */
const ALSO_GROUPS: Record<string, string> = {
  "Bacterial Vat": "vat",
  "Tree Growth Simulator": "tgs",
};
/** A numeric control with more options than this is sampled: its ends and evenly spaced keys between. */
const MAX_OPTIONS = 8;

function arg(name: string): string | undefined {
  const at = process.argv.indexOf(`--${name}`);
  return at > 0 ? process.argv[at + 1] : undefined;
}

/** What MachineGoldensTest reads, computed as the website computes a card. */
function expectedFor(recipe: Recipe, node: Node) {
  const card = node as FactoryNode;
  const stats = getOverclockedRecipeStats(recipe, card);
  const effective = applyMachineHandlerToRecipe(recipe, card);
  const runtime = selectRuntimeCalculationVariant(effective, card);
  const multipliers = effective.outputs.map((output) =>
    getMachineOutputMultiplier(effective, card, output, stats.tier),
  );
  const runtimeOutputs = getRuntimeCalculationOutputs(effective, card);
  const runTier = getNodeRunTier(effective, card);
  const voltageOrdinal = getEffectiveVoltageOrdinal(effective, card, runTier);
  const expected: Record<string, unknown> = {
    tier: stats.tier,
    minimumTier: stats.minimumTier,
    overclockSteps: stats.overclockSteps,
    perfectOverclockSteps: stats.perfectOverclockSteps,
    perfectSpeedFactor: stats.perfectSpeedFactor,
    perfectEuFactor: stats.perfectEuFactor,
    durationTicks: stats.durationTicks,
    eut: stats.eut,
    parallels: runtime?.parallel ?? getMachineParallelMultiplier(effective, card),
    structuralParallels: getMachineStructuralParallels(effective, card),
    durationMultiplier: getMachineDurationMultiplier(effective, card),
    eutMultiplier: getMachineEutMultiplier(effective, card),
    outputMultipliers: multipliers,
    chances: effective.outputs.map((output) => output.chance ?? 1),
    ...(runtimeOutputs
      ? {
          runtimeOutputs: runtimeOutputs.map(({ kind, id, amount, chance }) => ({
            kind,
            id,
            amount,
            chance,
          })),
        }
      : {}),
    outputAmounts: runtimeOutputs
      ? runtimeOutputs.map((output) => output.amount)
      : effective.outputs.map((output, index) => output.amount * multipliers[index]),
    inputAmounts: applyRecipeInputOverrides(recipe, card).inputs.map((input) => ({
      amount: input.amount,
      consumed: input.consumed !== false,
    })),
    machineType: effective.machineType,
    multiblock: isMultiblockRecipe(effective),
    runTier,
    amps: getNodePowerAmps(effective, card),
    voltageOrdinal,
    heatDiscount: getHeatDiscountMultiplier(effective, card, runTier, voltageOrdinal),
  };
  if (hasPowerReport(recipe, card)) {
    const report = getNodePowerReport(recipe, card);
    expected.powerState = report.state;
    if (report.recipeGateReason !== undefined) expected.recipeGateReason = report.recipeGateReason;
    expected.poolEuT = report.poolEuT;
    expected.singleDrawEuT = report.singleDrawEuT;
  }
  const steam = getNodeSteamReport(recipe, card);
  if (steam) {
    expected.steam = {
      perTick: steam.drawSteamPerTick,
      singlePerTick: steam.singleDrawSteamPerTick,
      parallels: steam.parallels,
      multiblock: steam.isMultiblock,
      highPressure: steam.highPressure,
    };
  }
  const coil = getRecipeCoilTierControl(effective, card);
  expected.controls = [
    ...getRecipeMachineConfigTierControls(effective, card),
    ...(coil ? [{ ...coil, coil: true }] : []),
  ].map((control) => ({
    id: control.id,
    label: control.label,
    minimumKey: control.minimum.key,
    currentKey: control.current.key,
    minimumIndex: control.minimumIndex,
    tiers: control.tiers.map((option) => option.key),
    ...(control.numeric ? { numeric: control.numeric } : {}),
    ...("coil" in control ? { coil: true } : {}),
  }));
  return { expected, runtimeVariant: runtime !== undefined };
}

function sample<T>(values: T[]): T[] {
  if (values.length <= MAX_OPTIONS) return values;
  const step = (values.length - 1) / (MAX_OPTIONS - 1);
  return [...new Set(Array.from({ length: MAX_OPTIONS }, (_, i) => values[Math.round(i * step)]))];
}

/** One recipe's cards: every machine that runs it, at the tiers, hatches and settings a player can pick. */
function casesFor(recipe: Recipe, group: string, why: string[]): Case[] {
  const handlers: (MachineHandler | undefined)[] = getRecipeMachineHandlers(recipe);
  if (handlers.length === 0) handlers.push(undefined);
  const out: Case[] = [];
  handlers.forEach((handler, index) => {
    const base: Node = { machineCount: 1, parallel: 1, overclockTier: "LV" };
    const handlerId = handler && index > 0 ? { machineHandlerId: handler.id } : {};
    const label = handler?.label ?? recipe.machineType;
    const add = (variant: string, node: Omit<Node, "machineCount" | "parallel">) =>
      out.push({
        group,
        why,
        variant: `${label}: ${variant}`,
        recipe,
        node: { ...base, ...node, ...handlerId },
      });
    const effective = applyMachineHandlerToRecipe(recipe, { ...base, ...handlerId } as FactoryNode);
    const low = getVoltageTierIndex(getRecipeMinimumVoltageTier(effective));
    const settings = (prefix: string, extra: Omit<Node, "machineCount" | "parallel">) => {
      const card = { ...base, ...extra, ...handlerId } as FactoryNode;
      const coil = getRecipeCoilTierControl(effective, card);
      for (const option of coil?.tiers ?? [])
        add(`${prefix} coil ${option.key}`, { ...extra, coilTier: option.key });
      for (const control of getRecipeMachineConfigTierControls(effective, card)) {
        for (const option of sample(control.tiers)) {
          add(`${prefix} ${control.id}=${option.key}`, {
            ...extra,
            machineConfigTiers: { [control.id]: option.key },
          });
        }
      }
    };
    if (!isMultiblockRecipe(effective)) {
      const available = effective.availableTiers
        ?.map((tier) => TIERS.indexOf(tier as Tier))
        .filter((i) => i >= 0);
      const high = available?.length ? Math.max(...available) : TIERS.length - 1;
      for (let tier = low; tier <= high; tier++) {
        if (!available?.length || available.includes(tier))
          add(`tier ${TIERS[tier]}`, { overclockTier: TIERS[tier] });
      }
      if (low > 0) add(`tier ${TIERS[low - 1]} (below minimum)`, { overclockTier: TIERS[low - 1] });
      settings(`tier ${TIERS[low]}`, { overclockTier: TIERS[low] });
      return;
    }
    const hatch = (tier: number, amps: number) => ({
      overclockTier: tierAt(tier),
      hatchVoltageTier: tierAt(tier),
      hatchAmps: amps,
      powerInputMode: "amps" as const,
      powerEuT: voltage(tier) * amps,
    });
    const high = Math.min(TIERS.length - 1, Math.max(UV, low + 1));
    for (let tier = low; tier <= high; tier++)
      for (const amps of AMPS) add(`hatch ${TIERS[tier]} ${amps}A`, hatch(tier, amps));
    if (low > 0) {
      for (const amps of [1, 16])
        add(`hatch ${TIERS[low - 1]} ${amps}A (below minimum)`, hatch(low - 1, amps));
    }
    const laser = Math.max(IV, low);
    add(`laser256 ${tierAt(laser)}`, { ...hatch(laser, 256), energyHatchType: "laser256" });
    add(`eut mode ${tierAt(low)} 5A`, { ...hatch(low, 5), powerInputMode: "eut" });
    add(`legacy ${tierAt(low)}`, { overclockTier: tierAt(low) });
    add(`legacy ${tierAt(low)} 4 hatches`, { overclockTier: tierAt(low), energyHatches: 4 });
    add(`legacy ${tierAt(Math.max(EV, low))} amp64`, {
      overclockTier: tierAt(Math.max(EV, low)),
      energyHatchType: "amp64",
    });
    add(`legacy ${tierAt(low)} budget 3V`, {
      overclockTier: tierAt(low),
      powerEuT: voltage(low) * 3,
    });
    const at = Math.min(high, Math.max(EV, low + 1));
    settings(`${tierAt(at)} 2A`, hatch(at, 2));
  });
  return out;
}

interface IndexEntry {
  id: string;
  kind: string;
  machineType?: string;
  recipeMap?: string;
  minimumTier?: string;
  durationTicks: number;
  eut: number;
  specialValue?: number;
}

/** The recipes a map is checked on: the ones at its extremes, each tagged with why it was picked. */
function extremes(entries: IndexEntry[]): Map<string, string[]> {
  const picked = new Map<string, string[]>();
  const pick = (tag: string, score: (entry: IndexEntry) => number | undefined, lowest: boolean) => {
    let best: IndexEntry | undefined;
    let bestScore = 0;
    for (const entry of entries) {
      const value = score(entry);
      if (value === undefined || !Number.isFinite(value)) continue;
      if (!best || (lowest ? value < bestScore : value > bestScore)) {
        best = entry;
        bestScore = value;
      }
    }
    if (best) picked.set(best.id, [...(picked.get(best.id) ?? []), tag]);
  };
  const tier = (entry: IndexEntry) =>
    getVoltageTierIndex(
      getRecipeMinimumVoltageTier({ eut: entry.eut, minimumTier: entry.minimumTier ?? "" }),
    );
  pick("minEut", (entry) => Math.abs(entry.eut), true);
  pick("maxEut", (entry) => Math.abs(entry.eut), false);
  pick("subTick", (entry) => entry.durationTicks, true);
  pick(
    "lowEnergy",
    (entry) => (entry.eut ? Math.abs(entry.eut) * entry.durationTicks : undefined),
    true,
  );
  pick("maxSpecial", (entry) => (entry.specialValue ? entry.specialValue : undefined), false);
  pick("maxDuration", (entry) => entry.durationTicks, false);
  pick("tierTop", tier, false);
  pick("tierBottom", tier, true);
  return picked;
}

async function datasetCases(): Promise<Case[]> {
  const root = path.join(process.cwd(), "public", "datasets", "gtnh");
  const manifest = JSON.parse(fs.readFileSync(path.join(root, "datasets.manifest.json"), "utf8"));
  const versionId: string = arg("dataset") ?? manifest.latestStableVersion;
  const version = manifest.versions.find((entry: { id: string }) => entry.id === versionId);
  if (!version) throw new Error(`No dataset ${versionId} in ${root}`);
  // The pack's rate-mod factors, as the store sets them for this dataset (not present before the record existed).
  const rates = await import("../../src/lib/model/game-rates").catch(() => undefined);
  rates?.setActiveGameRates(version.gameRates);
  const index = JSON.parse(
    zlib
      .gunzipSync(fs.readFileSync(path.join(root, versionId, "recipe-index.json.gz")))
      .toString("utf8"),
  ) as { recipes: IndexEntry[] };
  const byMap = new Map<string, IndexEntry[]>();
  for (const entry of index.recipes) {
    const map = entry.recipeMap ?? entry.machineType;
    if (entry.kind !== "gregtech_machine" || !map) continue;
    byMap.set(map, [...(byMap.get(map) ?? []), entry]);
  }
  const cases: Case[] = [];
  for (const [map, entries] of [...byMap].sort(([a], [b]) => a.localeCompare(b))) {
    const special = SPECIAL_GROUPS[map];
    const picked = extremes(entries);
    const lists: [string, Map<string, string[]>][] = special
      ? [
          [
            special,
            new Map(entries.map((entry) => [entry.id, [...(picked.get(entry.id) ?? []), "all"]])),
          ],
        ]
      : [
          [`map:${map}`, picked],
          ...(ALSO_GROUPS[map]
            ? ([[ALSO_GROUPS[map], picked]] as [string, Map<string, string[]>][])
            : []),
        ];
    for (const [group, ids] of lists) {
      for (const [id, why] of ids) {
        const recipe = await getDatasetRecipe(versionId, id);
        if (recipe) cases.push(...casesFor(recipe, group, why));
      }
    }
  }
  return cases;
}

async function replayCases(file: string): Promise<Case[]> {
  const recipes = new Map<string, Recipe>();
  const cases: Case[] = [];
  const lines = readline.createInterface({
    input: fs.createReadStream(file).pipe(zlib.createGunzip()),
  });
  for await (const line of lines) {
    if (!line) continue;
    const old = JSON.parse(line);
    if (old.recipe) recipes.set(old.recipeId, old.recipe);
    cases.push({
      group: old.group,
      why: old.why,
      variant: old.variant,
      recipe: recipes.get(old.recipeId)!,
      node: old.node,
    });
  }
  return cases;
}

function finite(_key: string, value: unknown) {
  return typeof value === "number" && !Number.isFinite(value) ? String(value) : value;
}

async function main() {
  const out = arg("out");
  if (!out) throw new Error("Expected --out <file.jsonl.gz>");
  const replay = arg("replay");
  const cases = replay ? await replayCases(replay) : await datasetCases();
  const seen = new Set<string>();
  const lines: string[] = [];
  const failed: string[] = [];
  cases.forEach((entry, index) => {
    try {
      const { expected, runtimeVariant } = expectedFor(entry.recipe, entry.node);
      const first = !seen.has(entry.recipe.id);
      seen.add(entry.recipe.id);
      const line = {
        case: index,
        group: entry.group,
        why: entry.why,
        variant: entry.variant,
        ...(first ? { recipe: entry.recipe } : {}),
        recipeId: entry.recipe.id,
        node: entry.node,
        runtimeVariant,
        expected,
      };
      lines.push(JSON.stringify(line, finite));
    } catch (error) {
      failed.push(`${entry.variant}: ${String(error)}`);
    }
  });
  fs.writeFileSync(out, zlib.gzipSync(`${lines.join("\n")}\n`));
  console.log(
    `${lines.length} cases over ${seen.size} recipes written to ${out}; ${failed.length} threw`,
  );
  for (const message of failed.slice(0, 20)) console.log(`  ${message}`);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
