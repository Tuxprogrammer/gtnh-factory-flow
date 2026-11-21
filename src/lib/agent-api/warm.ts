import { getDatasetRecipe, getDefaultDatasetVersionId } from "@/lib/server/dataset-query";
import { searchRecipes } from "./recipes";
import { searchResources } from "./resources";

/**
 * Runs one call of each kind the agent API makes after the site's own prewarm: the recipe id map recipes/get reads,
 * a name search, a resource search and a text-only recipe search build their lazy indexes here, so the first call
 * after a start is not the one that pays for them. Called from instrumentation when the API is on.
 */
export async function warmAgentApi(): Promise<void> {
  const versionId = await getDefaultDatasetVersionId();
  if (!versionId) {
    return;
  }
  await getDatasetRecipe(versionId, "");
  const found = await searchResources({ text: "water", limit: 1 });
  await searchRecipes({ text: "water", limit: 1, offset: 0, detail: false });
  const first = found.results[0];
  if (first) {
    await searchRecipes({ makes: { id: first.id, kind: first.kind as "item" | "fluid" }, limit: 1, offset: 0, detail: false });
  }
}
