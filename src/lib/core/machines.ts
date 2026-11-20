// A board card's machine maths in one call: through the compiled core (core-machines, MachinesExports.machinesCard)
// when the "machines" area is on and the core is loaded, else the website's TypeScript. Both give the same numbers
// (machines-shadow.test.ts holds them equal on the machine goldens and on the inputs of the website's machine tests).
//
// The recipe crosses once: the core keeps it under its id until a different recipe object comes with the same id. A
// node crosses as JSON on every call, and the answer comes back as JSON in the website's own shapes, so nothing here
// re-derives a number. The per-card readout every consumer asks (`cardMachineMath`) is plan code in
// src/lib/machines/card-readout.ts; importing this file installs the core behind it (`setMachinesBridge`).
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
 */
export function websiteOnly(recipe: Recipe): boolean {
  return (
    isCropProductionRecipe(recipe) ||
    isBeeProductionRecipe(recipe) ||
    getCropsNhStats(recipe) !== undefined ||
    isIndustrialApiaryMachineType(recipe.machineType)
  );
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
  /** The recipe object kept under each id in this core instance. */
  byId: Map<string, Recipe>;
  /** Each control table's text sent to this core instance, and the name it is kept under there. */
  controls: Map<string, string>;
}
const kept = new WeakMap<CoreExports, Kept>();

/**
 * Hands the recipe to the core unless this very object is already kept under its id. Its control tables (most of a
 * recipe's text, and the same few on most of a plan's recipes) cross once each: a table the core already keeps goes
 * as {"$ref": name}, which the core reads as that very text (MachineReadout.addRecipe).
 */
export function keepRecipe(c: CoreExports, recipe: Recipe): void {
  let k = kept.get(c);
  if (!k) kept.set(c, (k = { byId: new Map(), controls: new Map() }));
  if (k.byId.get(recipe.id) === recipe) return;
  const known = k;
  const text = JSON.stringify(recipe, (key, value: unknown) =>
    key === "machineConfigControls" && Array.isArray(value)
      ? value.map((control: unknown) => {
          const table = JSON.stringify(control);
          let name = known.controls.get(table);
          if (name === undefined) {
            name = `c${known.controls.size}`;
            c.MachinesExports.machinesAddControl(name, table);
            known.controls.set(table, name);
          }
          return { $ref: name };
        })
      : value,
  );
  c.MachinesExports.machinesAddRecipe(recipe.id, text);
  k.byId.set(recipe.id, recipe);
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
 * The core for the machines area (adopt.ts: off by default): null while the area is off or the core is loading, and
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

setMachinesBridge({
  figures(recipe, node) {
    const c = coreForMachines();
    return c ? cardMachineMathCore(c, recipe, node) : null;
  },
});
