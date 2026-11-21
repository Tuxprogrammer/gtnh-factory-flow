import type { z } from "zod";
import type { RecipeSummary } from "@/lib/datasets/types";
import type { RecipeQueryClause } from "@/lib/datasets/recipe-query";
import { queryDatasetRecipes } from "@/lib/server/dataset-query";
import { getVoltageTierIndex } from "@/lib/model/tiers";
import type { MachineHandler, MachineTier } from "@/lib/model/types";
import { bannedSet, checkRecipe, handlerBanned, notEnforced } from "./constraints";
import { agentDatasetVersion, resolveResource, withDataset, type ResolvedResource } from "./dataset";
import { formatCompactRecipe, machinesOf, recipeMapOf } from "./format";
import { unpoweredKind } from "./power-source";
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

const FORM_PREFIX = /^(?:(?:small|tiny|impure|pure) pile of |crushed |purified |centrifuged |molten |hot )/i;
const FORM_SUFFIX =
  / (?:dust|ingot|plate|double plate|dense plate|nugget|rod|long rod|bolt|screw|ring|round|gear|small gear|foil|fine wire|wire|block|cell|gem|ore|frame box|spring|lens)$/i;

/**
 * The material a resource name stands for ("Titanium" for "Titanium Dust" or "Small Pile of Titanium Dust"), or
 * undefined when the name has no form word to strip. Used to tell recycling apart: a recipe that makes Titanium Dust
 * from a Titanium Ingot gives the material back rather than producing it.
 */
export function materialOf(name: string): string | undefined {
  const stripped = name.replace(FORM_PREFIX, "").replace(FORM_SUFFIX, "").trim();
  return stripped && stripped.length < name.length ? stripped.toLowerCase() : undefined;
}

function escapeRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/** Ore-processing forms: consuming one of them produces the material, it does not recycle it. */
const ORE_FORM = /\b(?:ore|crushed|purified|centrifuged|impure|shard|raw|cluster)\b|^(?:ore|crushed|raw)[A-Z]/i;
/** Recipe maps whose one-input recipes are the game's generated recycling of crafted parts. */
const RECYCLING_MAPS = new Set(["macerator", "arc furnace", "pulverizer"]);

/** Matches a material name as a word ("Titanium Ingot") or inside an ore dictionary name ("dustSmallTitanium"). */
export function materialPattern(material: string | undefined): RegExp | undefined {
  if (!material) {
    return undefined;
  }
  const word = escapeRegExp(material);
  const camel = escapeRegExp(material.replace(/(^|\s)\S/g, (letter) => letter.toUpperCase()).replace(/\s+/g, ""));
  return new RegExp(`(?:^|[^A-Za-z])(?:${word})(?![a-z])|[a-z]${camel}(?![a-z])`, "i");
}

/**
 * True when the recipe gives back material rather than producing it: a replicator; a recipe whose consumed inputs
 * (tools aside) are all the same material in another form that is not an ore form (dust from an ingot, from tiny piles);
 * or a one-input macerator, arc furnace or pulverizer recipe from something that is not ore (a crafted part).
 */
export function recycles(recipe: RecipeSummary, material: RegExp | undefined, makes: ResolvedResource): boolean {
  const map = recipeMapOf(recipe).toLowerCase();
  const consumed = recipe.inputs.filter(
    (input) =>
      input.consumed !== false &&
      !(input.id === makes.id && input.kind === makes.kind) &&
      !/craftingTool/i.test(input.displayName || input.id),
  );
  const names = consumed.map((input) => (input.displayName || input.id).replace(/^Ore Dictionary:\s*/i, ""));
  if (map.includes("replicator") || names.some((name) => /uu-matter/i.test(name))) {
    return true;
  }
  if (names.length === 0 || names.some((name) => ORE_FORM.test(name))) {
    return false;
  }
  if (material && names.every((name) => material.test(name))) {
    return true;
  }
  return names.length === 1 && RECYCLING_MAPS.has(map);
}

