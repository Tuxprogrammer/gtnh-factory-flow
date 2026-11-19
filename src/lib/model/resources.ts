import type {
  Recipe,
  RecipeOutput,
  ResourceAmount,
  ResourceKey,
  ResourceKind,
} from "./types";
import { getCropsNhStats } from "./passive-production";
import { isFreeRecipeInput } from "./free-input";

export function makeResourceKey(kind: ResourceKind, resourceId: string): ResourceKey {
  return `${kind}:${resourceId}` as ResourceKey;
}

export function getResourceKey(resource: Pick<ResourceAmount, "kind" | "id">): ResourceKey {
  return makeResourceKey(resource.kind, resource.id);
}

export function isOreDictionaryResource(resource: Pick<ResourceAmount, "id">): boolean {
  return resource.id.startsWith("oredict:");
}

export function isVirtualChoiceResource(
  resource: Pick<ResourceAmount, "id" | "displayName">,
): boolean {
  return (
    isOreDictionaryResource(resource) ||
    Boolean(resource.displayName?.match(/^Ore Dictionary:\s*/i)) ||
    isWildcardChoiceResource(resource)
  );
}

function isWildcardChoiceResource(resource: Pick<ResourceAmount, "id" | "displayName">): boolean {
  const id = resource.id.trim();
  const displayName = resource.displayName?.trim() ?? "";

  return (
    // "@32767" is the any-damage wildcard pseudo-item, not a real item; it
    // shares its display name with the damage-0 item and would list twice.
    id.endsWith("@32767") ||
    /^any(?:$|[:@._-])/i.test(id) ||
    /^any(?:$|\s|[:@._-])/i.test(displayName)
  );
}

export function resourceLabel(resource: Pick<ResourceAmount, "id" | "displayName"> & Partial<Pick<ResourceAmount, "alternatives">>): string {
  if (isOreDictionaryResource(resource)) {
    const group = resource.id.slice("oredict:".length);
    const woodNames: Record<string, string> = {
      plankWood: "wooden planks", stickWood: "wooden sticks", logWood: "wood logs",
    };
    const words = group.replace(/([a-z0-9])([A-Z])/g, "$1 $2").toLowerCase();
    const memberName = resource.alternatives?.find((entry) => !isVirtualChoiceResource(entry))?.displayName;
    return `Any ${woodNames[group] ?? memberName ?? words}`;
  }
  const displayName = stripOreDictionaryPrefix(resource.displayName);
  if (displayName) {
    return displayName;
  }

  return resource.id;
}

export function primaryOutput(recipe: Recipe): RecipeOutput | undefined {
  return recipe.outputs.find((output) => !output.byproduct) ?? recipe.outputs[0];
}

export function getChanceMultiplier(
  recipe: Pick<Recipe, "metadata">,
  output: RecipeOutput,
): number {
  // A CropsNH card bakes EXPECTED amounts: the drop-table weight is already
  // inside `amount`, and `chance` is that same weight kept for the display
  // badge. Multiplying it in again would count the probability twice.
  if (getCropsNhStats(recipe)) {
    return 1;
  }
  return output.chance ?? 1;
}

export function isRecipeInputConsumed(
  input: Pick<ResourceAmount, "id"> & { kind?: string; consumed?: boolean },
): boolean {
  // A free-in-the-game placeholder (see free-input.ts) is nothing anyone
  // supplies, so every rule that skips a circuit skips it too.
  if (isFreeRecipeInput(input)) {
    return false;
  }
  return input.consumed !== false;
}

const ANY_DAMAGE_SUFFIX = "@32767";

/**
 * "@32767" is Minecraft's any-damage wildcard. `minecraft:log@32767` is not an
 * item you can hold: it stands for "an oak, spruce, birch or jungle log,
 * whichever damage value". A concrete variant satisfies it and it satisfies a
 * concrete slot, so the two must match wherever an item id is compared.
 *
 * The dataset leans on it hard: `oredict:logWood` lists its vanilla members as
 * the two wildcards rather than the six real logs, and ~2,800 recipes take a
 * wildcard input directly. This mirrors the server's rule
 * (`resourceIdsAreCompatible` in `dataset-query.ts`).
 */
function itemIdsMatch(left: string, right: string): boolean {
  return left === right || matchesAnyDamage(left, right) || matchesAnyDamage(right, left);
}

