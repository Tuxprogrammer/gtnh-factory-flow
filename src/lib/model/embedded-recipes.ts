import { listNodeRecipeIds } from "./shared-machine";
import type { FactoryProject } from "./types";

/**
 * A plan embeds each recipe some card runs (any section), once. A recipe no
 * card names is read by nothing (every lookup starts from a card), so one
 * left behind by a removed section, a deleted card or a swapped recipe is
 * dead weight in every save, code and export; a second entry under the same
 * id makes lookups disagree on which body is the recipe (`find` takes the
 * first, a Map the last). The first entry for an id is kept.
 *
 * Run by the load funnel (`normalizeLoadedProjectWithNotice`) and the edit
 * funnel (`touchProject`), so the plan in memory, and so every save, never
 * holds one. Undo restores whole snapshots, which keep their own recipes.
 * Returns the same object when there is nothing to drop.
 */
export function keepUsedRecipes(project: FactoryProject): FactoryProject {
  const used = new Set<string>();
  for (const node of project.nodes) {
    for (const id of listNodeRecipeIds(node)) used.add(id);
  }
  const kept = new Set<string>();
  const recipes = project.recipes.filter((recipe) => {
    if (!used.has(recipe.id) || kept.has(recipe.id)) return false;
    kept.add(recipe.id);
    return true;
  });
  return recipes.length === project.recipes.length ? project : { ...project, recipes };
}
