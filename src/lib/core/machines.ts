// A board card's machine maths in one call: through the compiled core (core-machines, MachinesExports.machinesCard)
// when the "machines" area is on and the core is loaded, else the website's TypeScript. Both give the same numbers
// (machines-shadow.test.ts holds them equal on the machine goldens and on the inputs of the website's machine tests).
//
// A recipe crosses once per content and stays resident in the core under its id (keepRecipe); a card's call passes the
// id and the node as JSON, and the answer comes back as JSON in the website's own shapes, so nothing here re-derives a
// number. Recipes cross in idle time: a card whose recipe is not resident yet takes the TypeScript's figures. The
// per-card readout every consumer asks (`cardMachineMath`) is plan code in src/lib/machines/card-readout.ts; importing
// this file installs the core behind it (`setMachinesBridge`).
import type { CoreExports } from "./exports.generated";
import { coreFor, isAdopted } from "./adopt";
import { loadCore } from "./loader";
import type { FactoryNode, Recipe } from "@/lib/model/types";
import { applyMachineHandlerToRecipe } from "@/lib/model/recipe-rules";
import { getGameRates, type ActiveGameRates } from "@/lib/model/game-rates";
import {
  getCropsNhStats,
  isBeeProductionRecipe,
  isCropProductionRecipe,
  isIndustrialApiaryMachineType,
} from "@/lib/model/passive-production";
import { getOverclockedRecipeStats, type OverclockedRecipeStats } from "@/lib/solver/overclock";
import {
  getMachineOutputMultiplier,
  getMachineParallelMultiplier,
  getMachineStructuralParallels,
} from "@/lib/solver/machine-effects";
import {
  getNodePowerReport,
  getNodeSteamReport,
  hasPowerReport,
  type NodePowerReport,
  type NodeSteamReport,
} from "@/lib/solver/power-report";
import { setMachinesBridge, type CardMachineMath } from "@/lib/machines/card-readout";

export type { CardMachineMath } from "@/lib/machines/card-readout";
export {
  CardReadout,
  applyCardOutputMultipliers,
  cardMachineMath,
  cardMachineMathCounts,
  clearCardMachineMath,
  nodeContentKey,
  resetCardMachineMathCounts,
} from "@/lib/machines/card-readout";

/** The TypeScript path: the same calls the cards make one by one. */
export function cardMachineMathTs(recipe: Recipe, node: FactoryNode): CardMachineMath {
  const effective = applyMachineHandlerToRecipe(recipe, node);
  const stats = getOverclockedRecipeStats(recipe, node);
  const powerReport = hasPowerReport(recipe, node) ? getNodePowerReport(recipe, node) : undefined;
  const steamReport = getNodeSteamReport(recipe, node);
  return {
    stats,
    ...(powerReport ? { powerReport } : {}),
    ...(steamReport ? { steamReport } : {}),
    parallel: getMachineParallelMultiplier(effective, node),
    structuralParallel: getMachineStructuralParallels(effective, node),
    outputMultipliers: effective.outputs.map((output) =>
      getMachineOutputMultiplier(effective, node, output, stats.tier),
    ),
  };
}

/**
 * Crops and bees: the card maths for them are the passive-production models' (the core judges bees and crops through
 * BeeReadout and CropReadout, not the machine table). The core's generic readout leaves the same cards out
 * (NodeMath.covers, PassiveRecipes; the shadow test holds the two to it), so the facade does not send them at all.
 * Decided once per recipe object (it reads the recipe alone, and a dataset's recipes are never changed in place).
 */
const websiteOnlyByRecipe = new WeakMap<Recipe, boolean>();
export function websiteOnly(recipe: Recipe): boolean {
  let only = websiteOnlyByRecipe.get(recipe);
  if (only === undefined) {
    only =
      isCropProductionRecipe(recipe) ||
      isBeeProductionRecipe(recipe) ||
      getCropsNhStats(recipe) !== undefined ||
      isIndustrialApiaryMachineType(recipe.machineType);
    websiteOnlyByRecipe.set(recipe, only);
  }
  return only;
}

/** Non-finite numbers cross as the words the goldens use. */
function revive(_key: string, value: unknown): unknown {
  return value === "Infinity"
    ? Infinity
    : value === "-Infinity"
      ? -Infinity
      : value === "NaN"
        ? NaN
        : value;
}