/**
 * The order results come out in (docs/agent-api.md, "Ranking"):
 * - makes: recipes whose first output is the resource at full chance (its main product) before recipes that make it
 *   second or by chance (byproducts); then recipes that produce it before ones that recycle it
 *   (`recycles`: dust from an ingot, a crafted part or a replicator); then fewer outputs (the resource is more of what
 *   the recipe is for); then the lower minimum tier; then fewer consumed inputs.
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
  const makesMaterial = makes ? materialOf(makes.name) : undefined;
  const material = materialPattern(makesMaterial);
  const keyed = recipes.map((recipe, order) => {
    const keys: number[] = [];
    if (makes) {
      const first = recipe.outputs[0];
      const main =
        first && sameResource(first, makes) && (first.chance === undefined || first.chance >= 1) ? 0 : 1;
      keys.push(main, recycles(recipe, material, makes) ? 1 : 0, recipe.outputs.length);
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

function namedUnpowered(recipe: RecipeSummary, machine: string): MachineHandler | undefined {
  const wanted = machine.trim().toLowerCase();
  return machinesOf(recipe).find(
    (handler) =>
      (handler.id.toLowerCase() === wanted || handler.label.toLowerCase() === wanted) && unpoweredKind(handler, recipe),
  );
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

    const perMap = Math.min(40, Math.max(12, (body.offset + body.limit) * 2));
    const whole = () => queryDatasetRecipes(versionId, { ...base, allMaps: true, offset: 0, limit: WHOLE_WINDOW });
    let candidates: RecipeSummary[];
    let total: number;
    if (clauses.length === 0) {
      // Text alone keeps the one window: asking map by map would score the words once per map.
      const page = await whole();
      candidates = page.recipes;
      total = page.total;
    } else {
      // One map's page first: the site ranks only that map's recipes for it, and it carries every map's count. An
      // all-maps page would rank every match once more before the map-by-map pages rank them again.
      const probe = await queryDatasetRecipes(versionId, { ...base, allMaps: false, offset: 0, limit: perMap });
      const mapCounts: Record<string, number> = probe.recipeMapCounts ?? {};
      total = Object.values(mapCounts).reduce((sum, count) => sum + count, 0);
      if (total <= WHOLE_WINDOW) {
        const page = await whole();
        candidates = page.recipes;
        total = page.total;
      } else {
        const wantedMaps = body.machine
          ? probe.recipeMaps.filter((map) => map.toLowerCase() === body.machine!.toLowerCase())
          : [];
        const maps = (wantedMaps.length ? wantedMaps : probe.recipeMaps)
          .slice()
          .sort((a, b) => (mapCounts[b] ?? 0) - (mapCounts[a] ?? 0))
          .slice(0, MAX_MAPS);
        const pages = await Promise.all(
          maps.map((recipeMap) =>
            recipeMap === probe.recipeMaps[0]
              ? probe
              : queryDatasetRecipes(versionId, { ...base, allMaps: false, recipeMap, offset: 0, limit: perMap }),
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
    }

    const banned = bannedSet(body.constraints);
    const kept: RecipeSummary[] = [];
    const machineByRecipe = new Map<string, string>();
    let steamOnly = 0;
    for (const recipe of candidates) {
      if (body.machine && !machineMatches(recipe, body.machine)) {
        continue;
      }
      // Naming a steam or primitive machine asks for it, as naming it on a card does.
      const wanted = body.machine ? namedUnpowered(recipe, body.machine) : undefined;
      const named = wanted && !handlerBanned(wanted, banned) ? wanted : undefined;
      const check = checkRecipe(recipe, named ? { ...body.constraints, steam: true } : body.constraints, banned);
      if (!check.machine) {
        steamOnly += check.steamOnly ? 1 : 0;
        continue;
      }
      machineByRecipe.set(recipe.id, (named ?? check.machine).label);
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
      total,
      offset: body.offset,
      more: body.offset + body.limit < ranked.length || total > candidates.length,
      ...(truncated ? { truncated } : {}),
      ...(skipped ? { notEnforced: skipped } : {}),
      ...(steamOnly ? { steamOnly } : {}),
    }));
  });
}
