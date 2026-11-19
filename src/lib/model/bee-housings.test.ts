import { describe, expect, it } from "vitest";
import type { FactoryProject, Recipe } from "./types";
import { enrichPassiveProductionRecipe } from "./passive-production";
import { applyMachineHandlerToRecipe, getRecipeMachineHandlers } from "./recipe-rules";
import { normalizeLoadedProject } from "./project-normalize";

/** A Bee Produce recipe as the dataset ships it: no machine handlers of its own. */
function datasetBeeRecipe(): Recipe {
  return {
    id: "bee-salt",
    name: "Bee Produce: Salt Bee",
    machineType: "Bee Produce",
    minimumTier: "UNKNOWN",
    durationTicks: 550,
    eut: 0,
    inputs: [
      {
        kind: "item",
        id: "factoryflow:bee_species:salt",
        amount: 1,
        displayName: "Salt Bee",
        consumed: false,
      },
    ],
    outputs: [
      {
        kind: "item",
        id: "forestry:beecombs@0",
        amount: 1,
        displayName: "Honey Comb",
        chance: 0.3,
      },
    ],
    source: { recipeMap: "Bee Produce" },
  } as Recipe;
}

describe("the housings a bee card offers", () => {
  const recipe = enrichPassiveProductionRecipe(datasetBeeRecipe());

  it("start with the plain Apiary and the Bee House, then the rest", () => {
    expect(getRecipeMachineHandlers(recipe).map((handler) => handler.id)).toEqual([
      "apiary",
      "bee-house",
      "magic-apiary",
      "alveary",
      "industrial-apiary",
      "mega-apiary",
    ]);
  });

  it("run a card with no housing chosen in the plain Apiary", () => {
    expect(applyMachineHandlerToRecipe(recipe, {}).machineType).toBe("Apiary");
  });

  it("give the Bee House no frames, since the block has no frame slots", () => {
    const house = getRecipeMachineHandlers(recipe).find((handler) => handler.id === "bee-house")!;
    expect(house.machineType).toBe("Bee House");
    expect(house.machineConfigControls?.map((control) => control.id)).toEqual([
      "beeSpeedGene",
      "beeEnvironment",
    ]);
    const apiary = getRecipeMachineHandlers(recipe).find((handler) => handler.id === "apiary")!;
    expect(apiary.machineConfigControls?.map((control) => control.id)).toContain("beeFrameSlot3");
  });
});

describe("a plan saved when the Magic Apiary was the first housing", () => {
  function plan(firstHandler: string, chosen?: string): FactoryProject {
    const recipe = enrichPassiveProductionRecipe(datasetBeeRecipe());
    const handlers = [...(recipe.machineHandlers ?? [])];
    const first = handlers.findIndex((handler) => handler.id === firstHandler);
    handlers.unshift(...handlers.splice(first, 1));
    return {
      schemaVersion: 1,
      id: "bees",
      name: "Bees",
      recipes: [{ ...recipe, machineHandlers: handlers }],
      nodes: [
        {
          id: "card",
          recipeId: recipe.id,
          machineCount: 1,
          parallel: 1,
          position: { x: 0, y: 0 },
          ...(chosen ? { machineHandlerId: chosen } : {}),
        },
      ],
      storages: [],
      edges: [],
      fuelProfiles: [],
    } as unknown as FactoryProject;
  }

  it("keeps the Magic Apiary on a card that never chose one", () => {
    const loaded = normalizeLoadedProject(plan("magic-apiary"));
    expect(loaded.nodes[0]!.machineHandlerId).toBe("magic-apiary");
  });

  it("leaves a chosen housing and a plan listing the Apiary first alone", () => {
    expect(normalizeLoadedProject(plan("magic-apiary", "alveary")).nodes[0]!.machineHandlerId).toBe(
      "alveary",
    );
    expect(normalizeLoadedProject(plan("apiary")).nodes[0]!.machineHandlerId).toBeUndefined();
  });
});
