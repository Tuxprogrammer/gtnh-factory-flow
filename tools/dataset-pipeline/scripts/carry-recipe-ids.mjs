// Keep a previous dataset's recipe ids in a fresh build.
//
// A fresh oracle export re-mints recipe ids: every id carries the dataset
// version, and crafting, furnace, thaumcraft and other recipes are numbered in
// an order the game does not keep. Saved plans name recipes by id, so a new
// build takes the previous build's id for every recipe that does the same
// thing (same map, slots, amounts, chances, ticks and EU). Recipes named by a
// game identity (a bee species, a crop, a vein, a mob, an underground fluid)
// keep their id when that identity is unchanged, even if their numbers moved.
// A recipe that only changed because the game renamed an item it takes or
// makes (resource-renames.json beside the new recipes.json, the game's own
// Postea table) keeps its id too: recipes no exact match took are matched
// again with every item read through the renames, on both sides.
// A slot of several blocks or items that shows the one the game happens to
// list first (a Crop Breeder soil, an assembly-line ore) shows another one
// after a relaunch: a recipe that differs only in which member such a slot
// shows keeps its id and shows the member the previous dataset showed, so the
// cell's id stays too. Ore dictionary cells are not matched this way.
// Everything else (new or changed recipes) keeps its new id.
//
// Usage: node carry-recipe-ids.mjs <previous recipes.json[.gz]> <new recipes.json> [report.json] [--renames=<file>]
// Rewrites the new recipes.json in place. Run after normalize-oracle-export and
// before the resource and recipe indexes.
import fs from "node:fs";
import path from "node:path";
import readline from "node:readline";
import zlib from "node:zlib";
import { RESOURCE_RENAMES_FILE, renameLookup, renameResourceId } from "./resource-renames.mjs";

const args = process.argv.slice(2).filter((arg) => !arg.startsWith("--renames="));
const renamesArg = process.argv.slice(2).find((arg) => arg.startsWith("--renames="))?.slice("--renames=".length);
const [previousPath, nextPath, reportPath] = args;
if (!previousPath || !nextPath) {
  console.error(
    "Usage: carry-recipe-ids.mjs <previous recipes.json[.gz]> <new recipes.json> [report.json] [--renames=<file>]",
  );
  process.exit(1);
}

// Kinds whose id is a game identity rather than a content hash or an index.
const IDENTITY_CATEGORIES = new Set([
  "forestry-bee",
  "ic2-crop",
  "cropsnh-crop",
  "kubatech-eec",
  "ore-vein",
  "small-ore",
  "underground-fluid",
]);

function lines(filePath) {
  const stream = fs.createReadStream(filePath);
  const input = filePath.endsWith(".gz") ? stream.pipe(zlib.createGunzip()) : stream;
  return readline.createInterface({ input, crlfDelay: Infinity });
}

function slot(entry, output, renames) {
  return [
    entry.kind,
    renames ? renameResourceId(renames, entry.id, entry.kind) : entry.id,
    entry.amount ?? "",
    output ? "" : entry.consumed === false ? "nc" : "",
    entry.chance ?? "",
  ].join("|");
}

// GregTech maps by their game id, as it reads in the newest pack: GT++ merged
// into GregTech in 2.9.0-RC-1, so its gtpp.recipe.* maps are gt.recipe.* there,
// and the Industrial Coke Oven's map took the machine's name. Display names
// changed too ("Naquadah Reactor MkII" is "Mk-II"), so names are not used.
const MAP_RENAMES = new Map([["gtpp.recipe.cokeoven", "gt.recipe.industrialcokeoven"]]);

function gameMap(recipe) {
  const raw = String(recipe.source?.rawRecipeId ?? "");
  const colon = raw.lastIndexOf(":");
  if (recipe.category !== "gregtech" || colon <= 0 || !raw.includes(".recipe.")) {
    return undefined;
  }
  const map = raw.slice(0, colon);
  return MAP_RENAMES.get(map) ?? map.replace(/^gtpp\.recipe\./, "gt.recipe.");
}

function machineKey(recipe) {
  return gameMap(recipe) ?? String(recipe.machineType ?? "").toLowerCase().replace(/[^a-z0-9]+/g, "");
}

export function contentKey(recipe, renames) {
  return JSON.stringify([
    recipe.category,
    recipe.kind,
    machineKey(recipe),
    recipe.specialValue ?? null,
    recipe.durationTicks ?? null,
    recipe.eut ?? null,
    (recipe.inputs ?? []).map((entry) => slot(entry, false, renames)).sort(),
    (recipe.outputs ?? []).map((entry) => slot(entry, true, renames)).sort(),
  ]);
}

