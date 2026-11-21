import type { MachineHandler } from "@/lib/model/types";
import type { RecipeSummary } from "@/lib/datasets/types";
import { machinesOf, recipeHeat, recipeMapOf } from "./format";
import { steamAllowed, unpoweredKind } from "./power-source";
import type { AgentConstraints } from "./schemas";

/**
 * What a recipe search does with the constraints object.
 *
 * Filters: `tierCap` (recipes whose minimum tier is above it are left out, by the site's own tier index), `heat`
 * (a recipe on a heat machine whose special value, its heat in K, is above it is left out), `banned` (ids or names,
 * matched without case against the recipe id, its recipe map, its machine type and each machine that can run it; a
 * recipe is left out when the recipe, its map or every machine is banned, and a banned machine is never the one shown),
 * `steam` (unless it is true or `tierCap` is ULV, a steam or primitive machine is never the one shown, and a recipe
 * only they run is left out: `checkRecipe` says `steamOnly` so the search can count it).
 *
 * Not enforced: `cleanroom` and `lowgrav` (the dataset carries no cleanroom or low-gravity flag on any recipe, so
 * nothing can be filtered on them) and `free` (it means "treat as raw" to expand and solve, not a search filter).
 * A search that was given any of these says so in `notEnforced`.
 */
export const SEARCH_NOT_ENFORCED = ["cleanroom", "lowgrav", "free"] as const;

export function notEnforced(constraints: AgentConstraints | undefined): string[] | undefined {
  if (!constraints) {
    return undefined;
  }
  const given = SEARCH_NOT_ENFORCED.filter((key) => constraints[key] !== undefined);
  return given.length ? [...given] : undefined;
}

function key(value: string): string {
  return value.trim().toLowerCase();
}

export interface ConstraintCheck {
  /** The machine to show for the recipe: the first that is not banned. Undefined when the recipe is out. */
  machine?: MachineHandler;
  /** The recipe is out because every machine left to run it burns steam or is primitive. */
  steamOnly?: boolean;
}

export function bannedSet(constraints: AgentConstraints | undefined): Set<string> {
  return new Set((constraints?.banned ?? []).map(key));
}

export function handlerBanned(handler: MachineHandler, banned: Set<string>): boolean {
  return banned.has(key(handler.id)) || banned.has(key(handler.label)) || banned.has(key(handler.machineType));
}

/** The machine a search shows for this recipe under the constraints; no machine when the recipe is filtered out. */
export function checkRecipe(
  recipe: RecipeSummary,
  constraints: AgentConstraints | undefined,
  banned: Set<string>,
): ConstraintCheck {
  if (constraints?.heat !== undefined) {
    const heat = recipeHeat(recipe);
    if (heat !== undefined && heat > constraints.heat) {
      return {};
    }
  }
  if (banned.has(key(recipe.id)) || banned.has(key(recipeMapOf(recipe)))) {
    return {};
  }
  const open = machinesOf(recipe).filter((handler) => !handlerBanned(handler, banned));
  if (steamAllowed(constraints)) {
    return open[0] ? { machine: open[0] } : {};
  }
  const machine = open.find((handler) => !unpoweredKind(handler, recipe));
  return machine ? { machine } : open.length ? { steamOnly: true } : {};
}