function matchesAnyDamage(wildcardId: string, concreteId: string): boolean {
  if (!wildcardId.endsWith(ANY_DAMAGE_SUFFIX)) {
    return false;
  }

  const baseId = wildcardId.slice(0, -ANY_DAMAGE_SUFFIX.length);
  // A bare id is the damage-0 item ("minecraft:log" is Oak Log). Anchoring the
  // prefix on "@" keeps "minecraft:log@32767" off "minecraft:log2@1".
  return concreteId === baseId || concreteId.startsWith(`${baseId}@`);
}

/**
 * An item is an item and a fluid is a fluid: kinds compare strictly. A filled
 * cell is an ordinary item and does NOT satisfy its fluid's slot; in game the
 * cell goes through a Canner first. Do not add a cross-kind branch: it hides
 * the Canner, its empty cells and its power from the plan.
 *
 * Cross-form equivalence exists only for SEARCH (`isFluidEquivalentToFilledCell`).
 * A cell meets its fluid on the board only in a fluid tank card.
 */
export function resourceMatchesInput(
  resource: Pick<ResourceAmount, "kind" | "id" | "displayName">,
  input: Pick<ResourceAmount, "kind" | "id" | "displayName" | "alternatives">,
): boolean {
  if (resource.kind !== input.kind) {
    return false;
  }

  // Damage values only exist on items; a fluid id is compared literally.
  const idsMatch = resource.kind === "item" ? itemIdsMatch : (left: string, right: string) =>
    left === right;

  return (
    idsMatch(resource.id, input.id) ||
    Boolean(
      input.alternatives?.some(
        (alternative) =>
          alternative.kind === resource.kind && idsMatch(alternative.id, resource.id),
      ),
    )
  );
}

/**
 * The fluid a filled cell is named after, for SEARCH ONLY: "does a fluid form
 * of this item exist, so the recipe book can offer it too". Deliberately
 * carries no amount or litres-per-cell ratio.
 */
export function getFilledCellFluidEquivalent(
  resource: Pick<ResourceAmount, "kind" | "id" | "displayName"> & {
    alternatives?: ResourceAmount["alternatives"];
  },
): Pick<ResourceAmount, "kind" | "id" | "displayName"> | undefined {
  if (resource.kind === "fluid") {
    return { kind: "fluid", id: resource.id, displayName: resource.displayName };
  }

  const alternative = resource.alternatives?.find((entry) => entry.kind === "fluid");
  if (alternative) {
    return { kind: "fluid", id: alternative.id, displayName: alternative.displayName };
  }

  const fluidName = getFilledCellFluidName(resource);
  if (!fluidName) {
    return undefined;
  }

  return { kind: "fluid", id: normalizeFluidId(fluidName), displayName: fluidName };
}

/**
 * Name-tolerant "is this fluid what this cell holds", shared by the recipe
 * search and Pool's cell pairs. A fluid id rarely spells its display name
 * exactly ("Molten Cast Iron" is `molten.castiron`). On its own it converts
 * no amounts and wires nothing: only a Canner ratio does.
 */
export function isFluidEquivalentToFilledCell(
  fluid: Pick<ResourceAmount, "kind" | "id" | "displayName">,
  cell: Pick<ResourceAmount, "kind" | "id" | "displayName" | "alternatives">,
): boolean {
  if (
    cell.alternatives?.some(
      (alternative) => alternative.kind === "fluid" && alternative.id === fluid.id,
    )
  ) {
    return true;
  }

  const fluidName = getFilledCellFluidName(cell);
  if (!fluidName) {
    return false;
  }

  const normalizedFluidName = normalizeResourceName(fluidName);
  const normalizedFluidDisplayName = normalizeResourceName(resourceLabel(fluid));

  return (
    normalizeFluidId(fluidName) === fluid.id ||
    normalizedFluidName === normalizedFluidDisplayName ||
    normalizedFluidName === normalizeResourceName(fluid.id)
  );
}

function getFilledCellFluidName(
  resource: Pick<ResourceAmount, "displayName" | "id">,
): string | undefined {
  const label = resourceLabel(resource).trim();
  const match = label.match(/^(.+?)\s+Cell$/i);
  if (!match) {
    return undefined;
  }

  const fluidName = match[1]?.trim();
  return fluidName && !/^empty$/i.test(fluidName) ? fluidName : undefined;
}

function normalizeFluidId(fluidName: string): string {
  return normalizeResourceName(fluidName).replace(/\s+/g, ".");
}

function normalizeResourceName(value: string): string {
  return value
    .toLowerCase()
    .replace(/^fluid:/, "")
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

export function stripOreDictionaryPrefix(value: string | undefined): string | undefined {
  const stripped = value?.replace(/^Ore Dictionary:\s*/i, "").trim();
  return stripped || undefined;
}
