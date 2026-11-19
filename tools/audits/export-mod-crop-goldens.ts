/**
 * Crop goldens for the core's crop model (jvm/core/core-machines, CropsNhGoldensTest): this website's own CropsNH
 * functions run over every Crop Farm recipe of a dataset, under both harvesters, across every option of every setting
 * the card offers (sampled where there are many) and a few crop counts, at the pack's IC2 crop yield of 1 and 4.
 *
 *   npx tsx tools/audits/export-mod-crop-goldens.ts --out <file.jsonl.gz> [--dataset <version id>]
 *
 * Reads public/datasets/gtnh (the manifest's latest stable version unless --dataset names one).
 *
 * FORMAT: gzip, UTF-8, one JSON object per line:
 *   { crop, stats (getCropsNhStats), handler, tiers (the card's settings), crops (crops planted), gameRates,
 *     expected: { setup, envH, nutrient, rate, ticks, rounds, speed, perMachine, machines, slots, used, eutPerCrop,
 *                 farmEut, managerEu, outputMultipliers (one per stats drop), durationMultiplier, draw } }
 * Numbers are JSON numbers, except non-finite ones, which are the strings "Infinity", "-Infinity" and "NaN".
 */
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";
import type { FactoryNode, MachineHandler, Recipe, RecipeOutput } from "../../src/lib/model/types";
import {
  applyMachineHandlerToRecipe,
  getRecipeMachineConfigTierControls,
  getRecipeMachineHandlers,
} from "../../src/lib/model/recipe-rules";
import {
  cropsNhCropsPerMachine,
  cropsNhEnvironmentFromTiers,
  cropsNhEutPerCrop,
  cropsNhFarmEut,
  cropsNhGrowthRate,
  cropsNhGrowthSpeedMultiplier,
  cropsNhHarvestRoundMultiplier,
  cropsNhHarvestTicks,
  cropsNhHarvesterEnvironment,
  cropsNhHarvesterFromTiers,
  cropsNhHarvesterMachineCount,
  cropsNhIsHandPicked,
  cropsNhManagerEuPerHarvest,
  cropsNhNutrientScore,
  cropsNhUnitSlotsUsed,
  cropsNhUpgradeSlots,
  getCropsNhStats,
} from "../../src/lib/model/passive-production";
import {
  getMachineDurationMultiplier,
  getMachineOutputMultiplier,
} from "../../src/lib/solver/machine-effects";
import { getGameRates, setActiveGameRates } from "../../src/lib/model/game-rates";
import { getDatasetRecipe } from "../../src/lib/server/dataset-query";

/** Crops planted on a card: one, a stack, and a big field. */
const CROP_COUNTS = [1, 64, 1829];
/** The pack's IC2 crop yield: none, and the instance's. */
const YIELDS = [1, 4];

function arg(name: string): string | undefined {
  const at = process.argv.indexOf(`--${name}`);
  return at > 0 ? process.argv[at + 1] : undefined;
}

function finite(_key: string, value: unknown) {
  return typeof value === "number" && !Number.isFinite(value) ? String(value) : value;
}

/** What CropsNhGoldensTest reads, as the website computes a crop card. */
function expectedFor(
  recipe: Recipe,
  handlerId: string,
  tiers: Record<string, string>,
  crops: number,
) {
  const stats = getCropsNhStats(recipe)!;
  const node = {
    machineCount: crops,
    parallel: 1,
    overclockTier: "NONE",
    machineConfigTiers: tiers,
    machineHandlerId: handlerId,
  } as unknown as FactoryNode;
  const effective = applyMachineHandlerToRecipe(recipe, node);
  const setup = cropsNhHarvesterFromTiers(
    tiers,
    handlerId,
    stats.minSeedBedTier,
    stats.subSoil !== undefined,
  );
  const world = cropsNhEnvironmentFromTiers(tiers);
  const env = cropsNhHarvesterEnvironment(setup, world);
  const machines = cropsNhHarvesterMachineCount(setup, crops);
  const worldTicks = cropsNhHarvestTicks(stats, world);
  // The card's draw as the machine list bills it: the farms whole, the managers' EU a crop picked over the harvest.
  const draw = cropsNhIsHandPicked(setup)
    ? 0
    : setup.id === "crop-industrial-farm"
      ? cropsNhFarmEut(setup) * machines
      : Number.isFinite(worldTicks) && worldTicks > 0
        ? (cropsNhManagerEuPerHarvest(setup) * Math.max(0, crops)) / worldTicks
        : 0;
  const outputMultipliers = stats.drops.map((drop) => {
    const output: RecipeOutput = effective.outputs.find((entry) => entry.id === drop.id) ?? {
      kind: "item",
      id: drop.id,
      amount: drop.stackSize,
    };
    return getMachineOutputMultiplier(effective, node, output, "LV");
  });
  return {
    setup,
    envH: env,
    nutrient: cropsNhNutrientScore(env),
    rate: cropsNhGrowthRate(stats, env),
    ticks: cropsNhHarvestTicks(stats, env),
    rounds: cropsNhHarvestRoundMultiplier(setup),
    speed: cropsNhGrowthSpeedMultiplier(setup),
    perMachine: cropsNhCropsPerMachine(setup),
    machines,
    slots: cropsNhUpgradeSlots(setup.tierIndex),
    used: cropsNhUnitSlotsUsed(setup),
    eutPerCrop: cropsNhEutPerCrop(setup),
    farmEut: cropsNhFarmEut(setup),
    managerEu: cropsNhManagerEuPerHarvest(setup),
    outputMultipliers,
    durationMultiplier: getMachineDurationMultiplier(effective, node),
    draw,
  };
}

