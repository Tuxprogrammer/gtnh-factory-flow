/**
 * Which settings a machine card shows, measured over a whole dataset, and every card that shows one setting twice.
 *
 *   npx tsx tools/audits/card-controls-audit.mjs [--dataset <dir or version id>] [--json <file>]
 *
 * --dataset is a dataset directory holding recipes-shards/, or a version id under public/datasets/gtnh (default: the
 * newest one installed). For every recipe and every machine handler it offers, the card's settings are composed as
 * the website composes them (settingControls in the clean card's settings model: the dataset's controls, the machine
 * table's over them, the table's hidden ones dropped, the coil first). A machine has a doubled control when:
 * - two settings on one of its cards have the same label (case and spacing ignored), or
 * - it is a machine-table machine and a setting the dataset added (not one the table declares) is unread: set to
 *   each of its options it changes no number of the machine's first recipe (duration, EU/t, overclocks, structural
 *   parallels), and it names what a table setting on the same card names: a label word of four letters or more
 *   ("Containment Block Tier" and "Containment Block") or two choices ("Neodymium" and "Neodymium Electromagnet").
 * Every other unread dataset setting of a table machine is listed too, apart from the doubles.
 * The mod's side is CardControlsTest in jvm/core/core-machines, over handlers.json.
 *
 * Ends with "N of M machines checked, K with a doubled control" (a machine is a handler label).
 */
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";
import { settingControls } from "../../src/components/flow/clean/menus/settings-model";
import {
  applyMachineHandlerToRecipe,
  getRecipeMachineHandlers,
} from "../../src/lib/model/recipe-rules";
import { getMachineBehaviour, getMachineTableControls } from "../../src/lib/machines/machine-table";
import { getOverclockedRecipeStats } from "../../src/lib/solver/overclock";
import { getMachineStructuralParallels } from "../../src/lib/solver/machine-effects";

const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), "../..");

function arg(name) {
  const i = process.argv.indexOf(name);
  return i >= 0 ? process.argv[i + 1] : undefined;
}

function datasetDir() {
  const given = arg("--dataset");
  const root = path.join(ROOT, "public/datasets/gtnh");
  if (given) return fs.existsSync(given) ? given : path.join(root, given);
  const versions = fs
    .readdirSync(root)
    .filter((name) => fs.existsSync(path.join(root, name, "recipes-shards")));
  if (versions.length === 0) throw new Error("no dataset installed under public/datasets/gtnh");
  return path.join(root, versions.sort().at(-1));
}

const norm = (text) =>
  String(text ?? "")
    .trim()
    .replace(/\s+/g, " ")
    .toLowerCase();

function node(recipe, handlerId, tiers) {
  return {
    id: "audit",
    recipeId: recipe.id,
    machineHandlerId: handlerId,
    machineCount: 1,
    parallel: 1,
    overclockTier: recipe.minimumTier && recipe.minimumTier !== "NONE" ? recipe.minimumTier : "LV",
    enabled: true,
    position: { x: 0, y: 0 },
    machineConfigTiers: tiers,
  };
}

/** Pairs of settings on one card with the same label. */
function sameLabels(controls) {
  const found = [];
  for (let i = 0; i < controls.length; i += 1) {
    for (let j = i + 1; j < controls.length; j += 1) {
      if (norm(controls[i].label) === norm(controls[j].label)) {
        found.push(`${controls[i].id} + ${controls[j].id}: same label "${controls[i].label}"`);
      }
    }
  }
  return found;
}

const words = (label) =>
  norm(label)
    .split(/[^a-z0-9]+/)
    .filter((w) => w.length >= 4 && w !== "tier");
const choices = (c) => (c.options ?? []).map((o) => norm(o.label)).filter((l) => l.length > 2);

/** Why an unread dataset setting reads as this table setting, if it does. */
function sameMeaning(unread, table) {
  const shared = words(unread.label).filter((w) => words(table.label).includes(w));
  if (shared.length) return `names "${shared.join(" ")}" as ${table.id} does`;
  const ob = choices(table);
  const overlap = choices(unread).filter((x) =>
    ob.some((y) => x === y || x.endsWith(` ${y}`) || y.endsWith(` ${x}`)),
  );
  return overlap.length >= 2
    ? `offers ${table.id}'s choices (${overlap.slice(0, 3).join(", ")})`
    : undefined;
}

/** The numbers a setting can change on one card. */
function fingerprint(effective, n) {
  const stats = getOverclockedRecipeStats(effective, n);
  return JSON.stringify([
    stats.durationTicks,
    stats.eut,
    stats.overclockSteps,
    getMachineStructuralParallels(effective, n),
  ]);
}

