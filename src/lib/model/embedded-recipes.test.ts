import { describe, expect, it } from "vitest";
import {
  PROJECT_SCHEMA_VERSION,
  type FactoryNode,
  type FactoryProject,
  type Recipe,
} from "./types";
import { keepUsedRecipes } from "./embedded-recipes";
import { normalizeLoadedProject } from "./project-normalize";
import { parseFactoryProjectJson, serializeFactoryProject } from "@/lib/import-export/factory-json";
import { useFactoryStore } from "@/store/factory-store";

// A saved plan holds each recipe some card runs, once.

function recipe(id: string, name = id): Recipe {
  return {
    id,
    name,
    machineType: "Large Chemical Reactor",
    minimumTier: "LV",
    durationTicks: 100,
    eut: 30,
    inputs: [{ kind: "item", id: `${id}-in`, amount: 1, displayName: `${id} in` }],
    outputs: [{ kind: "item", id: `${id}-out`, amount: 1, displayName: `${id} out` }],
  };
}

function card(id: string, recipeId: string, extra: Partial<FactoryNode> = {}): FactoryNode {
  return {
    id,
    recipeId,
    machineCount: 1,
    parallel: 1,
    overclockTier: "LV",
    enabled: true,
    position: { x: 0, y: 0 },
    ...extra,
  };
}

function plan(recipes: Recipe[], nodes: FactoryNode[]): FactoryProject {
  return {
    schemaVersion: PROJECT_SCHEMA_VERSION,
    id: "embedded",
    name: "Embedded",
    fuelProfiles: [],
    recipes,
    nodes,
    edges: [],
    storages: [],
    metadata: { createdAt: "2026-11-22T00:00:00.000Z", updatedAt: "2026-11-22T00:00:00.000Z" },
  };
}

const ids = (project: FactoryProject) =>
  project.recipes.map((entry) => `${entry.id}:${entry.name}`);

describe("embedded recipes", () => {
  it("keeps every section's recipe, the first entry for an id, and nothing no card runs", () => {
    const project = plan(
      [recipe("a", "first"), recipe("b"), recipe("a", "second"), recipe("unused"), recipe("c")],
      [card("n1", "a", { extraRecipes: [{ recipeId: "c" }] }), card("n2", "b")],
    );
    expect(ids(keepUsedRecipes(project))).toEqual(["a:first", "b:b", "c:c"]);
  });

  it("returns the same plan when there is nothing to drop", () => {
    const project = plan([recipe("a")], [card("n1", "a")]);
    expect(keepUsedRecipes(project)).toBe(project);
  });

  it("drops them on the way in, so an import and its export carry neither", () => {
    const text = JSON.stringify(
      plan([recipe("a"), recipe("a"), recipe("unused")], [card("n1", "a")]),
    );
    expect(ids(parseFactoryProjectJson(text))).toEqual(["a:a"]);
    expect(ids(normalizeLoadedProject(JSON.parse(text)))).toEqual(["a:a"]);
    const exported = JSON.parse(
      serializeFactoryProject(parseFactoryProjectJson(text)),
    ) as FactoryProject;
    expect(ids(exported)).toEqual(["a:a"]);
  });

  it("a removed section's recipe leaves the plan, and undo brings it back", () => {
    useFactoryStore
      .getState()
      .setProject(
        plan([recipe("a"), recipe("b")], [card("n1", "a", { extraRecipes: [{ recipeId: "b" }] })]),
      );
    useFactoryStore.getState().removeRecipeSection("n1", 1);
    expect(ids(useFactoryStore.getState().project)).toEqual(["a:a"]);
    useFactoryStore.getState().undo();
    expect(ids(useFactoryStore.getState().project)).toEqual(["a:a", "b:b"]);
    expect(useFactoryStore.getState().project.nodes[0]!.extraRecipes).toEqual([{ recipeId: "b" }]);
  });

  it("a deleted card's recipe leaves the plan", () => {
    useFactoryStore
      .getState()
      .setProject(plan([recipe("a"), recipe("b")], [card("n1", "a"), card("n2", "b")]));
    useFactoryStore.getState().deleteNode("n2");
    expect(ids(useFactoryStore.getState().project)).toEqual(["a:a"]);
  });
});
