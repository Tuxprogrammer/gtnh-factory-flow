import { describe, expect, it } from "vitest";
import type { FactoryProject, Recipe } from "@/lib/model/types";
import {
  renameResourceId,
  resourceRenameLookup,
  withRenamedProjectResources,
  withRenamedResources,
} from "./resource-renames";

const OLD = "ic2:itemfuelplantball";
const NEW = "gregtech:gt.metaitem.02@32020";
const renames = resourceRenameLookup({
  schemaVersion: 1,
  renames: [
    { from: "ic2:itemfuelplantball@*", kind: "item", to: NEW },
    { from: "ic2:itemcellempty@6", kind: "item", to: "gregtech:gt.metaitem.01@30704" },
    { from: "miscutils:item.biorecipeselector@*", kind: "item", to: "gregtech:gt.integrated_circuit@*" },
  ],
});

const slot = (id: string, extra: Record<string, unknown> = {}) => ({ kind: "item", id, amount: 1, ...extra });
const recipe = (id: string, inputs: unknown[], outputs: unknown[]) => ({ id, name: id, inputs, outputs }) as unknown as Recipe;
const enc = encodeURIComponent;

function plan(recipes: Recipe[]): FactoryProject {
  return {
    id: "p",
    name: "p",
    recipes,
    nodes: [
      { id: "a", recipeId: recipes[0]!.id },
      { id: "b", recipeId: recipes[1]!.id },
    ],
    storages: [
      { id: "s", kind: "item", resourceId: OLD, displayName: "Plantball", iconPath: "/old.png" },
    ],
    edges: [
      {
        id: "e",
        source: "a",
        target: "b",
        resourceKind: "item",
        resourceId: OLD,
        label: "Plantball",
        sourceHandle: `output:item:${enc(OLD)}:0`,
        targetHandle: `input:item:${enc(OLD)}:0`,
      },
    ],
    spareShares: { [`a|o|output:item:${enc(OLD)}:0`]: { [`b|i|input:item:${enc(OLD)}:0`]: 1 } },
    view: { hiddenResourceKeys: [`item:${OLD}`, "fluid:water"], favouriteResourceKeys: [`item:${OLD}`] },
    gtnhplanner: { keep: OLD },
    fuelProfiles: [],
  } as unknown as FactoryProject;
}

describe("renameResourceId", () => {
  it("applies a meta's own row before the wildcard, keeps the meta where the target says so, and only for items", () => {
    expect(renameResourceId(renames, OLD)).toBe(NEW);
    expect(renameResourceId(renames, `${OLD}@3`)).toBe(NEW);
    expect(renameResourceId(renames, "ic2:itemcellempty@6")).toBe("gregtech:gt.metaitem.01@30704");
    expect(renameResourceId(renames, "ic2:itemcellempty@7")).toBe("ic2:itemcellempty@7");
    expect(renameResourceId(renames, "miscutils:item.biorecipeselector@12")).toBe("gregtech:gt.integrated_circuit@12");
    expect(renameResourceId(renames, OLD, "fluid")).toBe(OLD);
  });
});

describe("withRenamedResources", () => {
  it("rewrites a recipe's slots for matching", () => {
    const renamed = withRenamedResources(renames, recipe("r", [slot("natura:berry@1")], [slot(OLD)]));
    expect(renamed.outputs[0]!.id).toBe(NEW);
    expect(renamed.inputs[0]!.id).toBe("natura:berry@1");
  });
});

describe("withRenamedProjectResources", () => {
  const face = { displayName: "Plantball (GT)", iconPath: "/new.png" };

  it("moves wires, handles, drawers and saved keys once no card takes or makes the old item", () => {
    const project = plan([
      recipe("compressor", [slot("natura:berry@1")], [slot(NEW, face)]),
      recipe("macerator", [slot(NEW, face)], [slot("dreamcraft:maceratedplantmass")]),
    ]);
    const next = withRenamedProjectResources(project, renames);
    const edge = next.edges[0]!;
    expect(edge.resourceId).toBe(NEW);
    expect(edge.sourceHandle).toBe(`output:item:${enc(NEW)}:0`);
    expect(edge.targetHandle).toBe(`input:item:${enc(NEW)}:0`);
    expect(edge.label).toBe("Plantball (GT)");
    expect(next.storages![0]).toMatchObject({ resourceId: NEW, displayName: "Plantball (GT)", iconPath: "/new.png" });
    expect(next.spareShares).toEqual({
      [`a|o|output:item:${enc(NEW)}:0`]: { [`b|i|input:item:${enc(NEW)}:0`]: 1 },
    });
    expect(next.view!.hiddenResourceKeys).toEqual([`item:${NEW}`, "fluid:water"]);
    expect(next.view!.favouriteResourceKeys).toEqual([`item:${NEW}`]);
    // Recipe bodies are the dataset's, and the in-game planner's data is its own.
    expect(next.recipes).toBe(project.recipes);
    expect(next.gtnhplanner).toBe(project.gtnhplanner);
  });

  it("keeps the old item everywhere while a card on the board still names it", () => {
    const project = plan([
      recipe("compressor", [slot("natura:berry@1")], [slot(OLD)]),
      recipe("macerator", [slot(NEW, face)], [slot("dreamcraft:maceratedplantmass")]),
    ]);
    expect(withRenamedProjectResources(project, renames)).toBe(project);
  });

  it("moves the items of a recipe the dataset lacks, whose saved copy the card runs", () => {
    const project = plan([
      recipe("compressor", [slot("natura:berry@1")], [slot(NEW, face)]),
      recipe("saved", [slot(OLD, { displayName: "Plantball", iconPath: "/old.png" })], [slot("x")]),
    ]);
    // Without the note the saved copy is a dataset body still naming the old item: nothing moves.
    expect(withRenamedProjectResources(project, renames)).toBe(project);
    const next = withRenamedProjectResources(project, renames, new Set(["saved"]));
    expect(next.recipes[0]).toBe(project.recipes[0]);
    expect(next.recipes[1]!.inputs[0]).toMatchObject({ id: NEW, displayName: "Plantball (GT)", iconPath: "/new.png" });
    expect(next.edges[0]!.targetHandle).toBe(`input:item:${enc(NEW)}:0`);
  });

  it("returns the same plan when the dataset has no renames", () => {
    const project = plan([recipe("x", [], []), recipe("y", [], [])]);
    expect(withRenamedProjectResources(project, new Map())).toBe(project);
  });
});
