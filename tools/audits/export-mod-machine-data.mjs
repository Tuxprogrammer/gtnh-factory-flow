/**
 * The in-game mod's machine data per recipe map (jvm/core/core-machines/src/main/resources/assets/gtnhplanner/
 * machines/handlers.json, read by the core's HandlerData), built from a normalized dataset and this website's own
 * machine code. The mod has no dataset: this file is how a card in game gets the handlers, controls and machine
 * blocks the website's copy of the same recipe has.
 *
 *   npx tsx tools/audits/export-mod-machine-data.mjs --dataset <dir or version id> [--out <file>] [--base <file>]
 *                                                    [--report <file.json>]
 *
 * --dataset is a dataset directory holding recipes.json.gz, or a version id under public/datasets/gtnh (default: the
 * manifest's latest stable). --out defaults to the core's handlers.json, --base to the file being replaced.
 *
 * Per recipe map (a recipe's metadata.recipeMapId; crafting, smelting and the Extreme Entity Crusher by category):
 * - handlers: the pipeline's machine templates (machine-configs.mjs buildMachineHandlerTemplates, which
 *   instantiateRecipeMachineHandlers turns into each recipe's machineHandlers). The normalized dataset carries the
 *   instantiated handlers, not the templates, so each template is rebuilt from the family's own catalyst face
 *   (machineHandlerIcons, run through buildMachineHandlerTemplates) or else inverted from the recipes, and kept only
 *   when instantiating it reproduces every recipe's handler exactly.
 * - controls / coilFromSpecialValue: the recipes' machineConfigControls; on the blast furnace maps the coil's
 *   minimum follows the recipe's special value (heat), as machineConfigControlsForOracleRecipe sets it.
 * - app: getRecipeMachineHandlers on the map's first recipe, with the machine table entry it runs on (table).
 * - items: the machine blocks of each handler. The dataset keeps a family's face and one block per tier
 *   (machineHandlerIcons), not every catalyst the exporter saw (the Electric Ovens fold into the Electric Furnace),
 *   so a handler the base file already lists keeps its items; a new one takes its face and tier blocks.
 * - A map the dataset has no recipes for (the furnace mirror, the fuel maps) has nothing to rebuild from and is
 *   kept from the base file as it is; so are the single-machine templates the dataset carries no handler for, when
 *   the map's face does not rebuild them.
 * Every value kept from the base file is listed in the report, with every place a check failed.
 *
 * validated counts what the checks found: handlerMismatches (a recipe whose handlers the file's templates do not
 * reproduce, minimum tier aside), minimumTierMismatches, controlMismatches (recipe-level controls), pipelineMismatches
 * (a template the pipeline's instantiateRecipeMachineHandlers and HandlerData's reading of the file disagree on) and
 * itemProblems (a handler with no machine block). All are 0 in a good file.
 */
import fs from "node:fs";
import path from "node:path";
import readline from "node:readline";
import zlib from "node:zlib";
import { pathToFileURL } from "node:url";
import {
  VOLTAGE_TIER_NAMES,
  buildMachineHandlerTemplates,
  heatingCoilTiers,
  instantiateRecipeMachineHandlers,
} from "../dataset-pipeline/scripts/machine-configs.mjs";
import { getRecipeMachineHandlers } from "../../src/lib/model/recipe-rules";
import {
  getMachineBehaviour,
  machineTableNames,
  normalizeMachineName,
} from "../../src/lib/machines/machine-table";
import { isMultiblockRecipe } from "../../src/lib/solver/power";
import { withSynthesizedHandlerIcons } from "../../src/lib/server/dataset-query";

const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), "../..");
const DEFAULT_OUT = path.join(
  ROOT,
  "jvm/core/core-machines/src/main/resources/assets/gtnhplanner/machines/handlers.json",
);
/** normalize-oracle-export.mjs voltageTierForEu: a recipe's tier by its EU/t (no UMV; OpV above UXV). */
export const RECIPE_TIERS = [
  ["ULV", 8],
  ["LV", 32],
  ["MV", 128],
  ["HV", 512],
  ["EV", 2048],
  ["IV", 8192],
  ["LuV", 32768],
  ["ZPM", 131072],
  ["UV", 524288],
  ["UHV", 2097152],
  ["UEV", 8388608],
  ["UIV", 33554432],
  ["UXV", 134217728],
  ["OpV", 536870912],
];
/** Recipes outside GregTech's recipe maps that a card in game still reads by these ids (WebCards). */
const CATEGORY_MAPS = { furnace: "smelting", "kubatech-eec": "kubatech-eec" };

