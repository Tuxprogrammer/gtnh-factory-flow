// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Recipe, ResourceAmount } from "@/lib/model/types";
import type { DatasetManifest, DatasetVersion } from "@/lib/datasets/types";
import { PROJECT_SCHEMA_VERSION } from "@/lib/model/types";
import { applyRecipeInputOverrides } from "@/lib/model/recipe-input-overrides";
import { useFactoryStore } from "@/store/factory-store";
import { RecipeBrowser } from "./RecipeBrowser";

// A slot written as one concrete item that also takes others (the dataset's Coke Oven log slot is "Oak Log" with every
// log as an alternative; the Arc Furnace's Coal Dust slot takes Charcoal Dust). The server leaves such a slot as written
// when a use is browsed, so the add itself must keep the browsed item in it, as the preview card already shows.
const datasetVersion: DatasetVersion = {
  id: "test-version",
  gtnhVersion: "test",
  channel: "daily",
  publishedAt: "2026-05-25T00:00:00.000Z",
  manifestPath: "manifest.json",
  recipeDatasetPath: "recipes.json",
  sourceInfo: { sourceId: "recex", generatedAt: "2026-05-25T00:00:00.000Z" },
};

const oakLog = {
  kind: "item",
  id: "minecraft:log@32767",
  amount: 1,
  displayName: "Oak Log",
} as const;
const rubberWood = {
  kind: "item",
  id: "ic2:blockrubwood",
  amount: 1,
  displayName: "Rubber Wood",
} as const;
const coalDust = {
  kind: "item",
  id: "gregtech:gt.metaitem.01@2535",
  amount: 1,
  displayName: "Coal Dust",
} as const;
const charcoalDust = {
  kind: "item",
  id: "gregtech:gt.metaitem.01@2536",
  amount: 1,
  displayName: "Charcoal Dust",
} as const;

function logSlot(amount: number): ResourceAmount {
  return { ...oakLog, amount, alternatives: [{ ...oakLog }, { ...rubberWood }] };
}

const cokeOven: Recipe = {
  id: "coke-oven-charcoal",
  name: "Coke Oven: Charcoal",
  machineType: "Coke Oven",
  minimumTier: "ULV",
  durationTicks: 29,
  eut: 0,
  inputs: [logSlot(1)],
  outputs: [{ kind: "item", id: "minecraft:coal@1", amount: 1, displayName: "Charcoal" }],
};

const industrialCokeOven: Recipe = {
  id: "industrial-coke-oven-charcoal",
  name: "Industrial Coke Oven: Charcoal",
  machineType: "Industrial Coke Oven",
  minimumTier: "LV",
  durationTicks: 640,
  eut: 96,
  inputs: [
    logSlot(16),
    {
      kind: "item",
      id: "gregtech:gt.integrated_circuit@1",
      amount: 1,
      displayName: "Programmed Circuit 1",
      consumed: false,
    },
  ],
  outputs: [{ kind: "item", id: "minecraft:coal@1", amount: 20, displayName: "Charcoal" }],
};

const arcFurnace: Recipe = {
  id: "arc-furnace-cast-iron",
  name: "Arc Furnace: Cast Iron Ingot",
  machineType: "Arc Furnace",
  minimumTier: "LV",
  durationTicks: 200,
  eut: 30,
  inputs: [
    { kind: "item", id: "minecraft:iron_ingot", amount: 3, displayName: "Iron Ingot" },
    { ...coalDust, alternatives: [{ ...coalDust }, { ...charcoalDust }] },
  ],
  outputs: [
    {
      kind: "item",
      id: "gregtech:gt.metaitem.01@11304",
      amount: 3,
      displayName: "Cast Iron Ingot",
    },
  ],
};

const loader = vi.hoisted(() => ({ recipe: undefined as unknown as Recipe }));

vi.mock("@/lib/datasets/browser-loader", () => ({
  getRecipeDatasetRecipe: vi.fn(async () => loader.recipe),
  queryRecipeDatasetRecipes: vi.fn(async () => ({
    recipes: [{ ...loader.recipe, recipeMap: loader.recipe.machineType, slots: [] }],
    total: 1,
    recipeMaps: [loader.recipe.machineType],
    offset: 0,
    limit: 120,
    hasMore: false,
  })),
  queryRecipeDatasetResources: vi.fn(async () => ({
    resources: [],
    total: 0,
    offset: 0,
    limit: 6,
    hasMore: false,
  })),
}));

async function addFromUsesOf(
  recipe: Recipe,
  browsed: Pick<ResourceAmount, "kind" | "id" | "displayName">,
) {
  loader.recipe = recipe;
  useFactoryStore.getState().browseResource(browsed, "uses");
  render(<RecipeBrowser onLoadDatasetVersion={() => {}} />);
  // A generator that burns the item answers too, and its tab comes first: pick the recipe's own machine.
  fireEvent.click(await screen.findByRole("tab", { name: recipe.machineType }));
  await screen.findAllByText(recipe.machineType);
  fireEvent.click(await screen.findByLabelText("Add recipe node"));
  await waitFor(() => expect(useFactoryStore.getState().project.nodes).toHaveLength(1));
  const { project } = useFactoryStore.getState();
  const node = project.nodes[0]!;
  return applyRecipeInputOverrides(
    project.recipes.find((entry) => entry.id === node.recipeId)!,
    node,
  );
}

describe("RecipeBrowser: an add from a browsed alternative", () => {
  afterEach(cleanup);

  beforeEach(() => {
    vi.clearAllMocks();
    vi.stubGlobal(
      "ResizeObserver",
      class {
        observe() {}
        unobserve() {}
        disconnect() {}
      },
    );
    const manifest: DatasetManifest = {
      schemaVersion: 1,
      latestDailyVersion: datasetVersion.id,
      versions: [datasetVersion],
    };
    useFactoryStore.getState().setProject({
      schemaVersion: PROJECT_SCHEMA_VERSION,
      id: "recipe-browser-alternatives",
      name: "Alternatives",
      fuelProfiles: [],
      recipes: [],
      nodes: [],
      edges: [],
    });
    useFactoryStore.getState().setDatasetManifest(manifest, "/datasets/manifest.json");
  });

  it("keeps Rubber Wood in a Coke Oven slot written as Oak Log", async () => {
    const effective = await addFromUsesOf(cokeOven, rubberWood);
    expect(effective.inputs[0]).toMatchObject({
      id: "ic2:blockrubwood",
      displayName: "Rubber Wood",
      amount: 1,
    });
  });

  it("keeps the slot's own amount when the browsed log stands in for sixteen", async () => {
    const effective = await addFromUsesOf(industrialCokeOven, rubberWood);
    expect(effective.inputs[0]).toMatchObject({ id: "ic2:blockrubwood", amount: 16 });
  });

  it("keeps Charcoal Dust in an Arc Furnace slot written as Coal Dust", async () => {
    const effective = await addFromUsesOf(arcFurnace, charcoalDust);
    expect(effective.inputs[1]).toMatchObject({
      id: "gregtech:gt.metaitem.01@2536",
      displayName: "Charcoal Dust",
    });
  });

  it("still takes the slot's own item when that is what was browsed", async () => {
    const effective = await addFromUsesOf(industrialCokeOven, oakLog);
    expect(effective.inputs[0]).toMatchObject({ id: "minecraft:log@32767", amount: 16 });
  });
});
