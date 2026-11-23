import { useFactoryStore } from "@/store/factory-store";
import type { FactoryNode } from "@/lib/model/types";
import type { TipSpec } from "./interaction/tip-spec";

/**
 * A card whose recipe the loaded dataset does not have (`recipesNotInDataset`: not found by id, nothing matches its
 * content) keeps running on the plan's saved copy, and says so: a tag over its head (`data-not-in-dataset`, drawn by
 * clean.css, so the card's size and its wires do not move) and a line in its name's tip. It says only what the site
 * knows: the recipe is not in the dataset it serves now. Nothing in the plan changes.
 */
export const NOT_IN_DATASET_TAG = "Recipe not in the current dataset";
export const NOT_IN_DATASET_NOTE = "This recipe is not in the current dataset. The card uses the plan's saved copy.";

/** Whether a recipe this card runs (any section of a shared machine) is one the loaded dataset lacks. */
export function useNotInDataset(node: Pick<FactoryNode, "recipeId" | "extraRecipes">): boolean {
  return useFactoryStore(
    (state) =>
      state.recipesNotInDataset.size > 0 &&
      (state.recipesNotInDataset.has(node.recipeId) ||
        (node.extraRecipes ?? []).some((extra) => state.recipesNotInDataset.has(extra.recipeId))),
  );
}

/** The card's name tip, led by the not-in-dataset line when it applies. */
export function withNotInDatasetNote(tip: TipSpec, notInDataset: boolean): TipSpec {
  return notInDataset ? { ...tip, notes: [{ text: NOT_IN_DATASET_NOTE, tone: "warn" }, ...(tip.notes ?? [])] } : tip;
}
