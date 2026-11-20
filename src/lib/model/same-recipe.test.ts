import { describe, expect, it } from "vitest";
import type { Recipe } from "./types";
import { sameRecipeOr } from "./same-recipe";

const recipe = {
  id: "r",
  name: "R",
  machineType: "Alloy Smelter",
  durationTicks: 100,
  eut: 30,
  inputs: [{ kind: "item", id: "a", amount: 1, iconPath: "a.png" }],
  outputs: [{ kind: "item", id: "b", amount: 2 }],
  metadata: { tags: ["x"], heat: 1800 },
} as unknown as Recipe;

describe("sameRecipeOr", () => {
  it("keeps the stored object for an equal copy, whatever the key order", () => {
    const { metadata, ...rest } = structuredClone(recipe);
    expect(sameRecipeOr(recipe, { metadata, ...rest } as Recipe)).toBe(recipe);
  });

  it("takes the refreshed recipe for any difference, however deep", () => {
    const deeper = structuredClone(recipe);
    (deeper.metadata as { tags: string[] }).tags.push("y");
    const port = structuredClone(recipe);
    port.inputs[0]!.amount = 2;
    const added = { ...structuredClone(recipe), runtimeCalculation: undefined } as Recipe;
    const dropped = structuredClone(recipe);
    delete (dropped as Partial<Recipe>).metadata;
    const zero = { ...structuredClone(recipe), eut: -0 } as Recipe;
    for (const refreshed of [deeper, port, added, dropped, zero])
      expect(sameRecipeOr(recipe, refreshed)).toBe(refreshed);
  });
});
