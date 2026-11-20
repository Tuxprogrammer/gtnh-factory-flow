#!/usr/bin/env node
// Writes conformance/data/gt-records.json: GregTech recipes as the export run's reader wrote them (the raw export's
// recipe objects) beside the dataset records normalize-oracle-export.mjs makes of them. core-model's
// GtDatasetRecipeTest holds GtDatasetRecipe (the mod's way to the same record) to them.
//
//   node --max-old-space-size=12000 tools/conformance/data/gt-record-cases.mjs <oracle-export.json> [out]
//
// The input is a full raw export (about 450 MB, never committed). Cases are picked so every rule shows up a few
// times: each coil and perfect-overclock ladder, multi-amp maps, fusion, Rock Breaker and Scanner times, kept slots,
// input and output chances, slot alternatives, NBT, special-slot items, fluid-only recipes. The Sparge Tower (whose
// rolled byproducts the mod's record leaves out) and the Canner maps (which also feed the synthetic Tank map) are not
// picked.
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repo = fileURLToPath(new URL("../../../", import.meta.url));
const [input, outArg] = process.argv.slice(2);
if (!input) throw new Error("usage: gt-record-cases.mjs <oracle-export.json> [out]");
const out = outArg ?? path.join(repo, "conformance/data/gt-records.json");
const LIMIT = 3;
const SKIP = new Set(["gtpp.recipe.lftr.sparging", "gt.recipe.canner", "gt.recipe.fluidcanner"]);
const NAMED = new Set([
  "gt.recipe.fusionreactor",
  "gt.recipe.rockbreaker",
  "gt.recipe.scanner",
  "gt.recipe.multiblockrockbreaker",
]);

const raw = JSON.parse(readFileSync(input, "utf8"));
const gregtech = raw.domains.find((d) => d.id === "gregtech");
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

function tags(map, r) {
  const t = [];
  const rt = r.runtimeCalculation ?? {};
  t.push(`profile:${rt.profile ?? "standard"}`);
  if (rt.status === "missing") t.push("missing");
  if ((map.amperage ?? 1) > 1) t.push("amps");
  if (NAMED.has(map.id)) t.push(`map:${map.id}`);
  for (const k of ["itemInputs", "fluidInputs"])
    for (const s of r[k] ?? []) {
      if ("inputChance" in s) t.push(`inputChance:${k}`);
      if (s.consumed === false) t.push(`kept:${k}`);
      if (s.alternatives) t.push(`alternatives:${k}`);
      if (s.nbt) t.push("nbt");
      if (s.id.includes("gt.integrated_circuit")) t.push("circuit");
    }
  for (const k of ["itemOutputs", "fluidOutputs"])
    for (const s of r[k] ?? []) {
      if ("chance" in s) t.push(`chance:${k}${s.chance === 0 ? ":0" : ""}`);
    }
  if ((r.nonConsumedInputs ?? []).length) t.push("special");
  if (!(r.itemOutputs ?? []).length && (r.fluidOutputs ?? []).length) t.push("fluid-only");
  if (r.durationTicks === 1) t.push("one-tick");
  if (r.metadata) t.push("metadata");
  return t;
}

const seen = new Map();
const maps = [];
for (const map of gregtech.recipeMaps) {
  if (SKIP.has(map.id)) continue;
  const recipes = [];
  for (const r of map.recipes) {
    if (!(r.itemOutputs ?? []).length && !(r.fluidOutputs ?? []).length) continue;
    const t = tags(map, r);
    if (!t.some((tag) => (seen.get(tag) ?? 0) < LIMIT)) continue;
    for (const tag of t) seen.set(tag, (seen.get(tag) ?? 0) + 1);
    recipes.push(strip(r));
  }
  if (recipes.length)
    maps.push({ id: map.id, name: map.name, amperage: map.amperage ?? 1, recipes });
}

const dir = mkdtempSync(path.join(tmpdir(), "gt-record-cases-"));
try {
  // One normalizer run per recipe, so each record pairs with its raw recipe however the normalizer merges or drops.
  const normalize = (map, recipe) => {
    const mini = {
      schemaVersion: 1,
      exporter: raw.exporter,
      format: raw.format,
      generatedAt: raw.generatedAt,
      minecraftVersion: "1.7.10",
      loadedMods: [],
      adapters: [],
      domains: [
        { id: "oreDictionary", entries: {} },
        { id: "gregtech", recipeMaps: [{ ...map, recipes: [recipe] }] },
      ],
    };
    writeFileSync(path.join(dir, "raw.json"), JSON.stringify(mini));
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
        stdio: ["ignore", "ignore", "inherit"],
      },
    );
    return JSON.parse(readFileSync(path.join(dir, "out", "recipes.json"), "utf8")).recipes.filter(
      (r) => r.kind === "gregtech_machine",
    );
  };
  const cases = [];
  for (const map of maps) {
    for (const recipe of map.recipes) {
      const head = { id: map.id, name: map.name, amperage: map.amperage };
      const [d, ...more] = normalize(head, recipe);
      if (!d || more.length) continue;
      // Each export's own: its ids, time and provenance.
      const { id, notes, ...record } = d;
      delete record.source.datasetVersionId;
      delete record.source.rawRecipeId;
      delete record.source.exporter;
      if (record.runtimeCalculation) delete record.runtimeCalculation.generatedAt;
      const plain = { ...recipe };
      delete plain.id;
      if (plain.runtimeCalculation)
        plain.runtimeCalculation = { ...plain.runtimeCalculation, generatedAt: undefined };
      cases.push({
        tags: tags(map, recipe),
        raw: { map: head, recipe: JSON.parse(JSON.stringify(plain)) },
        dataset: record,
      });
    }
  }
  writeFileSync(out, `${JSON.stringify({ cases }, null, 1)}\n`);
  console.log(`${cases.length} cases -> ${out}`);
  console.log(
    [...seen.entries()]
      .sort()
      .map(([k, v]) => `${k} ${v}`)
      .join("\n"),
  );
} finally {
  rmSync(dir, { recursive: true, force: true });
}