function args() {
  const out = {};
  const argv = process.argv.slice(2);
  for (let i = 0; i < argv.length; i++) {
    if (argv[i].startsWith("--"))
      out[argv[i].slice(2)] = argv[i + 1]?.startsWith("--") ? true : argv[++i];
  }
  return out;
}

function datasetFile(dataset) {
  const base = path.join(ROOT, "public/datasets/gtnh");
  let dir = dataset;
  if (!dir) {
    const manifest = JSON.parse(fs.readFileSync(path.join(base, "datasets.manifest.json"), "utf8"));
    const stable = (manifest.versions ?? []).filter((v) => v.status === "stable");
    dir = (stable.at(-1) ?? manifest.versions.at(-1)).id;
  }
  if (!fs.existsSync(dir)) dir = path.join(base, dir);
  const file = path.join(dir, "recipes.json.gz");
  if (!fs.existsSync(file)) throw new Error(`no recipes.json.gz in ${dir}`);
  return file;
}

function mapIdOf(recipe) {
  if (recipe.metadata?.recipeMapId) return recipe.metadata.recipeMapId;
  if (recipe.category === "crafting")
    return recipe.source?.recipeMap === "Shapeless Crafting"
      ? "crafting.shapeless"
      : "crafting.shaped";
  return CATEGORY_MAPS[recipe.category];
}

/** The dataset writer puts each array element of the top-level arrays on a line of its own. */
async function readDataset(file) {
  const rl = readline.createInterface({
    input: fs.createReadStream(file).pipe(zlib.createGunzip()),
    crlfDelay: Infinity,
  });
  const maps = new Map();
  const catalog = { resources: [], recipeMapIcons: [], machineHandlerIcons: [] };
  let key;
  let datasetVersionId;
  for await (const line of rl) {
    const top = /^ {2}"([A-Za-z]+)": (.*)$/.exec(line);
    if (top) {
      key = top[1];
      if (key === "datasetVersionId") datasetVersionId = JSON.parse(top[2].replace(/,$/, ""));
      continue;
    }
    if (!line.startsWith("    {")) continue;
    const value = JSON.parse(line.trim().replace(/,$/, ""));
    if (key === "recipes") {
      const id = mapIdOf(value);
      if (!id) continue;
      let map = maps.get(id);
      if (!map) {
        map = { id, name: value.source?.recipeMap ?? value.machineType, first: value, recipes: [] };
        maps.set(id, map);
      }
      map.recipes.push({
        minimumTier: value.minimumTier,
        durationTicks: value.durationTicks,
        eut: value.eut,
        specialValue: value.specialValue ?? value.metadata?.specialValue,
        machineHandlers: value.machineHandlers,
        machineConfigControls: value.machineConfigControls,
      });
    } else if (key in catalog) {
      catalog[key].push(value);
    }
  }
  return { datasetVersionId, maps, catalog };
}

/** JSON with keys sorted and undefined dropped: equal values, equal strings. */
function canon(value) {
  return JSON.stringify(value, (_k, v) =>
    v && typeof v === "object" && !Array.isArray(v)
      ? Object.fromEntries(
          Object.keys(v)
            .sort()
            .map((k) => [k, v[k]]),
        )
      : v,
  );
}
const tierIndex = (tier) =>
  VOLTAGE_TIER_NAMES.findIndex((t) => t.toLowerCase() === String(tier ?? "").toLowerCase());

