import { readFileSync } from "node:fs";
import { beforeEach, describe, expect, it } from "vitest";
import { captureBoardSelection, useFactoryStore } from "./factory-store";
import { normalizeLoadedProject } from "@/lib/model/project-normalize";
import { factoryProjectSchema } from "@/lib/model/schemas";
import { edgeSectionAt } from "@/lib/model/shared-machine";
import { PROJECT_SCHEMA_VERSION, type FactoryProject } from "@/lib/model/types";

// 7 cards, two of them shared machines (one with 1 extra section, one with 3).
const plan = (): FactoryProject =>
  normalizeLoadedProject(
    factoryProjectSchema.parse(
      JSON.parse(
        readFileSync(
          new URL(
            "../components/flow/__fixtures__/alumina-line-shared-reactor.json",
            import.meta.url,
          ),
          "utf8",
        ),
      ),
    ),
  );

const blank = (): FactoryProject => ({
  schemaVersion: PROJECT_SCHEMA_VERSION,
  id: "blank",
  name: "Blank",
  planMode: true,
  recipes: [],
  nodes: [],
  edges: [],
  fuelProfiles: [],
});

/** Wires whose section on either end has no recipe in the plan: the board cannot draw them. */
function danglingWires(project: FactoryProject): string[] {
  const recipes = new Set(project.recipes.map((recipe) => recipe.id));
  const nodes = new Map(project.nodes.map((node) => [node.id, node]));
  const sectionHeld = (nodeId: string, section: number) => {
    const node = nodes.get(nodeId);
    if (!node) return true; // a drawer end
    const id = section === 0 ? node.recipeId : node.extraRecipes?.[section - 1]?.recipeId;
    return id !== undefined && recipes.has(id);
  };
  return project.edges
    .filter(
      (edge) =>
        !sectionHeld(edge.source, edgeSectionAt(edge, edge.source, "source")) ||
        !sectionHeld(edge.target, edgeSectionAt(edge, edge.target, "target")),
    )
    .map((edge) => edge.id);
}

function everything(project: FactoryProject): string[] {
  return [
    ...project.nodes.map((node) => node.id),
    ...(project.storages ?? []).map((storage) => storage.id),
    ...(project.annotations ?? []).map((annotation) => annotation.id),
    ...(project.pockets ?? []).map((pocket) => pocket.id),
  ];
}

