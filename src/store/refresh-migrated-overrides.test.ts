import { beforeEach, describe, expect, it } from "vitest";
import { PROJECT_SCHEMA_VERSION, type FactoryProject, type Recipe } from "@/lib/model/types";
import { allowLegacyModesForTests } from "@/lib/model/project-normalize";
import { useFactoryStore } from "./factory-store";

allowLegacyModesForTests();

// A plan code or link lands without hydration; the landing refresh migrates each recipe the dataset re-keyed to its
// content match. The match may list its slots in another order, so slot picks must follow the resource.
const plate = {
  kind: "item" as const,
  id: "ore:plateAny",
  amount: 1,
  alternatives: [{ id: "plate-iron" }, { id: "plate-steel" }],
};
const water = { kind: "fluid" as const, id: "water", amount: 1000 };
const acid = { kind: "fluid" as const, id: "acid", amount: 500 };

function recipe(id: string, inputs: Recipe["inputs"]): Recipe {
  return {
    id,
    name: "Mix",
    machineType: "Mixer",
    minimumTier: "LV",
    durationTicks: 20,
    eut: 30,
    inputs,
    outputs: [{ kind: "item", id: "mix", amount: 1 }],
  };
}

function project(stored: Recipe, overrides: Record<string, Recipe["inputs"][number]>): FactoryProject {
  return {
    schemaVersion: PROJECT_SCHEMA_VERSION,
    id: "migrated-overrides",
    name: "Migrated overrides",
    recipes: [stored],
    nodes: [
      {
        id: "card",
        recipeId: stored.id,
        machineCount: 1,
        parallel: 1,
        overclockTier: "LV",
        enabled: true,
        position: { x: 0, y: 0 },
        recipeInputOverrides: overrides,
      },
    ],
    storages: [],
    edges: [],
    fuelProfiles: [],
  } as unknown as FactoryProject;
}

const card = () => useFactoryStore.getState().project.nodes.find((node) => node.id === "card")!;

describe("landing refresh: slot picks on a migrated recipe", () => {
  beforeEach(() => {
    useFactoryStore.getState().setProject(project(recipe("old", [plate, water]), {}));
  });

  it("moves a pick to the slot that takes its resource on the new recipe", () => {
    const stored = recipe("old", [plate, water]);
    const pick = { ...plate, id: "plate-steel", alternatives: undefined };
    useFactoryStore.getState().setProject(project(stored, { "0": pick }));
    const migrated = recipe("new", [water, plate]);
    useFactoryStore.getState().refreshProjectRecipes([migrated], { old: "new" });
    expect(card().recipeId).toBe("new");
    expect(Object.keys(card().recipeInputOverrides ?? {})).toEqual(["1"]);
    expect(card().recipeInputOverrides?.["1"]?.id).toBe("plate-steel");
  });

  it("keeps one pick per slot and drops a pick no slot of the new recipe takes", () => {
    const stored = recipe("old", [plate, water, acid]);
    // Two picks of the same fluid: the slot that takes it keeps one, the other slot shows its own input again.
    useFactoryStore.getState().setProject(project(stored, { "1": acid, "2": acid }));
    useFactoryStore.getState().refreshProjectRecipes([recipe("new", [plate, water, acid])], { old: "new" });
    expect(card().recipeInputOverrides).toEqual({ "2": acid });

    useFactoryStore.getState().setProject(project(stored, { "1": water }));
    useFactoryStore.getState().refreshProjectRecipes([recipe("new", [plate, acid])], { old: "new" });
    expect(card().recipeInputOverrides).toBeUndefined();
  });

  it("leaves picks where they are when the recipe keeps its id", () => {
    const stored = recipe("old", [plate, water]);
    const pick = { ...plate, id: "plate-iron", alternatives: undefined };
    useFactoryStore.getState().setProject(project(stored, { "0": pick }));
    useFactoryStore.getState().refreshProjectRecipes([recipe("old", [plate, water])]);
    expect(card().recipeInputOverrides).toEqual({ "0": pick });
  });
});