/** The file's compact control: options name their block by icon id and name instead of a resource. */
function compactControl(control) {
  const out = {};
  for (const [k, v] of Object.entries(control)) {
    if (
      [
        "id",
        "label",
        "minimumKey",
        "defaultKey",
        "numeric",
        "minimumFromSpecialValue",
        "minimumHeatFromSpecialValue",
      ].includes(k)
    )
      out[k] = v;
  }
  out.tiers = (control.tiers ?? []).map((tier) => {
    const option = {};
    for (const [k, v] of Object.entries(tier)) {
      if (k === "resource" || k === "tooltip" || k === "description") continue;
      if (typeof v === "number" || k === "key" || k === "label") option[k] = v;
    }
    if (tier.resource) {
      option.icon = tier.resource.id;
      option.name = tier.resource.displayName;
    }
    return option;
  });
  return out;
}

/** Named controls: the first of an id is the id, later different ones id~2, id~3 in order of first use. */
class ControlRegistry {
  constructor() {
    this.byContent = new Map();
    this.counts = new Map();
    this.controls = {};
  }
  ref(control) {
    const compact = compactControl(control);
    const key = canon(compact);
    let ref = this.byContent.get(key);
    if (!ref) {
      const n = (this.counts.get(compact.id) ?? 0) + 1;
      this.counts.set(compact.id, n);
      ref = n === 1 ? compact.id : `${compact.id}~${n}`;
      this.byContent.set(key, ref);
      this.controls[ref] = compact;
    }
    return ref;
  }
}

/** HandlerData.recipeControls: the map's coil minimum follows the recipe's heat when coilFromSpecialValue is set. */
function coilFor(specialValue) {
  return heatingCoilTiers.find((tier) => tier.heat >= specialValue) ?? heatingCoilTiers.at(-1);
}

/**
 * The map's recipe-level controls: the same on every recipe, except that on the blast furnace maps the coil's
 * minimum is the first coil hot enough for the recipe (coilFromSpecialValue). Every recipe is checked in validate.
 */
function mapControls(map) {
  const withControls = map.recipes.filter((r) => r.machineConfigControls?.length);
  if (withControls.length === 0) return {};
  const first = heatingCoilTiers[0].key;
  const coilFromSpecialValue = withControls.some((r) =>
    r.machineConfigControls.some(
      (c) =>
        c.id === "heatingCoil" &&
        Number(r.specialValue) > 0 &&
        c.minimumKey !== first &&
        c.minimumKey === coilFor(Number(r.specialValue)).key,
    ),
  );
  const base = withControls[0].machineConfigControls.map((c) =>
    coilFromSpecialValue && c.id === "heatingCoil"
      ? { ...c, minimumKey: first, defaultKey: first }
      : c,
  );
  return { controls: base, coilFromSpecialValue };
}

/** The value of the given form closest to the simplest fraction in [lo, hi): what a tooltip's percentage gives. */
function simplestIn(lo, hi) {
  for (let q = 1; q <= 2000; q++) {
    const p = Math.ceil(lo * q - 1e-12);
    if (p / q >= lo && p / q < hi) return p / q;
  }
  return undefined;
}

function invertedTemplate(index, map, absolute) {
  const handlers = map.recipes.filter((r) => r.machineHandlers?.length);
  const first = handlers[0].machineHandlers[index];
  const t = {};
  let lo = 0,
    hi = Infinity,
    elo = 0,
    ehi = Infinity,
    fixedD = new Set(),
    fixedE = new Set(),
    anyD = false,
    anyE = false;
  for (const r of handlers) {
    const h = r.machineHandlers[index];
    if (h.durationTicks !== undefined) {
      anyD = true;
      fixedD.add(h.durationTicks);
      if (r.durationTicks > 0) {
        lo = Math.max(lo, (h.durationTicks - 0.5) / r.durationTicks);
        hi = Math.min(hi, (h.durationTicks + 0.5) / r.durationTicks);
      }
    }
    if (h.eut !== undefined) {
      anyE = true;
      fixedE.add(h.eut);
      if (r.eut > 0) {
        elo = Math.max(elo, (h.eut - 0.005) / r.eut);
        ehi = Math.min(ehi, (h.eut + 0.005) / r.eut);
      }
    }
    if (h.perfectOverclock) t.perfectOverclock = true;
  }
  // A handler's own fixed numbers (the electric furnace's 128 ticks at 4 EU/t on smelting) or a multiplier.
  if (anyD) {
    if (absolute && fixedD.size === 1) t.durationTicks = [...fixedD][0];
    else t.durationMultiplier = simplestIn(lo, hi);
  }
  if (anyE) {
    if (absolute && fixedE.size === 1) t.eut = [...fixedE][0];
    else t.eutMultiplier = simplestIn(elo, ehi);
  }
  // Own controls come first, then the recipe's not overridden: the shortest prefix that is the same on every recipe.
  if (index > 0 && first.machineConfigControls?.length) {
    const n = first.machineConfigControls.length;
    for (let k = 1; k <= n; k++) {
      const own = first.machineConfigControls.slice(0, k);
      const ids = new Set(own.map((c) => c.id));
      const ok = handlers.every((r) => {
        const got = r.machineConfigControls ?? [];
        const h = r.machineHandlers[index].machineConfigControls ?? [];
        return canon(h) === canon([...own, ...got.filter((c) => !ids.has(c.id))]);
      });
      if (ok) {
        t.machineConfigControls = own;
        break;
      }
    }
  }
  return t;
}

