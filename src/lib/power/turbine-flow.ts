/**
 * What a turbine card really makes at the flow the solve feeds it. A turbine takes the fuel in its hatch each tick (up
 * to its cap) and loses efficiency the further that flow is from optimal (fluidIntoPower in MTEXLTurbine* and
 * MTELargeTurbine*), so its EU is not proportional to its flow. The solve bills a card linearly: x0.575 of a turbine set
 * to optimal flow counts 0.575 of its optimal EU, but one turbine fed that steadily makes about 0.575 x 0.575 of it.
 * This works out the real figure for the cards to show; the solve and its totals stay linear.
 */
import type { Recipe } from "@/lib/model/types";
import { getPowerSource } from "./registry";
import { isPowerRecipe } from "./power-recipe";
import { buildPowerSettingsReader, type PowerSourceDefinition } from "./types";

export interface TurbineFlowCheck {
  /** Whole turbines sharing the card's flow. */
  turbines: number;
  /** The flow each one gets, in the card's own unit. */
  flow: number;
  /** The flow the card is set to (its optimal, or a Custom flow), per turbine. */
  setFlow: number;
  /** The rotor's optimal flow for this fuel, per turbine. */
  optimal: number;
  unit: "L/t" | "L/s";
  /** EU/t all the turbines really make at `flow`. */
  euMade: number;
  /** EU/t the plan counts for the card. */
  euCounted: number;
}

function hasFlowSetting(source: PowerSourceDefinition): boolean {
  return source.settings.some((setting) => setting.id === "flowMode");
}

/** Whole turbines a card's flow is shared by: an Exactly card's count, else what runs rounded up. */
export function turbinesFor(running: number, exactPin?: number): number {
  return exactPin !== undefined && exactPin > 0 ? exactPin : Math.max(1, Math.ceil(running - 1e-9));
}

/** The cheap test the card shell's layout key uses: a turbine card whose turbines do not each run whole. */
export function mayTurbineFlow(
  recipe: Pick<Recipe, "power" | "category"> | undefined,
  running: number,
  turbines: number,
): boolean {
  if (!isPowerRecipe(recipe) || !(running > 0) || Math.abs(running - turbines) <= 1e-6)
    return false;
  const source = getPowerSource(recipe.power.sourceId);
  return source !== undefined && hasFlowSetting(source);
}

/**
 * The check for a turbine card running `running` turbines' worth on `turbines` whole turbines, or nothing when each
 * turbine gets the flow the card is set to (or the card is not a turbine, or runs none).
 */
export function turbineFlowCheck(
  recipe: Pick<Recipe, "power" | "category"> | undefined,
  settings: Record<string, string> | undefined,
  running: number,
  turbines: number,
): TurbineFlowCheck | undefined {
  if (!isPowerRecipe(recipe) || !(running > 0) || !(turbines >= 1)) return undefined;
  const source = getPowerSource(recipe.power.sourceId);
  if (!source || !hasFlowSetting(source)) return undefined;
  const at = (values: Record<string, string> | undefined) =>
    source.compute(buildPowerSettingsReader(source, values));
  // Plasma flows are litres a second on the card, steam and gas litres a tick (turbines.ts).
  const unit = source.settings.some(
    (setting) => setting.id === "customFlow" && setting.type === "number" && setting.unit === "L/s",
  )
    ? "L/s"
    : "L/t";
  const perUnit = (perSecond: number) => (unit === "L/s" ? perSecond : perSecond / 20);
  const set = at(settings);
  const setFlow = perUnit(set.inputs[0]?.perSecond ?? 0);
  if (!(setFlow > 0)) return undefined;
  // The game moves whole litres a tick; a steady feed averages out to the nearest.
  const flow = Math.max(1, Math.round((setFlow * running) / turbines));
  if (flow === setFlow) return undefined;
  const optimal = perUnit(at({ ...settings, flowMode: "optimal" }).inputs[0]?.perSecond ?? 0);
  const each = at({ ...settings, flowMode: "custom", customFlow: String(flow) }).euPerTick;
  const euMade = each * turbines;
  const euCounted = set.euPerTick * running;
  if (Math.abs(euMade - euCounted) < 1) return undefined;
  return { turbines, flow, setFlow, optimal, unit, euMade, euCounted };
}
