import type { MachineHandler, Recipe } from "@/lib/model/types";

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
 * The steam multiblocks whose structure has no input hatch, so a recipe with
 * a fluid input never reaches them: their hatch elements are the steam hatch
 * (locked to steam), steam input and output buses, and for the Steam
 * Separator (MTESteamCentrifuge) an output hatch. The Steam Squasher, Steam
 * Presser, Steam Purifier and Steam Blender take an input hatch.
 */
const STEAM_MULTIBLOCKS_WITHOUT_INPUT_HATCH = new Set([
  "Steam Grinder",
  "Steam Separator",
  "Steam Fuser",
  "Steam Hearth",
]);

/**
 * Whether a steam machine can run this recipe at all. The caller has already
 * decided the handler is a steam machine. Recipes the machine refuses are
 * not offered for it. A steam singleblock finds recipes from its items alone
 * (MTEBasicMachineBronze.checkRecipe passes no fluids), so it never runs one
 * with a fluid input.
 */
export function steamMachineRunsRecipe(
  handler: Pick<MachineHandler, "kind" | "machineType">,
  recipe: Partial<Pick<Recipe, "eut" | "inputs">>,
): boolean {
  if ((recipe.eut ?? 0) > STEAM_MAX_RECIPE_EUT) return false;
  if (!(recipe.inputs ?? []).some((input) => input.kind === "fluid")) return true;
  return (
    handler.kind === "multiblock" && !STEAM_MULTIBLOCKS_WITHOUT_INPUT_HATCH.has(handler.machineType)
  );
}
