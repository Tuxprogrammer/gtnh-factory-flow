/**
 * The power sources through the compiled core, behind the power adoption flag (src/lib/core/adopt.ts). Plan code may not
 * import the loader, so this file holds only the seam: the core's side (src/lib/core/power-adopt.ts) installs itself with
 * `setPowerBridge`, and until it has, or while the core is not loaded, every function answers undefined and the caller
 * runs the TypeScript in this folder, which stays the path until the core is everywhere the site runs.
 */
import type { TurbineFlowCheck } from "./turbine-flow";
import {
  buildPowerSettingsReader,
  type PowerModel,
  type PowerSourceDefinition,
  type PowerStatLine,
} from "./types";

/** A card's model as the core gives it, in the shapes the card and the recipe synthesis already use. */
export interface CorePowerCard {
  model: PowerModel;
  /** Per flow, whether the workbook name is a game resource the recipe can wire (inputs, then outputs). */
  resolved: { inputs: boolean[]; outputs: boolean[] };
  /** The stats plus a line for each flow the map cannot wire: what a power recipe's `power.stats` holds. */
  recipeStats: PowerStatLine[];
  /** The tier the card's EU counts in under the Amps dial, or undefined for a card that makes no EU. */
  cardTier: string | undefined;
  /** Whole turbines sharing the flow, whether the card can show a flow check, and the check itself. */
  turbines: number;
  may: boolean;
  check: TurbineFlowCheck | undefined;
}

/** What the core's side provides. Each answers undefined when the area is off, the core is not loaded or the call fails. */
export interface PowerBridge {
  card(
    sourceId: string,
    settings: Record<string, string> | undefined,
    running: number,
    pin: number | undefined,
  ): CorePowerCard | undefined;
  tier(euPerTick: number): string | null | undefined;
}

let bridge: PowerBridge | null = null;

export function setPowerBridge(next: PowerBridge | null): void {
  bridge = next;
}

/**
 * One power card through the core: its source at the stored `settings`, and, for a turbine card the solve gave
 * `running` turbines' worth (and `pin` whole turbines when its count is Exactly), the flow check.
 */
export function corePowerCard(
  sourceId: string,
  settings: Record<string, string> | undefined,
  running = 0,
  pin?: number,
): CorePowerCard | undefined {
  return bridge?.card(sourceId, settings, running, pin);
}

/** The Amps dial's tier for an EU/t figure through the core: a name, null for none, undefined when the TypeScript answers. */
export function corePowerCardTier(euPerTick: number): string | null | undefined {
  return bridge?.tier(euPerTick);
}

/** A source's model at stored settings: the core's when the power area is adopted and loaded, else the TypeScript's. */
export function computePowerModel(
  source: PowerSourceDefinition,
  settings: Record<string, string> | undefined,
): PowerModel {
  return (
    corePowerCard(source.id, settings)?.model ??
    source.compute(buildPowerSettingsReader(source, settings))
  );
}
