import type { z } from "zod";
import type { RecipeSummary } from "@/lib/datasets/types";
import type { RecipeQueryClause } from "@/lib/datasets/recipe-query";
import { queryDatasetRecipes } from "@/lib/server/dataset-query";
import { getVoltageTierIndex } from "@/lib/model/tiers";
import type { MachineTier } from "@/lib/model/types";
import { bannedSet, checkRecipe, notEnforced } from "./constraints";
import { agentDatasetVersion, resolveResource, withDataset, type ResolvedResource } from "./dataset";
import { formatCompactRecipe, recipeMapOf } from "./format";
import { fitList } from "./size";
import type { recipesSearchRequest, recipesSearchResponse } from "./schemas";

/**
 * A search with more matches than this does not take them in the site's one-list order (which pages machine by
 * machine, alphabetically): it asks each matching recipe map for its own best few instead, so a late map's main
 * recipe is not cut off by an early map's many byproduct ones.
 */
export const WHOLE_WINDOW = 160;
/** The most recipe maps asked one by one on a large search, biggest first. */
const MAX_MAPS = 48;

type SearchRequest = z.infer<typeof recipesSearchRequest>;
type SearchResponse = z.infer<typeof recipesSearchResponse>;

function tierIndex(tier: string): number {
  return getVoltageTierIndex(tier as Exclude<MachineTier, "DEMO">);
}

function consumedInputs(recipe: RecipeSummary): number {
  return recipe.inputs.filter((input) => input.consumed !== false).length;
}

function sameResource(entry: { id: string; kind: string }, resource: ResolvedResource): boolean {
  return entry.id === resource.id && entry.kind === resource.kind;
}

/**
 * The order results come out in (docs/agent-api.md, "Ranking"):
 * - makes: recipes whose first output is the resource at full chance (its main product) before recipes that make it
 *   second or by chance (byproducts); then fewer outputs (the resource is more of what the recipe is for); then the
 *   lower minimum tier; then fewer consumed inputs.
 * - takes: recipes that consume the resource before ones that only hold it (a catalyst, a circuit, a mold); then the
 *   lower minimum tier; then fewer consumed inputs.
 * - text alone: recipes whose name holds every word first; then the lower minimum tier.
 * Anything still equal keeps the site's own search order.
 */
export function rankRecipes(
  recipes: RecipeSummary[],
  makes: ResolvedResource | undefined,
  takes: ResolvedResource | undefined,
  text: string | undefined,
): RecipeSummary[] {
  const words = text ? text.toLowerCase().split(/\s+/).filter(Boolean) : [];
  const keyed = recipes.map((recipe, order) => {
    const keys: number[] = [];
    if (makes) {
      const first = recipe.outputs[0];
      const main =
        first && sameResource(first, makes) && (first.chance === undefined || first.chance >= 1) ? 0 : 1;
      keys.push(main, recipe.outputs.length);
    }
    if (takes) {
      const input = recipe.inputs.find((entry) => sameResource(entry, takes));
      keys.push(input && input.consumed !== false ? 0 : 1);
    }
    if (!makes && !takes && words.length) {
      const name = recipe.name.toLowerCase();
      keys.push(words.every((word) => name.includes(word)) ? 0 : 1);
    }
    keys.push(tierIndex(String(recipe.minimumTier)), consumedInputs(recipe), order);
    return { recipe, keys };
  });
  keyed.sort((left, right) => {
    for (let index = 0; index < left.keys.length; index += 1) {
      const delta = left.keys[index]! - right.keys[index]!;
      if (delta !== 0) {
        return delta;
      }
    }
    return 0;
  });
  return keyed.map((entry) => entry.recipe);
}

function machineMatches(recipe: RecipeSummary, machine: string): boolean {
  const wanted = machine.trim().toLowerCase();
  const names = [recipeMapOf(recipe), recipe.machineType, ...(recipe.machineHandlers ?? []).flatMap((h) => [h.id, h.label])];
  return names.some((name) => name?.toLowerCase() === wanted);
}

export async function searchRecipes(body: SearchRequest): Promise<SearchResponse> {
  return withDataset(async () => {
    const versionId = await agentDatasetVersion();
    const makes = body.makes === undefined ? undefined : await resolveResource(versionId, body.makes);
    const takes = body.takes === undefined ? undefined : await resolveResource(versionId, body.takes);
    const clauses: RecipeQueryClause[] = [
      ...(makes ? [{ role: "makes" as const, kind: makes.kind, id: makes.id }] : []),
      ...(takes ? [{ role: "takes" as const, kind: takes.kind, id: takes.id }] : []),
    ];
    const base = {
      query: body.text ?? "",
      mode: "recipes" as const,
      clauses: clauses.length ? clauses : undefined,
      takesOp: "all" as const,
      makesOp: "all" as const,
      maxTier: body.constraints?.tierCap ?? ("all" as const),
    };

    const first = await queryDatasetRecipes(versionId, { ...base, allMaps: true, offset: 0, limit: WHOLE_WINDOW });
    let candidates: RecipeSummary[] = first.recipes;
    // Text alone keeps the first window: asking map by map would score the words once per map.
    if (first.total > WHOLE_WINDOW && clauses.length > 0) {
      const wantedMaps = body.machine
        ? first.recipeMaps.filter((map) => map.toLowerCase() === body.machine!.toLowerCase())
        : [];
      const maps = (wantedMaps.length ? wantedMaps : first.recipeMaps)
        .slice()
        .sort((a, b) => (first.recipeMapCounts[b] ?? 0) - (first.recipeMapCounts[a] ?? 0))
        .slice(0, MAX_MAPS);
      const perMap = Math.min(40, Math.max(12, (body.offset + body.limit) * 2));
      const pages = await Promise.all(
        maps.map((recipeMap) =>
          queryDatasetRecipes(versionId, { ...base, allMaps: false, recipeMap, offset: 0, limit: perMap }),
        ),
      );
      const seen = new Set<string>();
      candidates = [];
      for (const page of pages) {
        for (const recipe of page.recipes) {
          if (!seen.has(recipe.id)) {
            seen.add(recipe.id);
            candidates.push(recipe);
          }
        }
      }
    }

    const banned = bannedSet(body.constraints);
    const kept: RecipeSummary[] = [];
    const machineByRecipe = new Map<string, string>();
    for (const recipe of candidates) {
      if (body.machine && !machineMatches(recipe, body.machine)) {
        continue;
      }
      const handler = checkRecipe(recipe, body.constraints, banned);
      if (!handler) {
        continue;
      }
      machineByRecipe.set(recipe.id, handler.label);
      kept.push(recipe);
    }
    const ranked = rankRecipes(kept, makes, takes, body.text);
    const page = ranked.slice(body.offset, body.offset + body.limit);
    const results = page.map((recipe) =>
      formatCompactRecipe(recipe, machineByRecipe.get(recipe.id) ?? recipe.machineType, body.detail),
    );
    const skipped = notEnforced(body.constraints);
    return fitList(results, (items, truncated) => ({
      dataset: versionId,
      results: items,
      total: first.total,
      offset: body.offset,
      more: body.offset + body.limit < ranked.length || first.total > candidates.length,
      ...(truncated ? { truncated } : {}),
      ...(skipped ? { notEnforced: skipped } : {}),
    }));
  });
}