/** Dataset settings of a table machine that change no number when set to each of their options. */
function deadDatasetControls(recipe, handler, controls) {
  if (!getMachineBehaviour(handler.machineType)) return [];
  const effective = applyMachineHandlerToRecipe(recipe, { machineHandlerId: handler.id });
  const tableIds = new Set(
    getMachineTableControls(handler.machineType, effective).map((c) => c.id),
  );
  const dead = [];
  for (const control of controls) {
    if (tableIds.has(control.id) || control.id === "heatingCoil" || control.numeric) continue;
    const prints = new Set();
    for (const option of control.options) {
      const n = node(recipe, handler.id, { [control.id]: option.key });
      prints.add(fingerprint(applyMachineHandlerToRecipe(recipe, n), n));
    }
    if (prints.size <= 1 && control.options.length > 1) dead.push(control.id);
  }
  return dead;
}

const dir = datasetDir();
const machines = new Map();
let recipes = 0;
const shards = path.join(dir, "recipes-shards");
for (const file of fs.readdirSync(shards).sort()) {
  const shard = JSON.parse(zlib.gunzipSync(fs.readFileSync(path.join(shards, file))).toString());
  for (const recipe of shard.recipes) {
    if (recipe.power) continue;
    recipes += 1;
    for (const handler of getRecipeMachineHandlers(recipe)) {
      const label = handler.label ?? handler.machineType ?? "?";
      let m = machines.get(label);
      if (!m) {
        m = {
          label,
          machineType: handler.machineType,
          table: Boolean(getMachineBehaviour(handler.machineType)),
          recipes: 0,
          cards: new Map(),
          doubled: new Map(),
          dead: undefined,
        };
        machines.set(label, m);
      }
      m.recipes += 1;
      const controls = settingControls(recipe, node(recipe, handler.id, {}));
      const shown = controls.map((c) => `${c.id}:${c.label}`).join(" | ");
      m.cards.set(shown, (m.cards.get(shown) ?? 0) + 1);
      for (const key of sameLabels(controls)) m.doubled.set(key, m.doubled.get(key) ?? recipe.id);
      if (m.dead === undefined) m.dead = deadDatasetControls(recipe, handler, controls);
      const tableIds = new Set(
        getMachineTableControls(handler.machineType, recipe).map((c) => c.id),
      );
      for (const id of m.dead) {
        const unread = controls.find((c) => c.id === id);
        for (const table of controls.filter((c) => tableIds.has(c.id))) {
          const why = unread && sameMeaning(unread, table);
          if (why)
            m.doubled.set(
              `${id} + ${table.id}: unread, ${why}`,
              m.doubled.get(`${id} + ${table.id}: unread, ${why}`) ?? recipe.id,
            );
        }
      }
    }
  }
}

const rows = [...machines.values()].sort((a, b) => a.label.localeCompare(b.label));
const flagged = rows.filter((m) => m.doubled.size > 0 || (m.dead?.length ?? 0) > 0);
for (const m of flagged) {
  console.log(`${m.label} [${m.machineType}${m.table ? ", table" : ""}] (${m.recipes} recipes)`);
  for (const [shown, count] of m.cards) console.log(`  shows (${count}): ${shown}`);
  for (const [why, example] of m.doubled) console.log(`  DOUBLED ${why} (e.g. ${example})`);
  for (const id of m.dead ?? []) console.log(`  UNREAD dataset setting ${id} changes no number`);
}
const doubled = rows.filter((m) => m.doubled.size > 0);
console.log(`\ndataset ${path.basename(dir)}: ${recipes} recipes`);
console.log(
  `${rows.length} of ${rows.length} machines checked, ${doubled.length} with a doubled control` +
    (doubled.length ? `: ${doubled.map((m) => m.label).join(", ")}` : ""),
);
const isDouble = (m, id) => [...m.doubled.keys()].some((key) => key.startsWith(`${id} + `));
const unread = rows.filter((m) => (m.dead ?? []).some((id) => !isDouble(m, id)));
console.log(
  `${unread.length} other table machines show a dataset setting no formula reads` +
    (unread.length
      ? `: ${unread.map((m) => `${m.label} (${m.dead.filter((id) => !isDouble(m, id)).join(", ")})`).join(", ")}`
      : ""),
);
const out = arg("--json");
if (out) {
  fs.writeFileSync(
    out,
    JSON.stringify(
      rows.map((m) => ({
        label: m.label,
        machineType: m.machineType,
        table: m.table,
        recipes: m.recipes,
        cards: Object.fromEntries(m.cards),
        doubled: Object.fromEntries(m.doubled),
        unread: m.dead ?? [],
      })),
      null,
      1,
    ),
  );
}
