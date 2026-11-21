import type { MachineHandler } from "@/lib/model/types";
import type { RecipeSummary } from "@/lib/datasets/types";
import { machinesOf, recipeHeat, recipeMapOf } from "./format";
import type { AgentConstraints } from "./schemas";

/**
 * What a recipe search does with the constraints object.
 *
 * Filters: `tierCap` (recipes whose minimum tier is above it are left out, by the site's own tier index), `heat`
 * (a recipe on a heat machine whose special value, its heat in K, is above it is left out), `banned` (ids or names,
 * matched without case against the recipe id, its recipe map, its machine type and each machine that can run it; a
 * recipe is left out when the recipe, its map or every machine is banned, and a banned machine is never the one shown).
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
}

export function bannedSet(constraints: AgentConstraints | undefined): Set<string> {
  return new Set((constraints?.banned ?? []).map(key));
}

function handlerBanned(handler: MachineHandler, banned: Set<string>): boolean {
  return banned.has(key(handler.id)) || banned.has(key(handler.label)) || banned.has(key(handler.machineType));
}

/** The machine a search shows for this recipe under the constraints, or undefined when the recipe is filtered out. */
export function checkRecipe(
  recipe: RecipeSummary,
  constraints: AgentConstraints | undefined,
  banned: Set<string>,
): MachineHandler | undefined {
  const handlers = machinesOf(recipe);
  if (constraints?.heat !== undefined) {
    const heat = recipeHeat(recipe);
    if (heat !== undefined && heat > constraints.heat) {
      return undefined;
    }
  }
  if (banned.size === 0) {
    return handlers[0];
  }
  if (banned.has(key(recipe.id)) || banned.has(key(recipeMapOf(recipe)))) {
    return undefined;
  }
  return handlers.find((handler) => !handlerBanned(handler, banned));
}