describe("pasting a shared machine", () => {
  beforeEach(() => {
    useFactoryStore.getState().setProject(plan());
  });

  it("brings every section's recipe back after a cut, so every wire still has its port", () => {
    const before = useFactoryStore.getState().project;
    expect(before.nodes.filter((node) => node.extraRecipes?.length)).toHaveLength(2);
    expect(danglingWires(before)).toEqual([]);
    const payload = captureBoardSelection(before, everything(before))!;

    useFactoryStore.getState().deleteBoardSelection({ nodeIds: everything(before) });
    expect(useFactoryStore.getState().project.recipes).toEqual([]);
    useFactoryStore.getState().pasteBoardItems(payload, { x: 40, y: 40 });

    const after = useFactoryStore.getState().project;
    expect(after.edges).toHaveLength(before.edges.length);
    expect(danglingWires(after)).toEqual([]);
    expect(after.recipes.map((recipe) => recipe.id).sort()).toEqual(
      before.recipes.map((recipe) => recipe.id).sort(),
    );
    // And it stays that way through a save and a load.
    expect(normalizeLoadedProject(structuredClone(after)).edges).toHaveLength(before.edges.length);
  });

  it("carries every section's recipe into another plan", () => {
    const source = useFactoryStore.getState().project;
    const payload = captureBoardSelection(source, everything(source))!;
    useFactoryStore.getState().setProject(blank());
    useFactoryStore.getState().pasteBoardItems(payload, { x: 0, y: 0 });

    const after = useFactoryStore.getState().project;
    expect(after.edges).toHaveLength(source.edges.length);
    expect(danglingWires(after)).toEqual([]);
    expect(after.recipes).toHaveLength(source.recipes.length);
  });

  it("keeps the plan's own copy of a recipe it already holds, once", () => {
    const source = useFactoryStore.getState().project;
    const shared = source.nodes.find((node) => (node.extraRecipes?.length ?? 0) > 1)!;
    const extraId = shared.extraRecipes![0]!.recipeId;
    const payload = captureBoardSelection(source, [shared.id])!;
    const own = {
      ...source.recipes.find((recipe) => recipe.id === extraId)!,
      name: "The plan's own copy",
    };
    const holder = {
      ...structuredClone(shared),
      id: "holder",
      recipeId: extraId,
      extraRecipes: undefined,
    };
    useFactoryStore.getState().setProject({ ...blank(), recipes: [own], nodes: [holder] });
    expect(useFactoryStore.getState().project.recipes.map((recipe) => recipe.name)).toEqual([
      own.name,
    ]);
    useFactoryStore.getState().pasteBoardItems(payload, { x: 0, y: 0 });

    const after = useFactoryStore.getState().project;
    expect(
      after.recipes.filter((recipe) => recipe.id === extraId).map((recipe) => recipe.name),
    ).toEqual([own.name]);
    expect(after.recipes).toHaveLength(1 + shared.extraRecipes!.length);
  });

  it("drops a section whose recipe the clipboard lacks too, with its wires", () => {
    const source = useFactoryStore.getState().project;
    const payload = captureBoardSelection(source, everything(source))!;
    const shared = payload.nodes.find((node) => (node.extraRecipes?.length ?? 0) > 1)!;
    const lost = shared.extraRecipes![0]!.recipeId;
    payload.recipes = payload.recipes.filter((recipe) => recipe.id !== lost);
    useFactoryStore.getState().setProject(blank());
    useFactoryStore.getState().pasteBoardItems(payload, { x: 0, y: 0 });

    const after = useFactoryStore.getState().project;
    const pasted = after.nodes.find((node) =>
      node.extraRecipes?.some((extra) => extra.recipeId === shared.extraRecipes![1]!.recipeId),
    )!;
    expect(pasted.extraRecipes!.map((extra) => extra.recipeId)).toEqual(
      shared.extraRecipes!.slice(1).map((extra) => extra.recipeId),
    );
    expect(danglingWires(after)).toEqual([]);
    expect(after.edges.length).toBeLessThan(source.edges.length);
  });
});

describe("a plan whose shared machine lost a section's recipe", () => {
  it("opens with that section and its wires gone, and later sections' wires moved down one", () => {
    const whole = plan();
    const shared = whole.nodes.find((node) => (node.extraRecipes?.length ?? 0) === 3)!;
    const [first, second, third] = shared.extraRecipes!.map((extra) => extra.recipeId);
    const touches = (edge: FactoryProject["edges"][number], section: number) =>
      (edge.source === shared.id && edgeSectionAt(edge, shared.id, "source") === section) ||
      (edge.target === shared.id && edgeSectionAt(edge, shared.id, "target") === section);
    const onSection = (project: FactoryProject, section: number) =>
      project.edges.filter((edge) => touches(edge, section)).length;
    // A wire from one section of the card to another is on both; one end on section 1 takes it.
    const wiresOn = [0, 1, 2, 3].map(
      (section) =>
        whole.edges.filter((edge) => touches(edge, section) && (section === 1 || !touches(edge, 1)))
          .length,
    );
    expect(wiresOn[1]).toBeGreaterThan(0);
    const damaged: FactoryProject = {
      ...whole,
      recipes: whole.recipes.filter((recipe) => recipe.id !== first),
    };
    expect(danglingWires(damaged).length).toBe(wiresOn[1]);

    const opened = normalizeLoadedProject(structuredClone(damaged));
    const card = opened.nodes.find((node) => node.id === shared.id)!;
    expect(card.extraRecipes!.map((extra) => extra.recipeId)).toEqual([second, third]);
    expect(danglingWires(opened)).toEqual([]);
    expect([0, 1, 2].map((section) => onSection(opened, section))).toEqual([
      wiresOn[0],
      wiresOn[2],
      wiresOn[3],
    ]);
    expect(onSection(opened, 3)).toBe(0);
    // An intact plan is left as it is.
    expect(normalizeLoadedProject(structuredClone(whole)).edges).toHaveLength(whole.edges.length);
  });
});
