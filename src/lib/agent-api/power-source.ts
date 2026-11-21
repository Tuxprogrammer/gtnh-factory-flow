import { getNodeSteamReport } from "@/lib/solver/power-report";
import { isSteamMachineHandler } from "@/lib/model/recipe-rules";
import type { FactoryNode, MachineHandler, Recipe } from "@/lib/model/types";
import type { AgentConstraints } from "./schemas";

/**
 * Which machines run on no electricity: steam machines and primitive (brick) machines. A player past the steam age
 * does not want them as a card's default machine or as a candidate, so the agent API leaves them out unless the
 * constraints ask for them (`steam: true`, or `tierCap: "ULV"`).
 *
 * Steam is the site's own rule, `isSteamMachineHandler` (every "Steam ..." and "High Pressure ..." machine), the one
 * the cards use to zero EU and bill litres instead (`getNodeSteamReport`).
 *
 * Primitive machines carry no flag in the dataset: their recipes read ULV and 0 EU, as generators, crafting grids,
 * the Neutron Activator and the Extreme Heat Exchanger do too, and their tier ("NONE" on some) says nothing either
 * (the electric Multi Smelter reads NONE). So they are named here. `power-source.local.test.ts` checks every
 * zero-EU machine of the pack against this list and its list of the others, so a new one fails there, not silently.
 */
export const PRIMITIVE_MACHINE_TYPES: readonly string[] = [
  "bricked blast furnace",
  "primitive blast furnace",
  // Railcraft's brick oven. The Industrial Coke Oven's map had this name in older datasets; it draws EU.
  "coke oven",
];

export type PowerSource = "steam" | "primitive";

function key(value: string | undefined): string {
  return (value ?? "").trim().toLowerCase();
}

/** "steam", "primitive", or undefined for a machine that runs on EU (or needs no power at all and is not a machine). */
export function unpoweredKind(handler: Pick<MachineHandler, "label" | "machineType">, recipe: Pick<Recipe, "eut">): PowerSource | undefined {
  if (isSteamMachineHandler(handler)) {
    return "steam";
  }
  const named = PRIMITIVE_MACHINE_TYPES.includes(key(handler.machineType)) || PRIMITIVE_MACHINE_TYPES.includes(key(handler.label));
  return named && !(recipe.eut > 0) ? "primitive" : undefined;
}

/** Whether the constraints let steam and primitive machines be chosen without being named: `steam: true` or a ULV cap. */
export function steamAllowed(constraints: AgentConstraints | undefined): boolean {
  return constraints?.steam === true || constraints?.tierCap === "ULV";
}

/** Litres of steam per EU, to compare a steam machine with an electric one (GregTech's rate: 2 L of steam is 1 EU). */
export const STEAM_LITRES_PER_EU = 2;

/** Litres of steam a tick one machine of this card burns, by the site's card math; 0 when it burns none. */
export function steamPerTick(recipe: Recipe, node: FactoryNode): number {
  return getNodeSteamReport(recipe, node)?.drawSteamPerTick ?? 0;
}