/** A setting with more options than this is sampled: its ends and evenly spaced keys between. */
const MAX_OPTIONS = 8;

function sample<T>(values: T[]): T[] {
  if (values.length <= MAX_OPTIONS) return values;
  const step = (values.length - 1) / (MAX_OPTIONS - 1);
  return [...new Set(Array.from({ length: MAX_OPTIONS }, (_, i) => values[Math.round(i * step)]))];
}

/**
 * One crop's cards: each harvester at its defaults, every (sampled) option of every setting one at a time, and every
 * setting at its first and at its last option together.
 */
function cardsFor(recipe: Recipe): Array<{ handler: string; tiers: Record<string, string> }> {
  const out: Array<{ handler: string; tiers: Record<string, string> }> = [];
  const handlers: MachineHandler[] = getRecipeMachineHandlers(recipe);
  for (const handler of handlers) {
    out.push({ handler: handler.id, tiers: {} });
    const node = { machineHandlerId: handler.id, machineConfigTiers: {} } as unknown as FactoryNode;
    const effective = applyMachineHandlerToRecipe(recipe, node);
    const controls = getRecipeMachineConfigTierControls(effective, node);
    for (const control of controls) {
      for (const option of sample(control.tiers))
        out.push({ handler: handler.id, tiers: { [control.id]: option.key } });
    }
    for (const end of [0, -1]) {
      out.push({
        handler: handler.id,
        tiers: Object.fromEntries(
          controls.map((control) => [control.id, control.tiers.at(end)!.key]),
        ),
      });
    }
  }
  return out;
}

async function main() {
  const out = arg("out");
  if (!out) throw new Error("Expected --out <file.jsonl.gz>");
  const root = path.join(process.cwd(), "public", "datasets", "gtnh");
  const manifest = JSON.parse(fs.readFileSync(path.join(root, "datasets.manifest.json"), "utf8"));
  const versionId: string = arg("dataset") ?? manifest.latestStableVersion;
  const index = JSON.parse(
    zlib
      .gunzipSync(fs.readFileSync(path.join(root, versionId, "recipe-index.json.gz")))
      .toString("utf8"),
  ) as { recipes: Array<{ id: string; kind: string; machineType?: string }> };
  const ids = index.recipes
    .filter((entry) => entry.kind === "crop_produce" && entry.machineType === "Crop Farm")
    .map((entry) => entry.id)
    .sort();
  const lines: string[] = [];
  let crops = 0;
  for (const id of ids) {
    const recipe = await getDatasetRecipe(versionId, id);
    const stats = recipe && getCropsNhStats(recipe);
    if (!recipe || !stats) continue;
    crops++;
    const crop = String(
      (recipe.metadata as { cropsNh?: { id?: unknown } } | undefined)?.cropsNh?.id ?? id,
    );
    for (const ic2CropsYield of YIELDS) {
      setActiveGameRates({ ic2CropsYield });
      const gameRates = getGameRates();
      for (const card of cardsFor(recipe)) {
        // The yield reaches only the Crop Manager's rounds: the one-setting sweep runs at no yield alone.
        if (ic2CropsYield !== 1 && Object.keys(card.tiers).length === 1) continue;
        // Every count on the defaults; one count on each setting, so the file stays small.
        const counts = Object.keys(card.tiers).length === 0 ? CROP_COUNTS : [CROP_COUNTS[1]];
        for (const count of counts) {
          lines.push(
            JSON.stringify(
              {
                crop,
                // A crop with no rule of these says so outright, as the Java reader expects.
                stats: {
                  ...stats,
                  machineOnly: stats.machineOnly ?? false,
                  minSeedBedTier: stats.minSeedBedTier ?? -1,
                  subSoil: stats.subSoil ?? null,
                },
                handler: card.handler,
                tiers: card.tiers,
                crops: count,
                gameRates,
                expected: expectedFor(recipe, card.handler, card.tiers, count),
              },
              finite,
            ),
          );
        }
      }
    }
  }
  setActiveGameRates(undefined);
  fs.writeFileSync(out, zlib.gzipSync(`${lines.join("\n")}\n`));
  console.log(`${lines.length} cases over ${crops} crops written to ${out}`);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
