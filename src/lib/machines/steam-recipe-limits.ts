import type { Recipe } from "@/lib/model/types";

/**
 * The most EU/t a recipe may ask of a steam machine: V[1], LV. A steam
 * singleblock (MTEBasicMachineBronze.checkRecipe) looks recipes up with
 * `.voltage(TierEU.LV)`, and a steam multiblock (MTESteamMultiBlockBase)
 * sets its available voltage to V[getTierRecipes()], which is 1 for all of
 * them, and refuses a recipe above it in validateRecipe. Bronze and steel
 * builds share the cap.
 */
export const STEAM_MAX_RECIPE_EUT = 32;

/**
 * Whether a steam machine can run this recipe at all. The caller has already
 * decided the handler is a steam machine. Recipes the machine refuses are
 * not offered for it.
 */
export function steamMachineRunsRecipe(recipe: Partial<Pick<Recipe, "eut">>): boolean {
  return (recipe.eut ?? 0) <= STEAM_MAX_RECIPE_EUT;
}
