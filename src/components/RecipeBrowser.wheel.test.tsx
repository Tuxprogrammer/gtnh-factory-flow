// @vitest-environment jsdom

import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Recipe } from "@/lib/model/types";
import type { DatasetManifest, DatasetVersion } from "@/lib/datasets/types";
import { PROJECT_SCHEMA_VERSION } from "@/lib/model/types";
import { useFactoryStore } from "@/store/factory-store";
import { RecipeBrowser } from "./RecipeBrowser";

// A slot that takes several items shows "Scroll to change it": one notch of the wheel must move it one item, though a
// high-resolution wheel sends several events for the notch.
const datasetVersion: DatasetVersion = {
  id: "test-version",
  gtnhVersion: "test",
  channel: "daily",
  publishedAt: "2026-05-25T00:00:00.000Z",
  manifestPath: "manifest.json",
  recipeDatasetPath: "recipes.json",
  sourceInfo: { sourceId: "recex", generatedAt: "2026-05-25T00:00:00.000Z" },
};

const woods = ["Oak Log", "Birch Log", "Spruce Log", "Jungle Log", "Acacia Log", "Dark Oak Log"].map(
  (displayName, index) => ({
    kind: "item" as const,
    id: `minecraft:log@${index}`,
    amount: 1,
    displayName,
  }),
);

const cokeOven: Recipe = {
  id: "coke-oven-charcoal",
  name: "Coke Oven: Charcoal",
  machineType: "Coke Oven",
  minimumTier: "ULV",
  durationTicks: 29,
  eut: 0,
  inputs: [{ ...woods[0]!, alternatives: woods.map((wood) => ({ ...wood })) }],
  outputs: [{ kind: "item", id: "minecraft:coal@1", amount: 1, displayName: "Charcoal" }],
};

vi.mock("@/lib/datasets/browser-loader", () => ({
  getRecipeDatasetRecipe: vi.fn(async () => cokeOven),
  queryRecipeDatasetRecipes: vi.fn(async () => ({
    recipes: [{ ...cokeOven, recipeMap: cokeOven.machineType, slots: [] }],
    total: 1,
    recipeMaps: [cokeOven.machineType],
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

describe("RecipeBrowser: the wheel over a slot with several items", () => {
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

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
      id: "recipe-browser-wheel",
      name: "Wheel",
      fuelProfiles: [],
      recipes: [],
      nodes: [],
      edges: [],
    });
    useFactoryStore.getState().setDatasetManifest(manifest, "/datasets/manifest.json");
  });

  it("moves one item for a notch that arrives as five events", async () => {
    useFactoryStore.getState().browseResource(cokeOven.outputs[0]!, "recipes");
    render(<RecipeBrowser onLoadDatasetVersion={() => {}} />);
    const card = (await screen.findByLabelText("Add recipe node")).closest("article")!;
    const row = card.querySelector<HTMLElement>(".cc-row")!;
    const shown = () => card.textContent ?? "";
    expect(shown()).toContain("Oak Log");

    // Each event lands in its own render, as the browser's do.
    const notch = async (deltaY: number) => {
      for (let event = 0; event < 5; event += 1) {
        await act(async () => {
          fireEvent.wheel(row, { deltaY });
        });
      }
    };
    await notch(24);
    expect(shown()).toContain("Birch Log");

    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 90));
    });
    await notch(24);
    expect(shown()).toContain("Spruce Log");
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 90));
    });
    await notch(-24);
    expect(shown()).toContain("Birch Log");
  });
});