const STAT_KEYS = [
  "durationMultiplier",
  "eutMultiplier",
  "durationTicks",
  "eut",
  "perfectOverclock",
  "machineConfigControls",
];
const pickStats = (t) =>
  Object.fromEntries(STAT_KEYS.filter((k) => t?.[k] !== undefined).map((k) => [k, t[k]]));

/** How many of the map's recipes the template at `index` does not reproduce (the pipeline's own instantiation). */
function mismatchCount(map, index, template) {
  const dummy = { id: "-", label: "-", kind: "single" };
  const list = index === 0 ? [template, dummy] : [dummy, template];
  let bad = 0;
  for (const r of map.recipes) {
    if (!r.machineHandlers?.length) continue;
    const got = instantiateRecipeMachineHandlers(list, r)[index === 0 ? 0 : 1];
    if (canon(got) !== canon(r.machineHandlers[index])) bad++;
  }
  return bad;
}

/** The template's minimum tier: shown wherever it is above the recipe's; else the singleblock's lowest block. */
function templateMinimumTier(map, index, base, committed) {
  let found;
  let floor = Infinity;
  for (const r of map.recipes) {
    const h = r.machineHandlers?.[index];
    if (!h) continue;
    floor = Math.min(floor, tierIndex(r.minimumTier));
    if (tierIndex(h.minimumTier) > tierIndex(r.minimumTier)) {
      if (found === undefined || tierIndex(h.minimumTier) < tierIndex(found)) found = h.minimumTier;
    }
  }
  if (found) return { tier: found };
  if (base.availableTiers?.length) return { tier: base.availableTiers[0] };
  // Every recipe is at or above it, so the recipes cannot tell it; the base file's value is kept if it fits.
  if (committed?.minimumTier && tierIndex(committed.minimumTier) <= floor)
    return { tier: committed.minimumTier, kept: true };
  return {};
}

function templatesFromRecipes(map, ctx, committedMap) {
  const withHandlers = map.recipes.filter((r) => r.machineHandlers?.length);
  const order = withHandlers[0].machineHandlers.map((h) => h.id);
  for (const r of withHandlers) {
    if (canon(r.machineHandlers.map((h) => h.id)) !== canon(order)) {
      ctx.report.problems.push(`${map.id}: recipes list different handlers`);
      break;
    }
  }
  const committed = new Map((committedMap?.handlers ?? []).map((t) => [t.id, t]));
  return order.map((id, index) => {
    const h = withHandlers[0].machineHandlers[index];
    const base = {
      id,
      label: h.label,
      kind: h.kind,
      isPrimary: index === 0,
      ...(h.maximumTier ? { maximumTier: h.maximumTier } : {}),
      ...(h.availableTiers ? { availableTiers: h.availableTiers } : {}),
    };
    const min = templateMinimumTier(map, index, base, committed.get(id));
    if (min.tier) base.minimumTier = min.tier;
    if (min.kept) ctx.report.kept.push(`${map.id} ${id}: minimumTier ${min.tier}`);
    const candidates = [];
    const face = ctx.handlerIcons.get(id)?.resource;
    if (face) {
      const built = buildMachineHandlerTemplates(map.name, [
        { resource: face, multiblock: h.kind === "multiblock" },
      ]);
      if (built.length === 1) candidates.push(["face", pickStats(built[0])]);
    }
    candidates.push(["recipes", invertedTemplate(index, map, false)]);
    candidates.push(["recipes, fixed numbers", invertedTemplate(index, map, true)]);
    let chosen;
    for (const [how, stats] of candidates) {
      const template = { ...base, ...stats };
      if (mismatchCount(map, index, template) === 0) {
        chosen = template;
        ctx.report.templateSources[how] = (ctx.report.templateSources[how] ?? 0) + 1;
        break;
      }
    }
    if (!chosen) {
      chosen = { ...base, ...candidates.at(-1)[1] };
      ctx.report.problems.push(
        `${map.id} ${id}: no template reproduces every recipe (${mismatchCount(map, index, chosen)} differ)`,
      );
    }
    return chosen;
  });
}

