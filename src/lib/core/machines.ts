// A board card's machine maths in one call: through the compiled core (core-machines, MachinesExports.machinesCard)
// when the "machines" area is on and the core is loaded, else the website's TypeScript. Both give the same numbers
// (machines-shadow.test.ts holds them equal on the machine goldens and on the inputs of the website's machine tests).
//
// The recipe crosses once: the core keeps it under its id until a different recipe object comes with the same id. A
// node crosses as JSON on every call, and the answer comes back as JSON in the website's own shapes, so nothing here
// re-derives a number. Results are kept per (recipe, node) object, the same key the cards' memos use, so the card,
// its glance face and its tips share one computation.
import type { CoreExports } from "./exports.generated";
import { coreIfLoaded } from "./loader";
import type { FactoryNode, Recipe } from "@/lib/model/types";
import { applyMachineHandlerToRecipe } from "@/lib/model/recipe-rules";
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

export interface CardMachineMath {
  /** getOverclockedRecipeStats(recipe, node). */
  stats: OverclockedRecipeStats;
  /** getNodePowerReport(recipe, node), when hasPowerReport(recipe, node). */
  powerReport?: NodePowerReport;
  /** getNodeSteamReport(recipe, node). */
  steamReport?: NodeSteamReport;
  /** getMachineParallelMultiplier and getMachineStructuralParallels on the machine's recipe. */
  parallel: number;
  structuralParallel: number;
  /** getMachineOutputMultiplier for each output of the machine's recipe, at the overclocked tier. */
  outputMultipliers: number[];
}

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
 * Crops and bees: the card maths for them are the website's own (the core judges bees and crops through BeeReadout and
 * CropReadout, not the machine table), so the core is never asked.
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
}
const kept = new WeakMap<CoreExports, Kept>();

/** Hands the recipe to the core unless this very object is already kept under its id. */
export function keepRecipe(c: CoreExports, recipe: Recipe): void {
  let k = kept.get(c);
  if (!k) kept.set(c, (k = { byId: new Map() }));
  if (k.byId.get(recipe.id) === recipe) return;
  c.MachinesExports.machinesAddRecipe(recipe.id, JSON.stringify(recipe));
  k.byId.set(recipe.id, recipe);
}

/** The core path, or null where the core leaves the machine to the website (crops, bees, the TGS, the Vat). */
export function cardMachineMathCore(
  c: CoreExports,
  recipe: Recipe,
  node: FactoryNode,
): CardMachineMath | null {
  if (websiteOnly(recipe)) return null;
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
 * Stand-in for adopt.ts's `coreFor("machines")` (the web stream's file, agreed, not on this base yet): the area is off,
 * so the TypeScript answers. When adopt.ts lands this becomes `coreFor("machines")` and the flag lives there.
 */
const MACHINES_ADOPTED = false;
function coreFor(_area: "machines"): CoreExports | null {
  return MACHINES_ADOPTED ? coreIfLoaded() : null;
}

const cache = new WeakMap<Recipe, WeakMap<FactoryNode, CardMachineMath>>();

/**
 * A card's machine maths for this recipe on this node: through the core when the area is on and the core has loaded,
 * else (or when the core does not cover the machine) the TypeScript. Kept per (recipe, node) object.
 */
export function cardMachineMath(recipe: Recipe, node: FactoryNode): CardMachineMath {
  let byNode = cache.get(recipe);
  const hit = byNode?.get(node);
  if (hit) return hit;
  const c = coreFor("machines");
  let fromCore: CardMachineMath | null = null;
  if (c) {
    try {
      fromCore = cardMachineMathCore(c, recipe, node);
    } catch {
      // A recipe the core cannot read is still a card: the TypeScript answers it (the shadow test reports such inputs).
      fromCore = null;
    }
  }
  const math = fromCore ?? cardMachineMathTs(recipe, node);
  if (!byNode) cache.set(recipe, (byNode = new WeakMap()));
  byNode.set(node, math);
  return math;
}