interface Kept {
  /** Per recipe id: the recipe object last kept under it in this core instance, and the text that crossed for it. */
  byId: Map<string, { recipe: Recipe; text: string }>;
  /** Each control table's text sent to this core instance, and the name it is kept under there. */
  controls: Map<string, string>;
  /** Each handler's text (its tables by reference) sent to this core instance, and the name it is kept under there. */
  handlers: Map<string, string>;
}
const kept = new WeakMap<CoreExports, Kept>();

/**
 * The keys of a port (an input, an output, a runtime variant's output) the core reads (core-machines WebJson.resource,
 * which reads no other): a port's icon, colour, mod, slot and alternatives never reach the maths, and are most of its
 * text. A key WebJson.resource starts reading goes here too.
 */
const PORT_KEYS = [
  "kind",
  "id",
  "amount",
  "chance",
  "consumed",
  "optional",
  "displayName",
  "tooltip",
] as const;
function portForCore(port: unknown): unknown {
  if (!port || typeof port !== "object" || Array.isArray(port)) return port;
  const from = port as Record<string, unknown>;
  const out: Record<string, unknown> = {};
  for (const key of PORT_KEYS) if (key in from) out[key] = from[key];
  return out;
}

/**
 * Hands the recipe to the core once per content: the core keeps it under its id, and later calls name only the id. A
 * new object with the same id (a refreshed plan, the same plan opened again) is compared by the text it would send, and
 * crosses only when that differs. Its control tables and handlers (most of a recipe's text, and the same few on most of
 * a map's recipes) cross once each: one the core already keeps goes as {"$ref": name}, which the core reads as that
 * very table or handler (MachineReadout.addRecipe). Ports cross with only the keys the core reads (PORT_KEYS). Each
 * rewrite applies only where the core's recipe reader looks for it (the recipe, its handlers, its runtime variants),
 * never inside the recipe's metadata or power maps, which the core reads whole.
 */
export function keepRecipe(c: CoreExports, recipe: Recipe): void {
  let k = kept.get(c);
  if (!k) kept.set(c, (k = { byId: new Map(), controls: new Map(), handlers: new Map() }));
  const before = k.byId.get(recipe.id);
  if (before?.recipe === recipe) return;
  const known = k;
  const controlRef = (control: unknown) => {
    const table = JSON.stringify(control);
    let name = known.controls.get(table);
    if (name === undefined) {
      name = `c${known.controls.size}`;
      c.MachinesExports.machinesAddControl(name, table);
      known.controls.set(table, name);
    }
    return { $ref: name };
  };
  const handlerRef = (handler: unknown) => {
    if (!handler || typeof handler !== "object" || Array.isArray(handler)) return handler;
    const table = JSON.stringify(handler, function (this: unknown, key: string, value: unknown) {
      return key === "machineConfigControls" && this === handler && Array.isArray(value)
        ? value.map(controlRef)
        : value;
    });
    let name = known.handlers.get(table);
    if (name === undefined) {
      name = `h${known.handlers.size}`;
      c.MachinesExports.machinesAddHandler(name, table);
      known.handlers.set(table, name);
    }
    return { $ref: name };
  };
  const portOwners = new Set<unknown>([recipe, ...(recipe.runtimeCalculation?.variants ?? [])]);
  const text = JSON.stringify(recipe, function (this: unknown, key: string, value: unknown) {
    if (!Array.isArray(value)) return value;
    if (this === recipe && key === "machineConfigControls") return value.map(controlRef);
    if (this === recipe && key === "machineHandlers") return value.map(handlerRef);
    if ((key === "inputs" || key === "outputs") && portOwners.has(this))
      return value.map(portForCore);
    return value;
  });
  if (before?.text !== text) c.MachinesExports.machinesAddRecipe(recipe.id, text);
  k.byId.set(recipe.id, { recipe, text });
}

/** The game-rates record last handed to each core instance: the core reads the pack's rates from its own copy. */
const ratesSent = new WeakMap<CoreExports, ActiveGameRates>();
function sendRates(c: CoreExports): void {
  const rates = getGameRates();
  if (ratesSent.get(c) === rates) return;
  c.MachinesExports.machinesSetRates(
    rates.beesYield,
    rates.ic2CropsYield,
    rates.recipeDurationDivisor,
  );
  ratesSent.set(c, rates);
}