/**
 * A map whose recipes carry no handlers: the pipeline's one template for it came from the map's catalysts, which the
 * dataset does not keep. The base file's is kept (or its absence: a map face alone, as the cauldron's, is no
 * catalyst); a map new to the file has its template rebuilt from the map's face.
 */
function singleTemplate(map, ctx, committedMap) {
  if (committedMap) {
    const kept = committedMap.handlers ?? [];
    if (kept.length)
      ctx.report.kept.push(
        `${map.id}: single-machine template ${kept.map((k) => k.id).join(", ")}`,
      );
    return kept.map((k) => ({ ...k, isPrimary: k.primary, keptFromBase: true }));
  }
  // Only GregTech's own maps have catalysts; crafting, the Tank and the crusher's drops have no machine template.
  const face =
    map.first.category === "gregtech" && map.id !== "planner.tank"
      ? ctx.recipeMapIcons.get(map.name)
      : undefined;
  if (!face) return [];
  const built = buildMachineHandlerTemplates(map.name, [{ resource: face }]);
  if (built.length !== 1) return [];
  const t = built[0];
  ctx.report.problems.push(
    `${map.id}: new single-machine template ${t.id} (${t.kind}) rebuilt from the map's face; check its kind`,
  );
  return [
    {
      id: t.id,
      label: t.label,
      kind: t.kind,
      isPrimary: true,
      ...(t.minimumTier ? { minimumTier: t.minimumTier } : {}),
      ...pickStats(t),
    },
  ];
}

/** The machine blocks of a handler: the base file's when it lists the handler, else the dataset's face and tiers. */
function handlerItems(mapId, id, ctx, committedMap, fallbackFace) {
  const kept = committedMap?.handlers?.find((t) => t.id === id)?.items;
  const entry = ctx.handlerIcons.get(id);
  const derived = entry
    ? [...new Set([entry.resource.id, ...(entry.tiers ?? []).map((t) => t.resource.id)])].sort()
    : fallbackFace
      ? [fallbackFace.id]
      : [];
  if (kept) {
    const stats = ctx.report.items;
    const same = canon(kept) === canon(derived);
    const subset = derived.every((i) => kept.includes(i));
    stats[same ? "same" : subset ? "datasetSubset" : "differ"]++;
    if (!same && !subset)
      ctx.report.itemNotes.push(
        `${mapId} ${id}: base ${kept.join(" ")} / dataset ${derived.join(" ")}`,
      );
    return kept;
  }
  ctx.report.items.fromDataset++;
  return derived;
}

/** The machine table entry a handler runs on (the app handler's table), or undefined. */
export function tableName(machineType) {
  const behaviour = getMachineBehaviour(machineType);
  if (!behaviour) return undefined;
  // Entries can share one behaviour; the entry named for this machine (or listing it as an alias) is the one.
  const names = machineTableNames().filter((name) => getMachineBehaviour(name) === behaviour);
  const own = normalizeMachineName(machineType);
  return (
    names.find((name) => normalizeMachineName(name) === own) ??
    names.find((name) =>
      (getMachineBehaviour(name).aliases ?? []).some((a) => normalizeMachineName(a) === own),
    ) ??
    names[0]
  );
}

