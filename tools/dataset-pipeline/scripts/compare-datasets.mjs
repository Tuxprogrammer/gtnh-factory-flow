// Compare two normalized datasets of one pack (two exports, or an export and a rebuild) and report every
// difference that is not on the list of what the game itself rolls anew each launch (LAUNCH_NOISE, the same list
// as tools/dataset-pipeline/CLAUDE.md). Exits 1 when anything else differs.
//
// Usage: node compare-datasets.mjs <dataset dir A> <dataset dir B> [report.json]
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import readline from "node:readline";
import zlib from "node:zlib";
import { pathToFileURL } from "node:url";

/** What differs between two launches of one pack, and why. Nothing outside this list may differ. */
export const LAUNCH_NOISE = {
  "shown-member":
    "A slot of several blocks or items shows the one the game lists first, from a hash set (Crop Breeder soils, an assembly-line ore): the shown member, its resource and, for GregTech, the recipe id move.",
  "slot-members":
    "Such a slot built from a hash set can list another subset of blocks (a Crop Breeder soil that lacks two bartworks ores one launch): its members, shown block and recipe id move.",
  "thaumcraft-list-position":
    "Thaumcraft infusion, arcane and crucible recipes are numbered by list position, and a few register in another order: the same recipes under swapped ids.",
  "thaumcraft-generated-aspects":
    "Thaumcraft generates the aspects of items that have none from their recipes, in an order that moves: an essentia smelting recipe's aspects and time.",
  "thaumcraft-addon-aspect-costs":
    "A few addon infusion recipes (Gadomancy's Aura Core, Element of Darkness) draw their aspect costs at launch: the aspect amounts and time.",
  "eec-live-entity":
    "Extreme Entity Crusher mobs whose health or drops MobsInfo reads from a live entity (slime size, mandrake drops): the mob's numbers and its spawner's tooltip.",
  "icon-bytes": "Icons rendered from animated textures: same file name, other bytes.",
  "instance-fingerprint":
    "The asset fingerprint hashes the instance's files, which the game rewrites as it runs (its configs).",
  "icon-report-size":
    "The icon report names recipes.json's size in bytes, which follows the noise above.",
  "export-timing": "The oracle report's durationMillis: how long each reader took.",
  derived:
    "Recipe indexes and shards are built from recipes.json and the resource index: they differ when those differ by noise.",
};

const DERIVED = (file) =>
  file === "recipe-index.json.gz" ||
  file === "recipe-lookup-index.json.gz" ||
  file.startsWith("recipes-shards/");

function files(dir) {
  const out = [];
  const walk = (at) => {
    for (const entry of fs.readdirSync(path.join(dir, at), { withFileTypes: true })) {
      const rel = at ? `${at}/${entry.name}` : entry.name;
      if (entry.isDirectory()) walk(rel);
      else out.push(rel);
    }
  };
  walk("");
  return out.sort();
}

const sha = (buffer) => crypto.createHash("sha1").update(buffer).digest("hex");

function readJson(file) {
  const bytes = fs.readFileSync(file);
  return JSON.parse((file.endsWith(".gz") ? zlib.gunzipSync(bytes) : bytes).toString("utf8"));
}

// The record lists of recipes.json, compared row by row; the resource index's rows follow recipe counts, so its
// order is not compared.
const SECTIONS = ["recipes", "resources", "resourceIndex"];