// contentKey with each slot's NBT text: content keys ignore NBT, so recipes that differ only in it (crop seeds,
// tool materials, a culture's name) share one, and file order alone would pair them. A new recipe takes the
// previous id of the recipe with its own NBT first.
export function nbtKey(recipe) {
  const nbt = (entry) => (entry.tooltip ?? []).find((line) => String(line).startsWith("NBT: ")) ?? "";
  return JSON.stringify([
    contentKey(recipe),
    (recipe.inputs ?? []).map((entry) => `${slot(entry, false)}|${nbt(entry)}`).sort(),
    (recipe.outputs ?? []).map((entry) => `${slot(entry, true)}|${nbt(entry)}`).sort(),
  ]);
}

// A slot that shows one of its own alternatives, keyed by all of them; else undefined.
function alternativesKey(entry) {
  const alternatives = entry.alternatives ?? [];
  if (alternatives.length < 2 || !alternatives.some((alt) => alt.kind === entry.kind && alt.id === entry.id)) {
    return undefined;
  }
  const ids = alternatives.map((alt) => `${alt.kind}:${alt.id}`).sort();
  return [entry.kind, `any(${ids.join(",")})`, entry.amount ?? "", entry.consumed === false ? "nc" : "", entry.chance ?? ""].join("|");
}

// contentKey with every such slot keyed by its alternatives, and [slot key, id it shows] of each; undefined when
// it has none.
export function shownKey(recipe) {
  const inputs = recipe.inputs ?? [];
  const shown = inputs.filter(alternativesKey).map((entry) => [alternativesKey(entry), entry.id]);
  if (shown.length === 0) {
    return undefined;
  }
  const key = JSON.stringify([
    recipe.category,
    recipe.kind,
    machineKey(recipe),
    recipe.specialValue ?? null,
    recipe.durationTicks ?? null,
    recipe.eut ?? null,
    inputs.map((entry) => alternativesKey(entry) ?? slot(entry, false)).sort(),
    (recipe.outputs ?? []).map((entry) => slot(entry, true)).sort(),
  ]);
  return { key, shown };
}

const ITEM_FIELDS = ["id", "displayName", "iconPath", "dominantColor", "modId", "tooltip"];

// The slot showing its alternative `id`: that member's own fields, the slot's amount and flags, the member first.
function showAlternative(entry, id) {
  const alt = entry.alternatives.find((candidate) => candidate.kind === entry.kind && candidate.id === id);
  if (!alt || entry.id === id) {
    return entry;
  }
  const out = {};
  for (const [field, value] of Object.entries(entry)) {
    if (!ITEM_FIELDS.includes(field)) out[field] = value;
    else if (alt[field] !== undefined) out[field] = alt[field];
  }
  out.alternatives = [alt, ...entry.alternatives.filter((candidate) => candidate !== alt)];
  return out;
}

function identityKey(recipe) {
  return `${recipe.category}|${String(recipe.id).replace(/^oracle:[^:]+:/, "")}`;
}

function recipeLine(line) {
  const trimmed = line.trimStart();
  if (!trimmed.startsWith("{")) {
    return undefined;
  }
  return JSON.parse(trimmed.replace(/,$/, ""));
}

async function readPrevious(filePath, renames) {
  const byContent = new Map();
  const byNbt = new Map();
  const byRenamed = new Map();
  const byIdentity = new Map();
  const byShown = new Map();
  const all = new Map();
  let inRecipes = false;
  for await (const line of lines(filePath)) {
    if (/^ {2}"[^"]+": /.test(line)) {
      inRecipes = line.startsWith('  "recipes": [');
      continue;
    }
    if (!inRecipes) {
      continue;
    }
    const recipe = recipeLine(line);
    if (!recipe) {
      continue;
    }
    all.set(recipe.id, recipe.category);
    const key = contentKey(recipe);
    if (!byContent.has(key)) {
      byContent.set(key, []);
    }
    byContent.get(key).push(recipe.id);
    const withNbt = nbtKey(recipe);
    if (!byNbt.has(withNbt)) {
      byNbt.set(withNbt, []);
    }
    byNbt.get(withNbt).push(recipe.id);
    if (renames) {
      const renamed = contentKey(recipe, renames);
      if (!byRenamed.has(renamed)) {
        byRenamed.set(renamed, []);
      }
      byRenamed.get(renamed).push(recipe.id);
    }
    if (IDENTITY_CATEGORIES.has(recipe.category)) {
      byIdentity.set(identityKey(recipe), recipe.id);
    }
    const shown = shownKey(recipe);
    if (shown) {
      if (!byShown.has(shown.key)) {
        byShown.set(shown.key, []);
      }
      byShown.get(shown.key).push({ id: recipe.id, shown: shown.shown });
    }
  }
  return { byContent, byNbt, byRenamed, byIdentity, byShown, all };
}

