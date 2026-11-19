// A board card's machine maths in one call: through the compiled core (core-machines, MachinesExports.machinesCard)
// when the "machines" area is on and the core is loaded, else the website's TypeScript. Both give the same numbers
// (machines-shadow.test.ts holds them equal on the machine goldens and on the inputs of the website's machine tests).
//
// The recipe crosses once: the core keeps it under its id until a different recipe object comes with the same id. A
// node crosses as JSON on every call, and the answer comes back as JSON in the website's own shapes, so nothing here
// re-derives a number. `cardMachineMath` keeps one readout per card (see the key below), so the card, its glance face,
// its tips and the Overview rail share one computation.
import type { CoreExports } from "./exports.generated";
import { coreFor, isAdopted } from "./adopt";
import { loadCore } from "./loader";
import type { FactoryNode, MachineHandler, Recipe } from "@/lib/model/types";
import { applyMachineHandlerToRecipe, getSelectedMachineHandler } from "@/lib/model/recipe-rules";
import { applyRecipeInputOverrides } from "@/lib/model/recipe-input-overrides";
import { getGameRates, type ActiveGameRates } from "@/lib/model/game-rates";
import {
  getCropsNhStats,
  isBeeProductionRecipe,
  isCropProductionRecipe,
  isIndustrialApiaryMachineType,
} from "@/lib/model/passive-production";
import { getOverclockedRecipeStats, type OverclockedRecipeStats } from "@/lib/solver/overclock";
import {
  applyMachineOutputMultipliers,
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

// ---------------------------------------------------------------- the card's cached readout
//
// Every board consumer of a card's machine figures (the glance face, the card, its tips, the shared card's sections,
// the Overview rail's machine list) asks `cardMachineMath`, so a card's figures are computed once and handed to all of
// them, also across the remounts virtualization makes.
//
// What a result depends on, and so the key: the recipe object (a dataset's recipes are never changed in place; a new
// dataset or a refreshed plan brings new objects), the node's content apart from where it sits and its solve pin
// (`nodeContentKey`; nothing in the machine maths reads either), and the pack's game rates (`getGameRates`, the only
// module state the maths read). A new game-rates object (setActiveGameRates, which runs on every dataset set and
// clear) drops everything. Display dials (rate unit, power display) are not inputs: they repaint numbers only.

const UNSET = Symbol("unset");

/** The node's own data apart from where it sits and its solve pin, as text; one string per node object. */
const nodeKeys = new WeakMap<FactoryNode, string>();
export function nodeContentKey(node: FactoryNode): string {
  let key = nodeKeys.get(node);
  if (key === undefined) {
    key = JSON.stringify(node, (name, value) =>
      name === "position" || name === "solvePin" ? undefined : value,
    );
    nodeKeys.set(node, key);
  }
  return key;
}

const counts = {
  readouts: 0,
  nodeRecipe: 0,
  stats: 0,
  power: 0,
  steam: 0,
  parallel: 0,
  outputs: 0,
  kept: 0,
  core: 0,
};

/** How many of each figure the TypeScript (or the core) computed since the last reset: for tests and probes. */
export function cardMachineMathCounts(): Readonly<typeof counts> {
  return { ...counts };
}
export function resetCardMachineMathCounts(): void {
  for (const key of Object.keys(counts) as Array<keyof typeof counts>) counts[key] = 0;
}

/**
 * One card's machine figures for a recipe on a node, each computed on first read and kept. The figures are those of
 * the recipe as the node takes it (`nodeRecipe`, its inputs overridden), as every card consumer has always computed
 * them. A figure whose maths throw is not kept: it throws again on the next read, as the call it replaces did.
 */
export class CardReadout implements CardMachineMath {
  private _handler: MachineHandler | typeof UNSET = UNSET;
  private _nodeRecipe: Recipe | typeof UNSET = UNSET;
  private _effective: Recipe | typeof UNSET = UNSET;
  private _core: CardMachineMath | null | typeof UNSET = UNSET;
  private _stats: OverclockedRecipeStats | typeof UNSET = UNSET;
  private _power: NodePowerReport | undefined | typeof UNSET = UNSET;
  private _steam: NodeSteamReport | undefined | typeof UNSET = UNSET;
  private _parallel: number | typeof UNSET = UNSET;
  private _structural: number | typeof UNSET = UNSET;
  private _outputs: number[] | typeof UNSET = UNSET;
  private readonly kept = new Map<unknown, unknown>();

  constructor(
    readonly recipe: Recipe,
    /** The first node object of this content: every node the readout is handed for has the same machine data. */
    readonly node: FactoryNode,
  ) {
    counts.readouts++;
  }

  /** getSelectedMachineHandler(recipe, node). */
  get handler(): MachineHandler {
    if (this._handler === UNSET) this._handler = getSelectedMachineHandler(this.recipe, this.node);
    return this._handler;
  }

  /** applyRecipeInputOverrides(recipe, node): the recipe as this node takes it. */
  get nodeRecipe(): Recipe {
    if (this._nodeRecipe === UNSET) {
      counts.nodeRecipe++;
      this._nodeRecipe = applyRecipeInputOverrides(this.recipe, this.node);
    }
    return this._nodeRecipe;
  }

  /** applyMachineHandlerToRecipe(nodeRecipe, node): the machine's recipe. */
  get effectiveRecipe(): Recipe {
    if (this._effective === UNSET)
      this._effective = applyMachineHandlerToRecipe(this.nodeRecipe, this.node);
    return this._effective;
  }

  /**
   * The core's figures, when the area is on, the core has loaded, it covers the machine and the node takes the recipe
   * as it is (the core holds the dataset's recipe, not one with this node's inputs overridden); else null.
   */
  private core(): CardMachineMath | null {
    if (this._core !== UNSET) return this._core;
    const c = coreForMachines();
    let fromCore: CardMachineMath | null = null;
    if (c && this.nodeRecipe === this.recipe) {
      try {
        fromCore = cardMachineMathCore(c, this.recipe, this.node);
        if (fromCore) counts.core++;
      } catch {
        // A recipe the core cannot read is still a card: the TypeScript answers it (the shadow test reports such inputs).
        fromCore = null;
      }
    }
    // Decided once per readout, so its figures all come from one engine (the two agree: machines-shadow.test.ts).
    this._core = fromCore;
    return fromCore;
  }

  /** getOverclockedRecipeStats(nodeRecipe, node). */
  get stats(): OverclockedRecipeStats {
    if (this._stats === UNSET) {
      const core = this.core();
      if (!core) counts.stats++;
      this._stats = core ? core.stats : getOverclockedRecipeStats(this.nodeRecipe, this.node);
    }
    return this._stats;
  }

  /** getNodePowerReport(nodeRecipe, node) when hasPowerReport(nodeRecipe, node), else undefined. */
  get powerReport(): NodePowerReport | undefined {
    if (this._power === UNSET) {
      const core = this.core();
      if (!core) counts.power++;
      this._power = core
        ? core.powerReport
        : hasPowerReport(this.nodeRecipe, this.node)
          ? getNodePowerReport(this.nodeRecipe, this.node)
          : undefined;
    }
    return this._power;
  }

  /** getNodeSteamReport(nodeRecipe, node). */
  get steamReport(): NodeSteamReport | undefined {
    if (this._steam === UNSET) {
      const core = this.core();
      if (!core) counts.steam++;
      this._steam = core ? core.steamReport : getNodeSteamReport(this.nodeRecipe, this.node);
    }
    return this._steam;
  }

  /** getMachineParallelMultiplier(effectiveRecipe, node). */
  get parallel(): number {
    if (this._parallel === UNSET) {
      const core = this.core();
      if (!core) counts.parallel++;
      this._parallel = core
        ? core.parallel
        : getMachineParallelMultiplier(this.effectiveRecipe, this.node);
    }
    return this._parallel;
  }

  /** getMachineStructuralParallels(effectiveRecipe, node). */
  get structuralParallel(): number {
    if (this._structural === UNSET) {
      const core = this.core();
      this._structural = core
        ? core.structuralParallel
        : getMachineStructuralParallels(this.effectiveRecipe, this.node);
    }
    return this._structural;
  }

  /** getMachineOutputMultiplier for each output of effectiveRecipe, at the overclocked tier. */
  get outputMultipliers(): number[] {
    if (this._outputs === UNSET) {
      const core = this.core();
      if (!core) counts.outputs++;
      this._outputs = core
        ? core.outputMultipliers
        : this.effectiveRecipe.outputs.map((output) =>
            getMachineOutputMultiplier(this.effectiveRecipe, this.node, output, this.stats.tier),
          );
    }
    return this._outputs;
  }

  /**
   * A value a consumer derives from this card's recipe and node (the face's tier ladder, the card's derived state),
   * kept beside the figures under `slot` so it is computed once per key as they are. `compute` must read nothing but
   * this readout, its recipe and its node.
   */
  keep<T>(slot: object, compute: (readout: CardReadout) => T): T {
    if (this.kept.has(slot)) return this.kept.get(slot) as T;
    counts.kept++;
    const value = compute(this);
    this.kept.set(slot, value);
    return value;
  }
}

/**
 * The recipe with the machine's output multipliers applied, as applyMachineOutputMultipliers(recipe, node, tier at the
 * overclock) gives it: from the readout's multipliers when `recipe` is its machine's recipe, else computed.
 */
export function applyCardOutputMultipliers(recipe: Recipe, readout: CardReadout): Recipe {
  if (recipe !== readout.effectiveRecipe) {
    return applyMachineOutputMultipliers(recipe, readout.node, readout.stats.tier);
  }
  const multipliers = readout.outputMultipliers;
  const outputs = recipe.outputs.map((output, index) => {
    const multiplier = multipliers[index]!;
    return multiplier === 1 ? output : { ...output, amount: output.amount * multiplier };
  });
  return outputs.some((output, index) => output !== recipe.outputs[index])
    ? { ...recipe, outputs }
    : recipe;
}

/**
 * Readouts per recipe object, then per card id: the last two contents of each card (a deferred render, the Overview
 * rail's, can still ask for the content before an edit while the card asks for the one after it), so the cache holds
 * one or two entries per card however long the session runs.
 */
type Slots = Map<string, Array<{ key: string; readout: CardReadout }>>;
let cache = new WeakMap<Recipe, Slots>();
let cacheRates: ActiveGameRates = getGameRates();

/** Drops every kept readout. */
export function clearCardMachineMath(): void {
  cache = new WeakMap();
  cacheRates = getGameRates();
}

/**
 * A card's machine figures for this recipe on this node, computed once per (recipe object, node content, game rates)
 * and shared by every consumer: through the core when the area is on and the core has loaded, else (or when the core
 * does not cover the machine) the TypeScript.
 */
export function cardMachineMath(recipe: Recipe, node: FactoryNode): CardReadout {
  if (getGameRates() !== cacheRates) clearCardMachineMath();
  const key = nodeContentKey(node);
  let byCard = cache.get(recipe);
  if (!byCard) cache.set(recipe, (byCard = new Map()));
  const slots = byCard.get(node.id);
  if (slots) {
    if (slots[0]!.key === key) return slots[0]!.readout;
    if (slots[1]?.key === key) {
      slots.reverse();
      return slots[0]!.readout;
    }
  }
  const readout = new CardReadout(recipe, node);
  byCard.set(node.id, slots ? [{ key, readout }, slots[0]!] : [{ key, readout }]);
  return readout;
}
