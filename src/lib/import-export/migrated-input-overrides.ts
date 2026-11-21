import type { Recipe, RecipeInput } from "@/lib/model/types";

/**
 * A card's slot picks (`recipeInputOverrides`) moved onto the recipe it migrated to. Slot numbers are the exporter's
 * order, which is not part of what makes two recipes the same, so a pick keyed by the old recipe's slot number can
 * land on another input of the new one. Each pick goes to the first unclaimed slot that takes its resource, as itself
 * or as one of the slot's alternatives; a pick no slot takes is dropped, and so is a second pick for a slot already
 * claimed. The same rule the plan file import applies (`carryNodeOntoMigratedRecipe`).
 */
export function rekeyInputOverrides(
  overrides: Record<string, RecipeInput> | undefined,
  recipe: Pick<Recipe, "inputs">,
): Record<string, RecipeInput> | undefined {
  if (!overrides) {
    return undefined;
  }
  const claimed = new Set<number>();
  const rekeyed: Record<string, RecipeInput> = {};
  for (const override of Object.values(overrides)) {
    if (!override) {
      continue;
    }
    const index = recipe.inputs.findIndex(
      (input, inputIndex) =>
        !claimed.has(inputIndex) &&
        input.kind === override.kind &&
        (input.id === override.id ||
          input.alternatives?.some((alternative) => alternative.id === override.id)),
    );
    if (index === -1) {
      continue;
    }
    claimed.add(index);
    rekeyed[String(index)] = override;
  }
  return Object.keys(rekeyed).length > 0 ? rekeyed : undefined;
}