// Each line of a dataset's recipes.json under its section: id -> hash, plus the text of everything else.
async function recipeLines(file, wanted) {
  const input = fs.createReadStream(file).pipe(zlib.createGunzip());
  const out = {
    recipes: new Map(),
    resources: new Map(),
    resourceIndex: new Map(),
    other: [],
    members: new Set(),
  };
  let section = "";
  for await (const line of readline.createInterface({ input, crlfDelay: Infinity })) {
    const head = /^ {2}"([^"]+)": /.exec(line);
    if (head) section = head[1];
    const record = line.startsWith("    {") && SECTIONS.includes(section);
    if (!record) {
      out.other.push(line);
      continue;
    }
    const text = line.trim().replace(/,$/, "");
    // The record's own id is its first "id" key: no record nests an object before it.
    const id = JSON.parse(`"${/"id":"((?:[^"\\]|\\.)*)"/.exec(text)[1]}"`);
    if (wanted) {
      if (wanted.has(id)) out[section].set(id, JSON.parse(text));
    } else {
      out[section].set(id, sha(text));
      if (section === "recipes" && text.includes('"alternatives":'))
        for (const entry of JSON.parse(text).inputs ?? [])
          for (const alt of entry.alternatives ?? []) out.members.add(alt.id);
    }
  }
  return out;
}

const omit = (record, keys) =>
  Object.fromEntries(Object.entries(record).filter(([key]) => !keys.includes(key)));
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const ITEM_FIELDS = ["id", "displayName", "iconPath", "dominantColor", "modId", "tooltip"];

// A recipe without its id and the raw id the exporter gave it, which is the same content hash or list position.
function withoutId(recipe) {
  const out = omit(recipe, ["id"]);
  if (recipe.source && "rawRecipeId" in recipe.source)
    out.source = omit(recipe.source, ["rawRecipeId"]);
  return out;
}

// A recipe with each slot that shows one of its own members reduced to the set of members; the id dropped.
export function withoutShownMember(recipe, keepMembers = true) {
  const slot = (entry) => {
    const alternatives = entry.alternatives ?? [];
    if (alternatives.length < 2 || !alternatives.some((alt) => alt.id === entry.id)) return entry;
    const out = omit(entry, [...ITEM_FIELDS, "alternatives"]);
    return keepMembers
      ? { ...out, members: alternatives.map((alt) => JSON.stringify(alt)).sort() }
      : out;
  };
  const out = withoutId(recipe);
  out.inputs = (recipe.inputs ?? []).map(slot);
  return out;
}

const isThaumcraft = (recipe) => recipe.category === "thaumcraft";
const aspectFree = (recipe) => ({
  ...omit(withoutId(recipe), ["durationTicks", "runtimeCalculation"]),
  inputs: (recipe.inputs ?? []).filter((entry) => entry.kind !== "aspect"),
  aspects: (recipe.inputs ?? [])
    .filter((entry) => entry.kind === "aspect")
    .map((entry) => entry.id)
    .sort(),
});

/**
 * The recipes that differ between A and B (records keyed by id, only the ones whose text differs or that one side
 * lacks), each put in its launch-noise kind; what fits none is returned in `unexplained`.
 */