// The previous id each new recipe (by line) takes by exact content: first every recipe whose NBT matches too, then
// the rest by content alone, each in file order.
async function exactMatches(previous) {
  const matches = new Map();
  const used = new Set();
  const rest = [];
  let inRecipes = false;
  let lineNo = -1;
  for await (const line of lines(nextPath)) {
    lineNo += 1;
    if (/^ {2}"[^"]+": /.test(line)) {
      inRecipes = line.startsWith('  "recipes": [');
      continue;
    }
    const recipe = inRecipes ? recipeLine(line) : undefined;
    if (!recipe) {
      continue;
    }
    const key = contentKey(recipe);
    if (!previous.byContent.has(key)) {
      continue;
    }
    const match = (previous.byNbt.get(nbtKey(recipe)) ?? []).find((id) => !used.has(id));
    if (match) {
      used.add(match);
      matches.set(lineNo, match);
    } else {
      rest.push([lineNo, key]);
    }
  }
  for (const [at, key] of rest) {
    const match = previous.byContent.get(key).find((id) => !used.has(id));
    if (match) {
      used.add(match);
      matches.set(at, match);
    }
  }
  return matches;
}

function loadRenames() {
  const file = renamesArg ?? path.join(path.dirname(nextPath), RESOURCE_RENAMES_FILE);
  if (!fs.existsSync(file)) {
    return undefined;
  }
  const lookup = renameLookup(JSON.parse(fs.readFileSync(file, "utf8")));
  return lookup.size > 0 ? lookup : undefined;
}

// Which new recipes (by line) only the renames match, or only the members
// their slots show, and the previous id each takes (with the members that
// recipe showed): exact matches are settled first, in file order as main does,
// so a second-chance match never takes an id an exact match would. Also the
// catalog rows of the members those recipes show again (the resource index
// takes a rendered icon only from the catalog).
async function renamedMatches(previous, renames, exact) {
  const matches = new Map();
  const catalog = new Map();
  const used = new Set();
  const unmatched = [];
  let inRecipes = false;
  let lineNo = -1;
  for await (const line of lines(nextPath)) {
    lineNo += 1;
    if (/^ {2}"[^"]+": /.test(line)) {
      inRecipes = line.startsWith('  "recipes": [');
      continue;
    }
    const recipe = inRecipes ? recipeLine(line) : undefined;
    if (!recipe) {
      continue;
    }
    const match = exact.get(lineNo);
    if (match) {
      used.add(match);
    } else {
      const shown = shownKey(recipe);
      const alternatives = shown ? (recipe.inputs ?? []).flatMap((entry) => entry.alternatives ?? []) : [];
      unmatched.push({ lineNo, key: renames ? contentKey(recipe, renames) : undefined, shown, alternatives });
    }
  }
  for (const { lineNo: at, key } of unmatched) {
    const match = key === undefined ? undefined : (previous.byRenamed.get(key) ?? []).find((id) => !used.has(id));
    if (match) {
      used.add(match);
      matches.set(at, { id: match });
    }
  }
  for (const { lineNo: at, shown, alternatives } of unmatched) {
    const match = matches.has(at) || !shown ? undefined : (previous.byShown.get(shown.key) ?? []).find((row) => !used.has(row.id));
    if (match) {
      used.add(match.id);
      matches.set(at, match);
      for (const [, id] of match.shown) {
        const alt = alternatives.find((candidate) => candidate.id === id);
        if (alt) {
          const row = { id: alt.id, ...alt };
          delete row.amount;
          catalog.set(`${alt.kind}:${alt.id}`, row);
        }
      }
    }
  }
  return { matches, catalog };
}

function bump(table, category, field) {
  table[category] ??= { kept: 0, keptByRename: 0, keptByShown: 0, keptByIdentity: 0, new: 0, gone: 0 };
  table[category][field] += 1;
}