/** The core path, or null where the core leaves the machine to the website (crops, bees, the TGS, the Vat). */
export function cardMachineMathCore(
  c: CoreExports,
  recipe: Recipe,
  node: FactoryNode,
): CardMachineMath | null {
  if (websiteOnly(recipe)) return null;
  sendRates(c);
  keepRecipe(c, recipe);
  const text = c.MachinesExports.machinesCard(recipe.id, JSON.stringify(node));
  if (text == null) return null;
  const out = JSON.parse(text, revive) as {
    stats: OverclockedRecipeStats;
    power?: NodePowerReport;
    steam?: NodeSteamReport;
    parallel: number;
    structuralParallel: number;
    outputMultipliers: number[];
  };
  return {
    stats: out.stats,
    ...(out.power ? { powerReport: out.power } : {}),
    ...(out.steam ? { steamReport: out.steam } : {}),
    parallel: out.parallel,
    structuralParallel: out.structuralParallel,
    outputMultipliers: out.outputMultipliers,
  };
}

/**
 * The core for the machines area (adopt.ts): null while the area is off or the core is loading, and
 * then the cards use the TypeScript. The load the area starts is followed by a warm-up in idle time.
 */
let warmPending = false;
function coreForMachines(): CoreExports | null {
  const c = coreFor("machines");
  if (c) warmSoon(c);
  else if (!warmPending && isAdopted("machines")) {
    warmPending = true;
    // loadCore shares the load coreFor started: this only waits for it.
    loadCore().then(warmSoon, () => undefined);
  }
  return c;
}

/**
 * The core's first cards pay for its tables and its cold code (in the browser on WebAssembly several times what the
 * cards cost warm). Once it has loaded, it runs the maths over a recipe of every map in idle time, so a plan's cards
 * do not. Exported for the probes, which load the core themselves.
 */
const warmed = new WeakSet<CoreExports>();
export function warmSoon(c: CoreExports): void {
  if (warmed.has(c)) return;
  warmed.add(c);
  const run = () => {
    try {
      c.MachinesExports.machinesWarm(1000);
    } catch {
      // Warming is only a head start: a failure here leaves the first cards to pay, nothing else.
    }
  };
  const idle = (globalThis as { requestIdleCallback?: (cb: () => void) => void })
    .requestIdleCallback;
  if (idle) idle(run);
  else setTimeout(run, 0);
}

/** Whether this very recipe object is the one the core keeps under its id (keepRecipe has sent it). */
export function isResident(c: CoreExports, recipe: Recipe): boolean {
  return kept.get(c)?.byId.get(recipe.id)?.recipe === recipe;
}

/**
 * Recipes the cards asked for before the core kept them, per core instance, by id (the latest object of each id).
 * Sending a recipe costs more than computing its card, and a plan's first paint asks for every recipe at once, so a
 * card whose recipe is not resident yet takes the TypeScript's figures (the same numbers) and its recipe crosses in
 * idle time; the cards computed after that (an edit, a new card, the next plan with these recipes) use the core.
 */
const waiting = new WeakMap<CoreExports, Map<string, Recipe>>();
const crossing = new WeakSet<CoreExports>();

/** Sends waiting recipes until `budgetMs` has passed (each recipe whole); answers whether any are left. */
export function crossWaitingRecipes(c: CoreExports, budgetMs = Infinity): boolean {
  const queue = waiting.get(c);
  if (!queue) return false;
  const until = performance.now() + budgetMs;
  for (const [id, recipe] of queue) {
    queue.delete(id);
    try {
      keepRecipe(c, recipe);
    } catch {
      // A recipe the core cannot read stays with the TypeScript, as the readout does for a throwing call.
    }
    if (performance.now() >= until) break;
  }
  return queue.size > 0;
}

type IdleDeadline = { timeRemaining(): number };
function crossSoon(c: CoreExports): void {
  if (crossing.has(c)) return;
  crossing.add(c);
  const idle = (globalThis as { requestIdleCallback?: (cb: (d: IdleDeadline) => void) => void })
    .requestIdleCallback;
  const run = (deadline?: IdleDeadline) => {
    // An idle period's time, or a short slice where the engine has no idle callback (Safari, Node).
    const more = crossWaitingRecipes(c, deadline ? Math.max(1, deadline.timeRemaining() - 1) : 8);
    if (!more) crossing.delete(c);
    else if (idle) idle(run);
    else setTimeout(run, 0);
  };
  if (idle) idle(run);
  else setTimeout(run, 0);
}

setMachinesBridge({
  figures(recipe, node) {
    const c = coreForMachines();
    if (!c || websiteOnly(recipe)) return null;
    if (!isResident(c, recipe)) {
      let queue = waiting.get(c);
      if (!queue) waiting.set(c, (queue = new Map()));
      queue.set(recipe.id, recipe);
      crossSoon(c);
      return null;
    }
    return cardMachineMathCore(c, recipe, node);
  },
});