export function classifyRecipes(a, b) {
  const noise = {};
  const unexplained = [];
  const count = (kind, id) => (noise[kind] ??= []).push(id);
  const restA = new Map();
  const restB = new Map();
  for (const [id, x] of a) {
    const y = b.get(id);
    if (!y) {
      restA.set(id, x);
      continue;
    }
    if (same(withoutShownMember(x), withoutShownMember(y))) count("shown-member", id);
    else if (
      x.kind === "essentia_smelting" &&
      same(
        omit(x, ["outputs", "durationTicks", "runtimeCalculation"]),
        omit(y, ["outputs", "durationTicks", "runtimeCalculation"]),
      )
    )
      count("thaumcraft-generated-aspects", id);
    else if (x.category === "kubatech-eec") count("eec-live-entity", id);
    else {
      restA.set(id, x);
      restB.set(id, y);
    }
  }
  for (const [id, y] of b) if (!a.has(id)) restB.set(id, y);
  // Pair what is left by content: the same recipe under another id, or showing another member.
  const pairUp = (key, kind, accept) => {
    const byKey = new Map();
    for (const [id, y] of restB) {
      if (!accept(y)) continue;
      const k = key(y);
      if (!byKey.has(k)) byKey.set(k, []);
      byKey.get(k).push(id);
    }
    for (const [id, x] of [...restA]) {
      if (!accept(x)) continue;
      const other = byKey.get(key(x))?.shift();
      if (other === undefined) continue;
      restA.delete(id);
      restB.delete(other);
      count(kind, id);
    }
  };
  pairUp((r) => JSON.stringify(withoutId(r)), "thaumcraft-list-position", isThaumcraft);
  pairUp(
    (r) => JSON.stringify(omit(withoutId(r), ["outputs", "durationTicks", "runtimeCalculation"])),
    "thaumcraft-generated-aspects",
    (r) => r.kind === "essentia_smelting",
  );
  pairUp(
    (r) => JSON.stringify(withoutShownMember(r)),
    "shown-member",
    () => true,
  );
  pairUp(
    (r) => JSON.stringify(withoutShownMember(r, false)),
    "slot-members",
    () => true,
  );
  pairUp(
    (r) => JSON.stringify(aspectFree(r)),
    "thaumcraft-addon-aspect-costs",
    (r) => r.kind === "gregtech_machine" && isThaumcraft(r),
  );
  for (const id of restA.keys())
    unexplained.push(b.has(id) ? `recipe ${id} differs` : `recipe ${id} only in A`);
  for (const id of restB.keys()) if (!restA.has(id)) unexplained.push(`recipe ${id} only in B`);
  return { noise, unexplained };
}

/** Resources (by id) that differ: noise when either dataset lists the resource as a member of a slot. */
export function classifyResources(a, b, members) {
  const noise = {};
  const unexplained = [];
  const count = (kind, id) => (noise[kind] ??= []).push(id);
  for (const [id, x] of a) {
    const y = b.get(id);
    if (y === undefined)
      members.has(id) ? count("shown-member", id) : unexplained.push(`resource ${id} only in A`);
    else if (same(x, y)) continue;
    else if (id.startsWith("factoryflow:eec_mob:")) count("eec-live-entity", id);
    else if (members.has(id)) count("shown-member", id);
    // An index row's recipe count follows the recipes that moved by noise.
    else if (
      Object.keys({ ...x, ...y }).every((key) => same(x[key], y[key]) || key.endsWith("Count"))
    )
      count("derived", id);
    else unexplained.push(`resource ${id} differs`);
  }
  for (const id of b.keys())
    if (!a.has(id))
      members.has(id) ? count("shown-member", id) : unexplained.push(`resource ${id} only in B`);
  return { noise, unexplained };
}

const byId = (list) => new Map((list ?? []).map((row) => [row.id, row]));