function appHandlers(map, templates, ctx) {
  // A machine only crafting and smelting stand in for (the Auto Workbench) has a face only if it is the map's machine.
  const realMachine =
    (map.first.category === "gregtech" && map.id !== "planner.tank") || map.id === "kubatech-eec";
  const face = realMachine ? ctx.recipeMapIcons.get(map.name) : undefined;
  const datasetIds = new Set((map.first.machineHandlers ?? []).map((h) => h.id));
  return getRecipeMachineHandlers(map.first).map((h) => {
    const own = templates.find((t) => t.id === h.id);
    const icons = ctx.handlerIcons.get(h.id);
    const items = own
      ? own.items
      : icons
        ? [...new Set([icons.resource.id, ...(icons.tiers ?? []).map((t) => t.resource.id)])].sort()
        : templates.length === 1
          ? templates[0].items
          : face && h.machineType === map.name
            ? [face.id]
            : [];
    const table = tableName(h.machineType);
    return {
      id: h.id,
      label: h.label,
      machineType: h.machineType,
      kind: h.kind ?? "single",
      // Numbers only for a machine the website makes up (the Auto Workbench's 64 ticks at 32 EU/t): a dataset
      // handler's numbers are the first recipe's, not the machine's.
      ...(!datasetIds.has(h.id) && h.durationTicks !== undefined
        ? { durationTicks: h.durationTicks }
        : {}),
      ...(!datasetIds.has(h.id) && h.eut !== undefined ? { eut: h.eut } : {}),
      ...(table ? { table } : {}),
      multiblock: appMultiblock(h),
      items,
    };
  });
}

/** Whether the website runs this handler as a multiblock (its kind, or the machine table's entry for it). */
export function appMultiblock(handler) {
  return (
    handler.kind === "multiblock" ||
    isMultiblockRecipe({ machineType: handler.machineType, machineHandlers: [] })
  );
}

/** The file's fixed tables, from the pipeline: voltage tiers, recipe tiers by EU/t, and the heating coils. */
export function fixedTables() {
  return {
    tiers: VOLTAGE_TIER_NAMES,
    recipeTiers: RECIPE_TIERS,
    coils: heatingCoilTiers.map(({ key, label, heat }) => ({ key, label, heat })),
  };
}

function fileTemplate(t, registry) {
  return {
    id: t.id,
    label: t.label,
    kind: t.kind,
    ...(t.isPrimary ? { primary: true } : {}),
    ...(t.minimumTier ? { minimumTier: t.minimumTier } : {}),
    ...(t.maximumTier ? { maximumTier: t.maximumTier } : {}),
    ...(t.availableTiers ? { availableTiers: t.availableTiers } : {}),
    ...(t.perfectOverclock ? { perfectOverclock: true } : {}),
    ...(Number.isFinite(t.durationMultiplier) && t.durationMultiplier !== 1
      ? { durationMultiplier: t.durationMultiplier }
      : {}),
    ...(Number.isFinite(t.eutMultiplier) && t.eutMultiplier !== 1
      ? { eutMultiplier: t.eutMultiplier }
      : {}),
    ...(Number.isFinite(t.durationTicks) ? { durationTicks: t.durationTicks } : {}),
    ...(Number.isFinite(t.eut) ? { eut: t.eut } : {}),
    ...(t.keptFromBase && t.controls ? { controls: t.controls } : {}),
    ...(!t.keptFromBase && !t.isPrimary && t.machineConfigControls?.length
      ? { controls: t.machineConfigControls.map((c) => registry.ref(c)) }
      : {}),
    items: t.items,
  };
}

