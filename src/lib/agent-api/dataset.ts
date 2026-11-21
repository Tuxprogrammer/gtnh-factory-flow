import {
  getDatasetRecipe,
  getDatasetResource,
  getDefaultDatasetVersionId,
} from "@/lib/server/dataset-query";
import { AgentApiError } from "./errors";
import type { ResourceRef } from "./schemas";

/**
 * The agent API reads the same dataset the site serves, through `dataset-query.ts` and its caches: the version the
 * server prewarms (latest stable, else latest daily, else the first). Durations and amounts are as served; the
 * instance's dataset already carries the server's recipe speed.
 */
export async function agentDatasetVersion(): Promise<string> {
  let versionId: string | undefined;
  try {
    versionId = await getDefaultDatasetVersionId();
  } catch {
    versionId = undefined;
  }
  if (!versionId) {
    throw new AgentApiError("dataset_unavailable", "No dataset is installed on this server.");
  }
  return versionId;
}

/** Turns a dataset load failure into dataset_unavailable; other errors pass through. */
export async function withDataset<T>(run: () => Promise<T>): Promise<T> {
  try {
    return await run();
  } catch (error) {
    if (error instanceof AgentApiError) {
      throw error;
    }
    const code = (error as NodeJS.ErrnoException | undefined)?.code;
    if (code === "ENOENT" || (error instanceof Error && /has no server resource index/.test(error.message))) {
      throw new AgentApiError("dataset_unavailable", "The dataset could not be read on this server.");
    }
    throw error;
  }
}

const KIND_ORDER = ["item", "fluid", "aspect"] as const;

export interface ResolvedResource {
  id: string;
  kind: "item" | "fluid" | "aspect";
  name: string;
}

/** A resource reference made concrete, or unknown_resource. A bare id is tried as an item, a fluid, then an aspect. */
export async function resolveResource(versionId: string, ref: ResourceRef): Promise<ResolvedResource> {
  const id = typeof ref === "string" ? ref : ref.id;
  const kinds = typeof ref === "string" || !ref.kind ? KIND_ORDER : [ref.kind];
  for (const kind of kinds) {
    const resource = await getDatasetResource(versionId, kind, id);
    if (resource) {
      return { id, kind, name: resource.displayName || id };
    }
  }
  throw new AgentApiError("unknown_resource", `No resource with id "${id}". Find ids with resources/search.`);
}

export async function loadRecipe(versionId: string, recipeId: string) {
  const recipe = await getDatasetRecipe(versionId, recipeId);
  if (!recipe) {
    throw new AgentApiError("unknown_recipe", `No recipe with id "${recipeId}". Find ids with recipes/search.`);
  }
  return recipe;
}

/**
 * Builds the lookups the agent API adds on top of the site's prewarm (the recipe id map that recipes/get reads), so
 * the first call after a start is not the one that pays for it. Called from instrumentation when the API is on.
 */
export async function warmAgentApi(): Promise<void> {
  const versionId = await getDefaultDatasetVersionId();
  if (versionId) {
    await getDatasetRecipe(versionId, "");
  }
}
