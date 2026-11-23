import type { DatasetVersion } from "./types";
import type { Recipe } from "@/lib/model/types";
import { isPowerRecipe } from "@/lib/power/power-recipe";
import { isCustomRateRecipe } from "@/lib/model/custom-rate";
import { FLUID_TANK_EMPTY_MAP } from "@/lib/model/fluid-tank";
import { CROP_FARM_PLACEHOLDER_RECIPE_ID } from "@/lib/model/passive-production";
import { recipeContentRef } from "@/lib/import-export/recipe-ref-match";
import { getRecipeDatasetRecipe, getResourceRenames, resolveRecipeDatasetRecipes } from "./browser-loader";
import { NO_RENAMES, withRenamedResources, type ResourceRenames } from "./resource-renames";
import { noteRestorableRecipes } from "./restorable-recipes";

/**
 * Which of a plan's recipes to fetch fresh from the dataset: each id once per
 * dataset version, and AGAIN when a recipe whose dataset body carries a
 * runtime table arrives without one. Synced and copied plans travel without
 * their GregTech tables (`withoutRuntimeTables`), and may bring recipes this
 * page already refreshed for another plan; keying on the id alone would leave
 * those bare, solving on fallback math.
 *
 * `checked` maps `version|id` to whether the dataset's body had a table;
 * `requested` holds recipe objects already sent, so none is fetched twice.
 */
export function recipesToRefresh(
  recipes: Recipe[],
  versionId: string,
  checked: Map<string, boolean>,
  requested: WeakSet<Recipe>,
): Recipe[] {
  return recipes.filter((recipe) => {
    if (requested.has(recipe)) {
      return false;
    }
    const key = `${versionId}|${recipe.id}`;
    return !checked.has(key) || (checked.get(key) === true && !recipe.runtimeCalculation);
  });
}

/** Marks `recipes` as sent, then what the dataset answered for them. */
export function noteRecipesRequested(
  recipes: Recipe[],
  versionId: string,
  checked: Map<string, boolean>,
  requested: WeakSet<Recipe>,
): void {
  for (const recipe of recipes) {
    requested.add(recipe);
    const key = `${versionId}|${recipe.id}`;
    if (!checked.has(key)) {
      checked.set(key, false);
    }
  }
}

export function noteRecipesRefreshed(
  refreshed: Recipe[],
  versionId: string,
  checked: Map<string, boolean>,
): void {
  noteRestorableRecipes(refreshed);
  for (const recipe of refreshed) {
    checked.set(`${versionId}|${recipe.id}`, Boolean(recipe.runtimeCalculation));
  }
}

/**
 * The dataset's current bodies for a plan's stored recipes.
 *
 * By id first. A stored id the dataset no longer has (a rebuild can re-key
 * recipes) is matched by what it takes and makes, the way import does, and
 * comes back with a `migration` entry from the stored id to the current one,
 * so old cards still pick up the dataset's current handlers. Power cards are
 * synthesized on the way in and are skipped. `missing` lists the stored ids
 * the dataset has no recipe for by either way; their cards keep the stored
 * body and say so. A stored recipe is matched as the game's item renames
 * (`renames`, the version's table) would write it, so a recipe that only
 * changed because an item moved to a new id is found; the caller moves the
 * plan's wires and drawers with `withRenamedProjectResources`.
 */
export async function resolveProjectRecipes(
  manifestUrl: string,
  version: DatasetVersion,
  recipes: Recipe[],
): Promise<{
  refreshed: Recipe[];
  migration: Record<string, string>;
  missing: string[];
  renames: ResourceRenames;
}> {
  // A demo recipe is the plan's own data: the dataset never has it, so asking only fills the console with 404s.
  // Custom rate cards, empty fluid tanks and the empty crop farm are the plan's own cards too.
  const asked = recipes.filter(
    (recipe) =>
      !recipe.isDemo &&
      !isPowerRecipe(recipe) &&
      !isCustomRateRecipe(recipe) &&
      recipe.source?.recipeMap !== FLUID_TANK_EMPTY_MAP &&
      recipe.id !== CROP_FARM_PLACEHOLDER_RECIPE_ID,
  );
  const byId = await Promise.allSettled(
    asked.map((recipe) => getRecipeDatasetRecipe(manifestUrl, version, recipe.id)),
  );
  const refreshed = byId
    .filter((result): result is PromiseFulfilledResult<Recipe> => result.status === "fulfilled")
    .map((result) => result.value);
  const stale = asked.filter(
    (recipe, index) => byId[index]?.status === "rejected" && !isPowerRecipe(recipe),
  );
  const migration: Record<string, string> = {};
  const missing: string[] = [];
  // Always read: a recipe found by its id can name an item the game has since moved (ids carry across versions), and
  // the plan's wires and slot picks must move with its body or they stop meeting it.
  const renames: ResourceRenames = asked.length > 0 ? await getResourceRenames(version) : NO_RENAMES;
  if (stale.length > 0) {
    try {
      const resolved = await resolveRecipeDatasetRecipes(
        manifestUrl,
        version,
        stale.map((recipe) => recipeContentRef(withRenamedResources(renames, recipe))),
      );
      // Not in this dataset: the id answered "not found" and nothing matches the content. A failed request says
      // nothing either way, so only a 404 counts.
      const matched = new Set(resolved.matches.map((match) => match.importedId));
      stale.forEach((recipe) => {
        const answer = byId[asked.indexOf(recipe)];
        if (!matched.has(recipe.id) && answer?.status === "rejected" && statusOf(answer.reason) === 404) {
          missing.push(recipe.id);
        }
      });
      const fetched = await Promise.allSettled(
        resolved.matches.map(async (match) => ({
          importedId: match.importedId,
          recipe: await getRecipeDatasetRecipe(manifestUrl, version, match.recipeId),
        })),
      );
      for (const result of fetched) {
        if (result.status === "fulfilled") {
          migration[result.value.importedId] = result.value.recipe.id;
          refreshed.push(result.value.recipe);
        }
      }
    } catch {
      // No match service: the stored bodies keep working as they are.
    }
  }
  return { refreshed, migration, missing, renames };
}

function statusOf(error: unknown): number | undefined {
  const status = (error as { status?: unknown } | undefined)?.status;
  return typeof status === "number" ? status : undefined;
}