async function main() {
  const opts = args();
  const file = datasetFile(opts.dataset);
  const out = opts.out ? path.resolve(opts.out) : DEFAULT_OUT;
  const basePath = opts.base ? path.resolve(opts.base) : DEFAULT_OUT;
  const base = fs.existsSync(basePath)
    ? JSON.parse(fs.readFileSync(basePath, "utf8"))
    : { maps: [], controls: {} };
  const baseMaps = new Map(base.maps.map((m) => [m.id, m]));
  const { datasetVersionId, maps, catalog } = await readDataset(file);
  const report = {
    kept: [],
    problems: [],
    itemNotes: [],
    templateSources: {},
    items: { same: 0, datasetSubset: 0, differ: 0, fromDataset: 0 },
  };
  const ctx = {
    report,
    handlerIcons: new Map(withSynthesizedHandlerIcons(catalog).map((e) => [e.familyId, e])),
    recipeMapIcons: new Map(catalog.recipeMapIcons.map((e) => [e.recipeMap, e.resource])),
  };
  const registry = new ControlRegistry();
  // Controls the base file names and a kept map still uses: registered first under the base file's own names.
  const entries = [];
  const ids = [...new Set([...maps.keys(), ...baseMaps.keys()])].sort();
  let recipeCount = 0;
  for (const id of ids) {
    const map = maps.get(id);
    const committedMap = baseMaps.get(id);
    if (!map) {
      report.kept.push(
        `${id}: whole map (${committedMap.recipes} recipes in the base file, none in the dataset)`,
      );
      entries.push({ kept: committedMap });
      continue;
    }
    recipeCount += map.recipes.length;
    const handlersOnRecipes = map.recipes.some((r) => r.machineHandlers?.length);
    const templates = handlersOnRecipes
      ? templatesFromRecipes(map, ctx, committedMap)
      : singleTemplate(map, ctx, committedMap);
    const face = templates.length === 1 ? ctx.recipeMapIcons.get(map.name) : undefined;
    for (const t of templates) t.items = handlerItems(id, t.id, ctx, committedMap, face);
    const { controls, coilFromSpecialValue } = mapControls(map);
    entries.push({ map, handlersOnRecipes, templates, controls, coilFromSpecialValue });
  }
  // Compact names in file order: each map's templates' controls, then the map's own.
  const outMaps = [];
  for (const e of entries) {
    if (e.kept) {
      const m = structuredClone(e.kept);
      const rename = (ref) => registry.ref(expand(base.controls[ref]));
      for (const t of m.handlers ?? []) if (t.controls) t.controls = t.controls.map(rename);
      if (m.controls) m.controls = m.controls.map(rename);
      outMaps.push(m);
      continue;
    }
    const handlers = e.templates.map((t) =>
      t.keptFromBase && t.controls
        ? { ...t, controls: t.controls.map((ref) => registry.ref(expand(base.controls[ref]))) }
        : t,
    );
    const fileHandlers = handlers.map((t) => fileTemplate(t, registry));
    const entry = {
      id: e.map.id,
      name: e.map.name,
      recipes: e.map.recipes.length,
      handlersOnRecipes: e.handlersOnRecipes,
      ...(fileHandlers.length ? { handlers: fileHandlers } : {}),
      ...(e.controls?.length ? { controls: e.controls.map((c) => registry.ref(c)) } : {}),
      ...(e.coilFromSpecialValue ? { coilFromSpecialValue: true } : {}),
      app: appHandlers(
        e.map,
        handlers.map((t) => ({ ...t })),
        ctx,
      ),
    };
    for (const t of fileHandlers)
      if (!t.items.length) report.problems.push(`${entry.id} ${t.id}: no machine block`);
    outMaps.push(entry);
  }
  const validated = validate(outMaps, registry.controls, maps, report);
  const result = {
    format: 1,
    dataset: datasetVersionId,
    generatedAt: new Date().toISOString(),
    ...fixedTables(),
    controls: registry.controls,
    maps: outMaps,
    validated: { recipes: recipeCount, ...validated },
  };
  fs.writeFileSync(out, JSON.stringify(result));
  if (opts.report) fs.writeFileSync(path.resolve(opts.report), JSON.stringify(report, null, 1));
  console.log(
    JSON.stringify(
      {
        out,
        dataset: datasetVersionId,
        maps: outMaps.length,
        validated: result.validated,
        templateSources: report.templateSources,
        items: report.items,
        kept: report.kept.length,
        problems: report.problems,
      },
      null,
      1,
    ),
  );
  if (Object.values(validated).some((n) => n > 0)) process.exitCode = 1;
}

