#!/usr/bin/env node
// Writes conformance/data/smelting-records.json and conformance/data/crafting-records.json: furnace and crafting-grid
// recipes as the export run's readers wrote them (the raw export's recipe objects, plus for crafting the ore dictionary
// lists its cells name) beside the dataset records normalize-oracle-export.mjs makes of them. core-model's
// SmeltingDatasetRecipeTest and CraftingDatasetRecipeTest hold the Java dataset shapes (the mod's way to the same
// records) to them.
//
//   node --max-old-space-size=12000 tools/conformance/data/furnace-crafting-cases.mjs <oracle-export.json> [outDir]
//
// The input is a full raw export (about 450 MB, never committed). Cases are picked so every rule shows up a few
// times: shaped grids of each width, shapeless lists, ore dictionary cells (one name and several, short and long
// lists), choice cells, fluid cells, NBT, the recipe classes the crafting reader opens itself, cells nothing fills
// (the dataset drops the recipe), wildcard furnace inputs and stacked outputs. Classes the dataset drops by name
// (scripts/unreadable-crafting.mjs) are not picked: the Java shape leaves that list to its caller.
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { UNREADABLE_CRAFTING_CLASSES } from "../../dataset-pipeline/scripts/unreadable-crafting.mjs";

const repo = fileURLToPath(new URL("../../../", import.meta.url));
const [input, outArg] = process.argv.slice(2);
if (!input) throw new Error("usage: furnace-crafting-cases.mjs <oracle-export.json> [outDir]");
const outDir = outArg ?? path.join(repo, "conformance/data");
const LIMIT = 3;

const raw = JSON.parse(readFileSync(input, "utf8"));
const domain = (id) => raw.domains.find((d) => d.id === id);
const oreEntries = domain("oreDictionary")?.entries ?? {};
const strip = (o) =>
  Array.isArray(o)
    ? o.map(strip)
    : o && typeof o === "object"
      ? Object.fromEntries(
          Object.entries(o)
            .filter(([k]) => k !== "icon")
            .map(([k, v]) => [k, strip(v)]),
        )
      : o;

function pick(recipes, tagsOf) {
  const seen = new Map();
  const picked = [];
  for (const r of recipes) {
    const t = tagsOf(r);
    if (!t.some((tag) => (seen.get(tag) ?? 0) < LIMIT)) continue;
    for (const tag of t) seen.set(tag, (seen.get(tag) ?? 0) + 1);
    picked.push({ recipe: r, tags: t });
  }
  return { picked, seen };
}

function smeltingTags(r) {
  const t = ["smelting"];
  if (r.input?.meta === 32767) t.push("wildcard");
  if ((r.output?.amount ?? 1) > 1) t.push("stacked-output");
  if (r.input?.nbt || r.output?.nbt) t.push("nbt");
  if (r.input?.registryId?.startsWith("minecraft:")) t.push("vanilla-input");
  if (r.input?.registryId?.startsWith("gregtech:")) t.push("gregtech-input");
  return t;
}

// The classes CraftingIngredients opens itself (jvm/mod read/crafting).
const OWN_READS = new Set([
  "appeng.recipes.game.ShapedRecipe",
  "appeng.recipes.game.ShapelessRecipe",
  "mrtjp.projectred.core.libmc.recipe.ShapedBuilderRecipe",
  "mrtjp.projectred.core.libmc.recipe.ShapelessBuilderRecipe",
  "com.dreammaster.scripts.ScriptEFR$ShulkerNBTRecipe",
  "logisticspipes.recipes.ShapelessResetRecipe",
  "mods.battlegear2.heraldry.KnightArmourRecipie",
  "com.gregguns.armor.slots.EquipmentRefillRecipe",
]);

function craftingTags(r) {
  const t = [`type:${r.type === "shaped" || r.type === "shapeless" ? r.type : "class"}`];
  if (r.type === "shaped") t.push(`width:${r.width}`);
  t.push(`class:${OWN_READS.has(r.className) ? r.className : "other"}`);
  for (const cell of r.inputs ?? []) {
    t.push(`cell:${cell.kind}`);
    if (cell.kind === "oreDictionary" && cell.names.length > 1) t.push("cell:oreDictionary:names");
    if (cell.kind === "oreDictionary") {
      const n = cell.names.reduce((sum, name) => sum + (oreEntries[name]?.length ?? 0), 0);
      if (n > 12) t.push("cell:oreDictionary:long");
      if (n === 0) t.push("cell:oreDictionary:empty");
    }
    if (cell.kind === "choice" && cell.alternatives.length > 12) t.push("cell:choice:long");
    if (cell.nbt) t.push("nbt");
  }
  if (r.output?.nbt) t.push("nbt:output");
  if ((r.output?.amount ?? 1) > 1) t.push("stacked-output");
  return t;
}

