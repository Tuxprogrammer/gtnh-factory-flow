import { afterEach, beforeEach, describe, expect, it } from "vitest";
import type { DatasetManifest, RecipeDataset } from "@/lib/datasets/types";
import { PROJECT_SCHEMA_VERSION, type FactoryProject, type Recipe } from "@/lib/model/types";
import { useFactoryStore } from "./factory-store";

// A plan saved on dataset "old" opened on a site that serves only "new": every icon it carries must come from "new".
const OLD = (file: string) => `/datasets/gtnh/old/textures/rendered/${file}`;
const NEW = (file: string) => `/datasets/gtnh/new/textures/rendered/${file}`;

const dataset = (id: string): RecipeDataset => ({
  schemaVersion: 1,
  recipes: [],
  resources: [],
  datasetVersionId: id,
  gtnhVersion: "2.9",
  sourceInfo: { sourceId: "unknown", generatedAt: "2026-09-10" },
  oreDictionary: {},
  recipeMaps: [],
  generatedAt: "2026-09-10",
});

const manifest = (ids: string[]): DatasetManifest =>
  ({ schemaVersion: 1, versions: ids.map((id) => ({ id })), latestStableVersion: ids[0] }) as unknown as DatasetManifest;

const RECIPE: Recipe = {
  id: "mix",
  name: "Mixer: Dust",
  machineType: "Mixer",
  minimumTier: "LV",
  durationTicks: 20,
  eut: 30,
  inputs: [{ kind: "item", id: "a", amount: 1, iconPath: OLD("a-1.png") }],
  outputs: [{ kind: "item", id: "b", amount: 1, iconPath: OLD("b-1.png") }],
} as Recipe;

function savedPlan(): FactoryProject {
  return {
    schemaVersion: PROJECT_SCHEMA_VERSION,
    id: "saved",
    name: "saved",
    solveMode: true,
    planMode: true,
    recipes: [RECIPE],
    nodes: [
      {
        id: "n",
        recipeId: "mix",
        machineCount: 1,
        parallel: 1,
        overclockTier: "LV",
        enabled: true,
        position: { x: 0, y: 0 },
        recipeInputOverrides: { "0": { kind: "item", id: "a", amount: 1, iconPath: OLD("a-1.png") } },
      },
    ],
    storages: [{ id: "d", kind: "item", resourceId: "b", iconPath: OLD("b-1.png"), position: { x: 400, y: 0 } }],
    edges: [],
    fuelProfiles: [],
  } as FactoryProject;
}

const oldPaths = (project: FactoryProject) => JSON.stringify(project).match(/\/datasets\/gtnh\/old\//g)?.length ?? 0;

beforeEach(() => useFactoryStore.getState().setDatasetManifest(manifest(["new"]), "/datasets/gtnh/datasets.manifest.json"));
afterEach(() => useFactoryStore.getState().clearDataset());

describe("a plan saved on a dataset the site no longer serves", () => {
  it("opens with the current dataset's icons on every path a plan lands by", () => {
    const store = useFactoryStore.getState();
    store.setDataset(dataset("new"));
    for (const land of [store.setProject, store.markHydratedProject, store.loadViewedProject]) {
      land(savedPlan());
      const project = useFactoryStore.getState().project;
      expect(oldPaths(project)).toBe(0);
      expect(project.storages![0]!.iconPath).toBe(NEW("b-1.png"));
      expect(project.nodes[0]!.recipeInputOverrides!["0"]!.iconPath).toBe(NEW("a-1.png"));
      expect(project.recipes[0]!.inputs[0]!.iconPath).toBe(NEW("a-1.png"));
    }
  });

  it("moves a plan that landed before the dataset once the dataset arrives", () => {
    useFactoryStore.getState().markHydratedProject(savedPlan());
    expect(oldPaths(useFactoryStore.getState().project)).toBeGreaterThan(0);
    useFactoryStore.getState().setDataset(dataset("new"));
    expect(oldPaths(useFactoryStore.getState().project)).toBe(0);
  });

  it("keeps paths into a version the site still serves", () => {
    useFactoryStore.getState().setDatasetManifest(manifest(["new", "old"]), "/datasets/gtnh/datasets.manifest.json");
    useFactoryStore.getState().setDataset(dataset("new"));
    useFactoryStore.getState().setProject(savedPlan());
    expect(useFactoryStore.getState().project.storages![0]!.iconPath).toBe(OLD("b-1.png"));
  });

  it("moves what a live batch brings, and never sends the move as an edit", () => {
    useFactoryStore.getState().setDataset(dataset("new"));
    useFactoryStore.getState().markHydratedProject({ ...savedPlan(), storages: [] });
    const drawer = savedPlan().storages![0]!;
    const conversion = useFactoryStore
      .getState()
      .applyLiveOps([{ o: "put", c: "storages", id: drawer.id, v: drawer as unknown as { id: string } }]);
    expect(conversion).toBeUndefined();
    expect(useFactoryStore.getState().project.storages![0]!.iconPath).toBe(NEW("b-1.png"));
  });
});

describe("recipes the dataset lacks", () => {
  it("are kept for the loaded version only, and forgotten when another version loads", () => {
    const store = useFactoryStore.getState();
    store.setDataset(dataset("new"));
    store.noteRecipesNotInDataset("new", ["gone"]);
    store.noteRecipesNotInDataset("other", ["elsewhere"]);
    expect([...useFactoryStore.getState().recipesNotInDataset]).toEqual(["gone"]);
    store.setDataset(dataset("new"));
    expect(useFactoryStore.getState().recipesNotInDataset.has("gone")).toBe(true);
    store.setDatasetManifest(manifest(["new", "newer"]), "/datasets/gtnh/datasets.manifest.json");
    store.setDataset(dataset("newer"));
    expect(useFactoryStore.getState().recipesNotInDataset.size).toBe(0);
  });
});
