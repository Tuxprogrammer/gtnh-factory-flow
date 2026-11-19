import { beforeEach, describe, expect, it, vi } from "vitest";
import type { DatasetVersion } from "./types";

const queryRecipeDatasetRecipes = vi.fn();
const getRecipeDatasetRecipe = vi.fn();

vi.mock("./browser-loader", () => ({
  queryRecipeDatasetRecipes: (...args: unknown[]) => queryRecipeDatasetRecipes(...args),
  getRecipeDatasetRecipe: (...args: unknown[]) => getRecipeDatasetRecipe(...args),
}));

import { fetchFluidTankRecipe } from "./tank-recipe";

const version = { id: "v" } as unknown as DatasetVersion;

function summary(id: string, name: string, itemIds: string[]) {
  return {
    id,
    name,
    recipeMap: "Tank",
    inputs: itemIds.map((item) => ({ kind: "item", id: item, amount: 1 })),
    outputs: [{ kind: "fluid", id: "water", amount: 1000 }],
  };
}

beforeEach(() => {
  queryRecipeDatasetRecipes.mockReset();
  getRecipeDatasetRecipe.mockReset();
  getRecipeDatasetRecipe.mockImplementation(async (_url: string, _version: unknown, id: string) => ({ id }));
});

describe("the Tank recipe a dropped port fits", () => {
  it("asks the Tank map for what takes a resource in when the tank takes it", async () => {
    queryRecipeDatasetRecipes.mockResolvedValue({
      recipes: [summary("can", "Tank: Can", ["gt:can"])],
    });
    await fetchFluidTankRecipe(version, { kind: "item", id: "gt:watercell" }, true);
    expect(queryRecipeDatasetRecipes.mock.calls[0]![2]).toMatchObject({
      mode: "uses",
      recipeMap: "Tank",
      resource: { kind: "item", id: "gt:watercell" },
    });
  });

  it("asks what makes it when the tank gives the resource", async () => {
    queryRecipeDatasetRecipes.mockResolvedValue({ recipes: [] });
    expect(await fetchFluidTankRecipe(version, { kind: "fluid", id: "water" }, false)).toBeUndefined();
    expect(queryRecipeDatasetRecipes.mock.calls[0]![2]).toMatchObject({ mode: "recipes" });
  });

  it("prefers the plain Empty Cell's recipe, as the mod does, and loads it whole", async () => {
    queryRecipeDatasetRecipes.mockResolvedValue({
      recipes: [
        summary("can", "Tank: Can", ["gt:can"]),
        summary("cell", "Tank: Empty Cell", ["gregtech:cell"]),
        summary("capsule", "Tank: Refractory Capsule", ["gt:capsule"]),
      ],
    });
    const recipe = await fetchFluidTankRecipe(version, { kind: "fluid", id: "water" }, false);
    expect(recipe?.id).toBe("cell");
    expect(getRecipeDatasetRecipe.mock.calls[0]![2]).toBe("cell");
  });

  it("falls back to the first recipe the map has, and refuses what is neither an item nor a fluid", async () => {
    queryRecipeDatasetRecipes.mockResolvedValue({ recipes: [summary("can", "Tank: Can", ["gt:can"])] });
    expect((await fetchFluidTankRecipe(version, { kind: "fluid", id: "water" }, false))?.id).toBe("can");
    queryRecipeDatasetRecipes.mockClear();
    expect(await fetchFluidTankRecipe(version, { kind: "aspect", id: "ignis" }, true)).toBeUndefined();
    expect(queryRecipeDatasetRecipes).not.toHaveBeenCalled();
  });

  // The search pairs a fluid with its filled cell, so the Tank map's answer can hold the other direction's recipe.
  // The game adds no emptying recipe for five molten metals (GTPostLoad skips Ruthenium, Rhodium, Osmium, Iridium
  // and Platinum), so for them the filling recipe is all the map has, and a tank that empties must not take it.
  const fillMoltenOsmium = {
    id: "fill-osmium",
    name: "Tank: Molten Osmium Cell",
    recipeMap: "Tank",
    inputs: [
      { kind: "item", id: "ic2:itemcellempty", amount: 1 },
      { kind: "fluid", id: "molten.osmium", amount: 144 },
    ],
    outputs: [{ kind: "item", id: "gregtech:gt.metaitem.99@83", amount: 1 }],
  };

  it("refuses a recipe that holds the resource on the wrong side: a tank giving a fluid never fills cells", async () => {
    queryRecipeDatasetRecipes.mockResolvedValue({ recipes: [fillMoltenOsmium] });
    expect(
      await fetchFluidTankRecipe(version, { kind: "fluid", id: "molten.osmium" }, false),
    ).toBeUndefined();
    expect(getRecipeDatasetRecipe).not.toHaveBeenCalled();
  });

  it("refuses a recipe that makes the cell when the tank takes that cell in", async () => {
    queryRecipeDatasetRecipes.mockResolvedValue({ recipes: [fillMoltenOsmium] });
    expect(
      await fetchFluidTankRecipe(version, { kind: "item", id: "gregtech:gt.metaitem.99@83" }, true),
    ).toBeUndefined();
  });

  it("passes over the other direction's recipe for the one that fits", async () => {
    const drainToluene = {
      id: "drain-toluene",
      name: "Tank: Empty Cell",
      recipeMap: "Tank",
      inputs: [{ kind: "item", id: "gregtech:gt.metaitem.01@30647", amount: 1 }],
      outputs: [
        { kind: "item", id: "ic2:itemcellempty", amount: 1 },
        { kind: "fluid", id: "liquid_toluene", amount: 1000 },
      ],
    };
    const fillToluene = {
      id: "fill-toluene",
      name: "Tank: Toluene Cell",
      recipeMap: "Tank",
      inputs: [
        { kind: "item", id: "ic2:itemcellempty", amount: 1 },
        { kind: "fluid", id: "liquid_toluene", amount: 1000 },
      ],
      outputs: [{ kind: "item", id: "gregtech:gt.metaitem.01@30647", amount: 1 }],
    };
    queryRecipeDatasetRecipes.mockResolvedValue({ recipes: [drainToluene, fillToluene] });
    expect(
      (await fetchFluidTankRecipe(version, { kind: "fluid", id: "liquid_toluene" }, true))?.id,
    ).toBe("fill-toluene");
    queryRecipeDatasetRecipes.mockResolvedValue({ recipes: [fillToluene, drainToluene] });
    expect(
      (await fetchFluidTankRecipe(version, { kind: "fluid", id: "liquid_toluene" }, false))?.id,
    ).toBe("drain-toluene");
  });

  it("refuses the drop when the lookup fails, never guessing a recipe", async () => {
    queryRecipeDatasetRecipes.mockRejectedValue(new Error("offline"));
    expect(await fetchFluidTankRecipe(version, { kind: "fluid", id: "water" }, false)).toBeUndefined();
  });
});
