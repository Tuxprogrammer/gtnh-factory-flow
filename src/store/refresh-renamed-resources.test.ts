import { describe, expect, it } from "vitest";
import { PROJECT_SCHEMA_VERSION, type FactoryProject, type Recipe } from "@/lib/model/types";
import { allowLegacyModesForTests } from "@/lib/model/project-normalize";
import { useFactoryStore } from "./factory-store";

allowLegacyModesForTests();

// A plan saved before the game moved an item to a new id: the landing refresh matches its recipes through the
// dataset's rename table and the plan's wires, drawers and saved copies follow the items.
const OLD = "ic2:itembiochaff";
const NEW = "gregtech:gt.metaitem.02@32021";
const renames = new Map([["ic2:itembiochaff@*", NEW]]);

function recipe(id: string, inputs: Recipe["inputs"], outputs: Recipe["outputs"]): Recipe {
  return { id, name: id, machineType: "M", minimumTier: "LV", durationTicks: 20, eut: 30, inputs, outputs };
}

function card(id: string, recipeId: string) {
  return { id, recipeId, machineCount: 1, parallel: 1, overclockTier: "LV", enabled: true, position: { x: 0, y: 0 } };
}

const enc = encodeURIComponent;
const centrifugeOld = recipe("centrifuge-old", [{ kind: "item", id: "mass", amount: 1 }], [{ kind: "item", id: OLD, amount: 1 }]);
const centrifugeNew = recipe(
  "centrifuge-new",
  [{ kind: "item", id: "mass", amount: 1 }],
  [{ kind: "item", id: NEW, amount: 1, displayName: "Bio Chaff" }],
);
const oven = recipe("oven", [{ kind: "item", id: OLD, amount: 4 }], [{ kind: "fluid", id: "ic2biomass", amount: 5000 }]);

const plan = {
  schemaVersion: PROJECT_SCHEMA_VERSION,
  id: "renamed",
  name: "Renamed",
  recipes: [centrifugeOld, oven],
  nodes: [card("a", "centrifuge-old"), card("b", "oven")],
  storages: [],
  edges: [
    {
      id: "e",
      source: "a",
      target: "b",
      resourceKind: "item",
      resourceId: OLD,
      sourceHandle: `output:item:${enc(OLD)}:0`,
      targetHandle: `input:item:${enc(OLD)}:0`,
    },
  ],
  fuelProfiles: [],
} as unknown as FactoryProject;

describe("landing refresh: items the game renamed", () => {
  it("moves the wire and the saved copy of a recipe the dataset lacks onto the new item", () => {
    const store = useFactoryStore.getState();
    store.setProject(plan);
    useFactoryStore.setState({ recipesNotInDataset: new Set(["oven"]) });
    useFactoryStore.getState().refreshProjectRecipes([centrifugeNew], { "centrifuge-old": "centrifuge-new" }, renames);
    const project = useFactoryStore.getState().project;
    expect(project.nodes.find((node) => node.id === "a")!.recipeId).toBe("centrifuge-new");
    expect(project.edges[0]).toMatchObject({
      resourceId: NEW,
      sourceHandle: `output:item:${enc(NEW)}:0`,
      targetHandle: `input:item:${enc(NEW)}:0`,
    });
    expect(project.recipes.find((entry) => entry.id === "oven")!.inputs[0]!.id).toBe(NEW);
  });

  it("changes nothing without a table", () => {
    useFactoryStore.getState().setProject(plan);
    useFactoryStore.getState().refreshProjectRecipes([centrifugeNew], { "centrifuge-old": "centrifuge-new" });
    expect(useFactoryStore.getState().project.edges[0]!.resourceId).toBe(OLD);
  });
});
