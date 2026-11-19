// A board card's machine figures, computed once per card and shared by every consumer (the glance face, the card, its
// tips, a shared card's sections, the Overview rail's machine list), with the website's TypeScript as the engine. Plan
// code: no loader, no browser. The compiled core plugs in through `setMachinesBridge` (src/lib/core/machines.ts
// installs it on import), and answers a card's figures only when the machines area is on and the core has loaded.
import type { FactoryNode, MachineHandler, Recipe } from "@/lib/model/types";
import { applyMachineHandlerToRecipe, getSelectedMachineHandler } from "@/lib/model/recipe-rules";
import { applyRecipeInputOverrides } from "@/lib/model/recipe-input-overrides";
import { getGameRates, type ActiveGameRates } from "@/lib/model/game-rates";
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

/** What the core's side provides: a card's figures for the recipe as it is, or null for the TypeScript to answer. */
export interface MachinesBridge {
  figures(recipe: Recipe, node: FactoryNode): CardMachineMath | null;
}
let bridge: MachinesBridge | undefined;
/** Installs the core's side (src/lib/core/machines.ts does on import); undefined takes it out. */
export function setMachinesBridge(next: MachinesBridge | undefined): void {
  bridge = next;
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
    let fromCore: CardMachineMath | null = null;
    if (bridge && this.nodeRecipe === this.recipe) {
      try {
        fromCore = bridge.figures(this.recipe, this.node);
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
