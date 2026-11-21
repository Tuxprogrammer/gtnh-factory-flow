import { describe, expect, it } from "vitest";
import { PROJECT_SCHEMA_VERSION, type FactoryEdge, type FactoryNode, type FactoryProject, type Recipe } from "@/lib/model/types";
import { useFactoryStore } from "@/store/factory-store";
import type { Draft } from "./drafts";
import { autoConnect } from "./wiring";

/**
 * The draft's automatic wiring mirrors the board's Auto-connect (`autoConnectNode`, whose builder the store keeps
 * private). Both must make the same wires on the same cards: repeated slots, "any of" slots, catalysts and fluids.
 */

const item = (id: string, amount = 1, extra: object = {}) => ({ kind: "item" as const, id, amount, displayName: id, ...extra });
const fluid = (id: string, amount = 1000) => ({ kind: "fluid" as const, id, amount, displayName: id });

function recipe(id: string, inputs: Recipe["inputs"], outputs: Recipe["outputs"]): Recipe {
  return { id, name: id, machineType: "Mock", minimumTier: "LV", durationTicks: 20, eut: 30, inputs, outputs };
}

function node(id: string, recipeId: string): FactoryNode {
  return { id, recipeId, machineCount: 1, parallel: 1, overclockTier: "LV", enabled: true, position: { x: 0, y: 0 } };
}

const RECIPES = [
  recipe("maker", [item("ore")], [item("dust"), item("dust"), fluid("steam"), item("plank")]),
  recipe("taker", [item("dust"), item("dust", 2), fluid("steam"), item("mold", 1, { consumed: false })], [item("ingot")]),
  recipe("any", [item("oredict:plankWood", 1, { alternatives: [item("plank")] })], [item("stick")]),
  recipe("loop", [item("ingot")], [item("ore"), item("mold")]),
];

function project(): FactoryProject {
  return {
    schemaVersion: PROJECT_SCHEMA_VERSION,
    id: "wiring",
    name: "wiring",
    fuelProfiles: [],
    recipes: RECIPES,
    nodes: [node("a", "maker"), node("b", "taker"), node("c", "any"), node("d", "loop")],
    edges: [],
  };
}

const shape = (edges: FactoryEdge[]) =>
  edges
    .map((edge) => `${edge.source}>${edge.target} ${edge.sourceHandle} ${edge.targetHandle} ${edge.resourceKind}:${edge.resourceId}`)
    .sort();

describe("draft wiring", () => {
  it.each(["a", "b", "c", "d"])("makes the wires the board's Auto-connect makes for card %s", (cardId) => {
    const store = useFactoryStore.getState();
    store.setProject(project());
    useFactoryStore.getState().autoConnectNode(cardId);
    const board = useFactoryStore.getState().project.edges;

    const draft = { project: project(), cut: new Set(), manual: new Set(), nextWire: 1 } as unknown as Draft;
    autoConnect(draft, [cardId]);
    expect(board.length).toBeGreaterThan(0);
    expect(shape(draft.project.edges)).toEqual(shape(board));
    expect(draft.project.nodes.map((entry) => entry.recipeInputOverrides)).toEqual(
      useFactoryStore.getState().project.nodes.map((entry) => entry.recipeInputOverrides),
    );
  });
});
