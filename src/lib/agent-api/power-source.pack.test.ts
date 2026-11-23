/**
 * Checks the steam and primitive rule (power-source.ts) against the pack dataset, so a new steam or brick machine
 * in a new pack fails here rather than slipping into an EV player's plan. It reads every recipe shard (about 11 s) and
 * runs wherever a dataset is installed (public/datasets/gtnh, or DATASET_DIR naming a version directory); without one
 * it is skipped.
 */
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";
import { describe, expect, it } from "vitest";
import { applyMachineHandlerToRecipe } from "@/lib/model/recipe-rules";
import type { Recipe } from "@/lib/model/types";
import { machinesOf } from "./format";
import { unpoweredKind } from "./power-source";

/** Machines whose every pack recipe reads 0 EU and that are neither steam nor primitive, by handler label. */
const ZERO_EU_NOT_UNPOWERED = new Set(
  [
    // Generators: their recipes are fuels, the EU is what they make.
    "acid generator", "corrosive energy releaser", "superacid generator", "large neutralization engine",
    "combustion generator", "chemical energy releaser", "supercharging combustion generator", "large combustion engine",
    "universal chemical fuel engine", "naquadah reactor mk-i", "naquadah reactor mk-ii", "naquadah reactor mk-iii",
    "naquadah reactor mk-iv", "naquadah reactor mk-v", "large bronze boiler", "large steel boiler",
    "large titanium boiler", "large tungstensteel boiler", "novice magic energy converter",
    "adept magic energy converter", "master magic energy converter", "plasma generator mk-i", "plasma generator mk-ii",
    "plasma generator mk-iii", "plasma generator mk-iv", "pocket sun", "large plasma turbine",
    "xl turbo plasma turbine", "large naquadah reactor", "liquid fluoride thorium reactor", "nuclear fission",
    "solar tower", "thermal boiler", "extreme heat exchanger",
    // Electric machines whose recipes the dataset exports at 0 EU (the site bills GT's furnace and crafting numbers).
    "furnace", "electric furnace", "multi smelter", "auto workbench",
    // The Void Miner map shows each dimension's ore pool at 1 tick and 0 EU; the controllers' tooltips in the same
    // dataset read "Consumes 30,720 / 122,880 / 491,520 EU/t", so they are electric.
    "void miner i", "void miner ii", "void miner iii",
    // Machines with their own energy (neutrons, the Eye of Harmony's stars) or none: not steam-age machines.
    "neutron activator", "eye of harmony", "nanochip conversion complex", "algae pond", "ice cream machine",
    "tree growth simulator", "nasa workbench (missile)",
    // Not machines: crafting, magic, farming, world generation, storage.
    "crafting table", "cauldron", "bee produce", "crop farm", "ic2 crop", "ore vein", "small ore", "underground fluid",
    "tank", "thaumcraft", "thaumcraft arcane crafting", "thaumcraft crucible", "thaumcraft essentia smelting",
    "thaumcraft infusion",
  ],
);

function datasetDir(): string | undefined {
  if (process.env.DATASET_DIR) {
    return process.env.DATASET_DIR;
  }
  const root = path.resolve("public/datasets/gtnh");
  if (!fs.existsSync(root)) {
    return undefined;
  }
  const versions = fs.readdirSync(root).filter((name) => fs.existsSync(path.join(root, name, "recipes-shards")));
  return versions.length ? path.join(root, versions.sort().at(-1)!) : undefined;
}

interface Seen {
  label: string;
  kind: string;
  recipes: number;
  allZero: boolean;
  unpowered?: string;
  /** A steam recipe whose card still bills EU. */
  billsEu: number;
}

describe.skipIf(!datasetDir())("steam and primitive machines in the pack dataset", () => {
  it("classifies every machine that matters, and steam cards bill no EU", () => {
    const dir = path.join(datasetDir()!, "recipes-shards");
    const seen = new Map<string, Seen>();
    for (const file of fs.readdirSync(dir).sort()) {
      const shard = JSON.parse(zlib.gunzipSync(fs.readFileSync(path.join(dir, file))).toString()) as { recipes: Recipe[] };
      for (const recipe of shard.recipes) {
        for (const handler of machinesOf(recipe)) {
          const entry = seen.get(handler.label.toLowerCase()) ?? {
            label: handler.label,
            kind: handler.kind ?? "single",
            recipes: 0,
            allZero: true,
            billsEu: 0,
          };
          entry.recipes += 1;
          entry.allZero &&= !(recipe.eut > 0);
          const kind = unpoweredKind(handler, recipe);
          entry.unpowered ??= kind;
          if (kind === "steam" && applyMachineHandlerToRecipe(recipe, { machineHandlerId: handler.id }).eut !== 0) {
            entry.billsEu += 1;
          }
          seen.set(handler.label.toLowerCase(), entry);
        }
      }
    }
    const steam = [...seen.values()].filter((entry) => entry.unpowered === "steam");
    const primitive = [...seen.values()].filter((entry) => entry.unpowered === "primitive");
    console.warn(
      `${seen.size} machines; steam ${steam.length}: ${steam.map((entry) => entry.label).join(", ")}; ` +
        `primitive ${primitive.length}: ${primitive.map((entry) => `${entry.label} (${entry.recipes})`).join(", ")}`,
    );
    // Every machine with "steam" or "high pressure" in its name is on the steam line (boilers and turbines make steam
    // or burn it for EU and carry neither phrase in the pack).
    const named = [...seen.values()].filter((entry) => /steam|high pressure/i.test(entry.label));
    expect(named.filter((entry) => entry.unpowered !== "steam").map((entry) => entry.label)).toEqual([]);
    expect(steam.filter((entry) => entry.billsEu > 0).map((entry) => entry.label)).toEqual([]);
    // Every machine that never draws EU is steam, primitive, or listed as neither.
    const unknown = [...seen.values()].filter(
      (entry) => entry.allZero && !entry.unpowered && !ZERO_EU_NOT_UNPOWERED.has(entry.label.toLowerCase()),
    );
    expect(unknown.map((entry) => entry.label)).toEqual([]);
    expect(primitive.map((entry) => entry.label).sort()).toEqual(["Bricked Blast Furnace", "Coke Oven"]);
    expect(seen.get("multi smelter")?.unpowered).toBeUndefined();
  }, 600_000);
});