const dir = mkdtempSync(path.join(tmpdir(), "furnace-crafting-cases-"));
try {
  // One normalizer run per recipe, so each record pairs with its raw recipe however the normalizer merges or drops.
  const normalize = (domains) => {
    const mini = {
      schemaVersion: 1,
      exporter: raw.exporter,
      format: raw.format,
      generatedAt: raw.generatedAt,
      minecraftVersion: "1.7.10",
      loadedMods: [],
      adapters: [],
      domains,
    };
    writeFileSync(path.join(dir, "raw.json"), JSON.stringify(mini));
    try {
      execFileSync(
        process.execPath,
        [
          path.join(repo, "tools/dataset-pipeline/scripts/normalize-oracle-export.mjs"),
          path.join(dir, "raw.json"),
          path.join(dir, "out", "recipes.json"),
        ],
        {
          env: {
            ...process.env,
            GTNH_DATASET_VERSION_ID: "cases",
            GTNH_DATASET_VERSION_LABEL: "cases",
            GTNH_RENDERED_ICON_DIR: "",
          },
          stdio: ["ignore", "ignore", "pipe"],
        },
      );
    } catch (error) {
      // A recipe the dataset drops leaves the normalizer nothing to write, which it refuses.
      if (String(error.stderr).includes("produced zero recipes")) return [];
      throw error;
    }
    return JSON.parse(readFileSync(path.join(dir, "out", "recipes.json"), "utf8")).recipes;
  };
  // Each export's own: its ids, notes and provenance; the machines are the caller's (the curated table).
  const plain = (record) => {
    const rest = { ...record, source: { ...record.source } };
    delete rest.id;
    delete rest.notes;
    delete rest.machineHandlers;
    delete rest.source.datasetVersionId;
    delete rest.source.rawRecipeId;
    delete rest.source.exporter;
    return rest;
  };
  const withoutId = (recipe) => {
    const copy = { ...recipe };
    delete copy.id;
    return JSON.parse(JSON.stringify(strip(copy)));
  };

  const smelting = pick(domain("smelting")?.recipes ?? [], smeltingTags);
  const smeltingCases = smelting.picked.map(({ recipe, tags }) => {
    const out = normalize([
      { id: "oreDictionary", entries: {} },
      { id: "smelting", recipes: [recipe] },
    ]);
    if (out.length > 1) throw new Error("one furnace recipe normalized into several");
    return { tags, raw: withoutId(recipe), dataset: out.length ? plain(out[0]) : null };
  });

  const crafting = pick(
    (domain("crafting")?.recipes ?? []).filter(
      (r) => !UNREADABLE_CRAFTING_CLASSES.has(r.className),
    ),
    craftingTags,
  );
  const craftingCases = crafting.picked.map(({ recipe, tags }) => {
    const names = [
      ...new Set(
        (recipe.inputs ?? []).filter((c) => c.kind === "oreDictionary").flatMap((c) => c.names),
      ),
    ];
    const entries = Object.fromEntries(names.map((name) => [name, oreEntries[name] ?? []]));
    const out = normalize([
      { id: "oreDictionary", entries },
      { id: "crafting", recipes: [recipe] },
    ]);
    if (out.length > 1) throw new Error("one crafting recipe normalized into several");
    return {
      tags,
      raw: withoutId(recipe),
      oreDictionary: JSON.parse(JSON.stringify(strip(entries))),
      dataset: out.length ? plain(out[0]) : null,
    };
  });

  for (const [file, cases, seen] of [
    ["smelting-records.json", smeltingCases, smelting.seen],
    ["crafting-records.json", craftingCases, crafting.seen],
  ]) {
    writeFileSync(path.join(outDir, file), `${JSON.stringify({ cases }, null, 1)}\n`);
    console.log(`${cases.length} cases -> ${path.join(outDir, file)}`);
    console.log(
      [...seen.entries()]
        .sort()
        .map(([k, v]) => `  ${k} ${v}`)
        .join("\n"),
    );
  }
} finally {
  rmSync(dir, { recursive: true, force: true });
}