/** A compact control back to the dataset's shape, enough for compactControl to give it back unchanged. */
function expand(compact) {
  const { tiers, ...rest } = compact;
  return {
    ...rest,
    tiers: tiers.map(({ icon, name, ...option }) => ({
      ...option,
      ...(icon !== undefined || name !== undefined
        ? { resource: { id: icon, displayName: name } }
        : {}),
    })),
  };
}

/**
 * HandlerData's reading of the file, recipe by recipe, against the dataset's own handlers and controls, and against
 * the pipeline's instantiation of the same templates.
 */
function validate(outMaps, controls, maps, report) {
  const counts = {
    handlerMismatches: 0,
    controlMismatches: 0,
    pipelineMismatches: 0,
    minimumTierMismatches: 0,
    itemProblems: 0,
  };
  const control = (ref) => expand(controls[ref]);
  for (const entry of outMaps) {
    for (const t of entry.handlers ?? []) if (!t.items?.length) counts.itemProblems++;
    const map = maps.get(entry.id);
    if (!map) continue;
    const before = { ...counts };
    for (const r of map.recipes) {
      const recipeControls = (entry.controls ?? []).map(control).map((c) => {
        if (c.id !== "heatingCoil" || !entry.coilFromSpecialValue || !(Number(r.specialValue) > 0))
          return c;
        const coil = coilFor(Number(r.specialValue));
        return { ...c, minimumKey: coil.key, defaultKey: coil.key };
      });
      if (
        canon(recipeControls.map(compactControl)) !==
        canon((r.machineConfigControls ?? []).map(compactControl))
      )
        counts.controlMismatches++;
      if (!entry.handlersOnRecipes || !r.machineHandlers?.length) continue;
      const ours = javaHandlers(entry.handlers, r, recipeControls, control);
      const pipeline = instantiateRecipeMachineHandlers(
        entry.handlers.map((t) => ({
          ...t,
          isPrimary: t.primary,
          machineConfigControls: t.controls?.map(control),
        })),
        { ...r, machineConfigControls: recipeControls },
      );
      const strip = (list, keepTier) =>
        canon(
          (list ?? []).map((h) => ({
            ...h,
            minimumTier: keepTier ? h.minimumTier : undefined,
            machineConfigControls: h.machineConfigControls?.map(compactControl),
          })),
        );
      if (strip(ours, false) !== strip(r.machineHandlers, false)) counts.handlerMismatches++;
      else if (strip(ours, true) !== strip(r.machineHandlers, true)) counts.minimumTierMismatches++;
      if (strip(ours, true) !== strip(pipeline, true)) counts.pipelineMismatches++;
    }
    for (const k of Object.keys(counts))
      if (counts[k] > before[k]) report.problems.push(`${entry.id}: ${counts[k] - before[k]} ${k}`);
  }
  return counts;
}

/** HandlerData.handlers in JavaScript: what the core builds from the file for this recipe. */
function javaHandlers(templates, recipe, recipeControls, control) {
  const recipeTier = tierIndex(recipe.minimumTier);
  return templates.map((t) => {
    const index = Math.max(tierIndex(t.minimumTier), recipeTier);
    const h = { id: t.id, label: t.label, kind: t.kind, machineType: t.label };
    h.minimumTier = index >= 0 ? VOLTAGE_TIER_NAMES[index] : recipe.minimumTier;
    if (t.maximumTier) h.maximumTier = t.maximumTier;
    if (t.availableTiers) h.availableTiers = t.availableTiers;
    if (t.durationMultiplier !== undefined)
      h.durationTicks = Math.max(1, Math.round(recipe.durationTicks * t.durationMultiplier));
    if (t.eutMultiplier !== undefined)
      h.eut = Math.max(0, Math.round(recipe.eut * t.eutMultiplier * 100) / 100);
    if (t.durationTicks !== undefined) h.durationTicks = Math.max(1, Math.round(t.durationTicks));
    if (t.eut !== undefined) h.eut = Math.max(0, t.eut);
    if (t.perfectOverclock) h.perfectOverclock = true;
    if (t.controls) {
      const own = t.controls.map(control);
      const ids = new Set(own.map((c) => c.id));
      h.machineConfigControls = [...own, ...recipeControls.filter((c) => !ids.has(c.id))];
    }
    return h;
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href)
  await main();