async function main() {
  const renames = loadRenames();
  const previous = await readPrevious(previousPath, renames);
  const exactByLine = await exactMatches(previous);
  const { matches: byRename, catalog } = await renamedMatches(previous, renames, exactByLine);
  const keptByRename = [];
  const keptByShown = [];
  const used = new Set();
  const counts = {};
  const pendingIdentity = [];
  const outPath = `${nextPath}.carry-tmp`;
  const out = fs.createWriteStream(outPath);
  const write = (text) =>
    new Promise((resolve) => (out.write(text) ? resolve() : out.once("drain", resolve)));

  // Pass one: exact content. Pass two (identity kinds) needs to know which
  // previous ids exact matches took, so identity recipes are held and written
  // in place once the whole file has been read: they are few.
  const buffered = [];
  let inRecipes = false;
  let resourceRows;
  // The catalog (sorted by kind:id) with each row a shown member needs and lacks put in its place.
  const flushResources = () => {
    if (!resourceRows) return;
    for (const [key, row] of catalog) {
      if (resourceRows.some((existing) => existing.key === key)) continue;
      const at = resourceRows.findIndex((existing) => existing.key.localeCompare(key) > 0);
      resourceRows.splice(at < 0 ? resourceRows.length : at, 0, { key, text: JSON.stringify(row) });
    }
    resourceRows.forEach((row, i) => buffered.push({ text: `    ${row.text}${i < resourceRows.length - 1 ? "," : ""}` }));
    resourceRows = undefined;
  };
  let lineNo = -1;
  for await (const line of lines(nextPath)) {
    lineNo += 1;
    if (/^ {2}"[^"]+": /.test(line)) {
      flushResources();
      inRecipes = line.startsWith('  "recipes": [');
      if (line.startsWith('  "resources": [') && catalog.size > 0) resourceRows = [];
      buffered.push({ text: line });
      continue;
    }
    if (resourceRows && line.trimStart().startsWith("{")) {
      const row = recipeLine(line);
      resourceRows.push({ key: `${row.kind}:${row.id}`, text: line.trim().replace(/,$/, "") });
      continue;
    }
    flushResources();
    const recipe = inRecipes ? recipeLine(line) : undefined;
    if (!recipe) {
      buffered.push({ text: line });
      continue;
    }
    const exact = exactByLine.get(lineNo);
    const second = exact ? undefined : byRename.get(lineNo);
    const match = exact ?? second?.id;
    const prefix = line.slice(0, line.length - line.trimStart().length);
    const comma = line.trimEnd().endsWith(",") ? "," : "";
    if (match) {
      used.add(match);
      recipe.id = match;
      if (second?.shown) {
        const pending = [...second.shown];
        recipe.inputs = recipe.inputs.map((entry) => {
          const at = pending.findIndex(([key]) => key === alternativesKey(entry));
          return at < 0 ? entry : showAlternative(entry, pending.splice(at, 1)[0][1]);
        });
      }
      bump(counts, recipe.category, exact ? "kept" : second.shown ? "keptByShown" : "keptByRename");
      if (second) {
        (second.shown ? keptByShown : keptByRename).push(match);
      }
      buffered.push({ text: `${prefix}${JSON.stringify(recipe)}${comma}` });
    } else if (IDENTITY_CATEGORIES.has(recipe.category)) {
      const entry = { recipe, prefix, comma, text: undefined };
      pendingIdentity.push(entry);
      buffered.push(entry);
    } else {
      bump(counts, recipe.category, "new");
      buffered.push({ text: line });
    }
    // Flush everything before the first pending identity recipe.
    if (pendingIdentity.length === 0 && buffered.length > 4096) {
      for (const item of buffered.splice(0)) {
        await write(`${item.text}\n`);
      }
    }
  }

  const changedIdentity = [];
  for (const entry of pendingIdentity) {
    const id = previous.byIdentity.get(identityKey(entry.recipe));
    if (id && !used.has(id)) {
      used.add(id);
      entry.recipe.id = id;
      bump(counts, entry.recipe.category, "keptByIdentity");
      changedIdentity.push(id);
    } else {
      bump(counts, entry.recipe.category, "new");
    }
    entry.text = `${entry.prefix}${JSON.stringify(entry.recipe)}${entry.comma}`;
  }
  for (const item of buffered) {
    await write(`${item.text}\n`);
  }
  await new Promise((resolve) => out.end(resolve));

  const goneIds = [];
  for (const [id, category] of previous.all) {
    if (!used.has(id)) {
      bump(counts, category, "gone");
      goneIds.push(id);
    }
  }
  fs.renameSync(outPath, nextPath);

  const totals = { kept: 0, keptByRename: 0, keptByShown: 0, keptByIdentity: 0, new: 0, gone: 0 };
  for (const row of Object.values(counts)) {
    for (const field of Object.keys(totals)) {
      totals[field] += row[field];
    }
  }
  console.table({ ...counts, TOTAL: totals });
  if (reportPath) {
    fs.writeFileSync(
      reportPath,
      `${JSON.stringify(
        { previous: previousPath, counts, totals, keptByRename, keptByShown, keptByIdentity: changedIdentity, gone: goneIds },
        null,
        1,
      )}\n`,
    );
  }
}

await main();