export async function compareDatasets(dirA, dirB) {
  const listA = files(dirA);
  const listB = files(dirB);
  const setB = new Set(listB);
  const unexplained = [];
  const noise = {};
  const add = (result) => {
    unexplained.push(...result.unexplained);
    for (const [kind, ids] of Object.entries(result.noise)) (noise[kind] ??= []).push(...ids);
  };
  for (const file of listA) if (!setB.has(file)) unexplained.push(`file ${file} only in A`);
  for (const file of listB) if (!listA.includes(file)) unexplained.push(`file ${file} only in B`);
  const differ = listA.filter(
    (file) =>
      setB.has(file) &&
      sha(fs.readFileSync(path.join(dirA, file))) !== sha(fs.readFileSync(path.join(dirB, file))),
  );
  const coreDiffers =
    differ.includes("recipes.json.gz") || differ.includes("resource-index.json.gz");
  let members = new Set();
  if (differ.includes("recipes.json.gz")) {
    const [la, lb] = await Promise.all([
      recipeLines(path.join(dirA, "recipes.json.gz")),
      recipeLines(path.join(dirB, "recipes.json.gz")),
    ]);
    for (const section of SECTIONS) {
      const wanted = new Set();
      for (const [id, h] of la[section]) if (lb[section].get(id) !== h) wanted.add(id);
      for (const id of lb[section].keys()) if (!la[section].has(id)) wanted.add(id);
      la[section] = wanted;
    }
    if (la.other.join("\n") !== lb.other.join("\n"))
      unexplained.push("recipes.json.gz: text outside the recipe and resource lists differs");
    const wanted = new Set([...la.recipes, ...la.resources, ...la.resourceIndex]);
    const [ra, rb] = await Promise.all([
      recipeLines(path.join(dirA, "recipes.json.gz"), wanted),
      recipeLines(path.join(dirB, "recipes.json.gz"), wanted),
    ]);
    members = new Set([...la.members, ...lb.members]);
    add(classifyRecipes(ra.recipes, rb.recipes));
    add(classifyResources(ra.resources, rb.resources, members));
    add(classifyResources(ra.resourceIndex, rb.resourceIndex, members));
  }
  for (const file of differ) {
    const fa = path.join(dirA, file);
    const fb = path.join(dirB, file);
    if (file === "recipes.json.gz") continue;
    if (DERIVED(file) && coreDiffers) (noise.derived ??= []).push(file);
    else if (file === "resource-index.json.gz") {
      const [x, y] = [readJson(fa), readJson(fb)];
      if (!same(omit(x, ["resources", "resourceIndex"]), omit(y, ["resources", "resourceIndex"])))
        unexplained.push(`${file}: header differs`);
      add(classifyResources(byId(x.resources), byId(y.resources), members));
      add(classifyResources(byId(x.resourceIndex), byId(y.resourceIndex), members));
    } else if (
      /^textures\/[^/]+-asset-fingerprint\.json$/.test(file) &&
      same(omit(readJson(fa), ["hash"]), omit(readJson(fb), ["hash"]))
    )
      (noise["instance-fingerprint"] ??= []).push(file);
    else if (
      file === "textures/icon-report.json" &&
      same(omit(readJson(fa), ["reason"]), omit(readJson(fb), ["reason"]))
    )
      (noise["icon-report-size"] ??= []).push(file);
    else if (
      file === "oracle/oracle-report.json" &&
      same(withoutTimings(readJson(fa)), withoutTimings(readJson(fb)))
    )
      (noise["export-timing"] ??= []).push(file);
    else if (/^textures\/rendered\/[^/]+\.png$/.test(file)) (noise["icon-bytes"] ??= []).push(file);
    else unexplained.push(`file ${file} differs`);
  }
  return { files: [listA.length, listB.length], differ: differ.length, noise, unexplained };
}

function withoutTimings(value) {
  if (Array.isArray(value)) return value.map(withoutTimings);
  if (value && typeof value === "object")
    return Object.fromEntries(
      Object.entries(value)
        .filter(([key]) => key !== "durationMillis")
        .map(([key, v]) => [key, withoutTimings(v)]),
    );
  return value;
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  const [dirA, dirB, reportPath] = process.argv.slice(2);
  if (!dirA || !dirB) {
    console.error("Usage: compare-datasets.mjs <dataset dir A> <dataset dir B> [report.json]");
    process.exit(2);
  }
  const report = await compareDatasets(dirA, dirB);
  console.log(`files ${report.files[0]} / ${report.files[1]}, ${report.differ} differ`);
  for (const [kind, ids] of Object.entries(report.noise))
    console.log(`  launch noise ${kind}: ${ids.length} (${ids.slice(0, 3).join(", ")})`);
  for (const line of report.unexplained.slice(0, 200)) console.log(`  NOT NOISE ${line}`);
  console.log(
    report.unexplained.length === 0
      ? "COMPARE: only launch noise"
      : `COMPARE: ${report.unexplained.length} difference(s) outside the launch noise list`,
  );
  if (reportPath) fs.writeFileSync(reportPath, `${JSON.stringify(report, null, 1)}\n`);
  process.exit(report.unexplained.length === 0 ? 0 : 1);
}
