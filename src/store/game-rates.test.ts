import { afterEach, describe, expect, it } from "vitest";
import { getGameRates } from "@/lib/model/game-rates";
import type { RecipeDataset } from "@/lib/datasets/types";
import { useFactoryStore } from "./factory-store";

const dataset = (gameRates?: RecipeDataset["gameRates"]): RecipeDataset => ({
  schemaVersion: 1,
  recipes: [],
  resources: [],
  datasetVersionId: "rates-test",
  gtnhVersion: "2.9",
  sourceInfo: { sourceId: "unknown", generatedAt: "2026-09-10" },
  oreDictionary: {},
  recipeMaps: [],
  ...(gameRates ? { gameRates } : {}),
  generatedAt: "2026-09-10",
});

afterEach(() => useFactoryStore.getState().clearDataset());

describe("the dataset's game rates in the store", () => {
  it("become the active rates when the dataset is set, and 1 again when it is cleared", () => {
    const store = useFactoryStore.getState();
    store.setDataset(dataset({ beesYield: 4, ic2CropsYield: 4, recipeDurationDivisor: 64 }));
    expect(getGameRates()).toEqual({ beesYield: 4, ic2CropsYield: 4, recipeDurationDivisor: 64 });
    store.clearDataset();
    expect(getGameRates()).toEqual({ beesYield: 1, ic2CropsYield: 1, recipeDurationDivisor: 1 });
  });

  it("read 1 for a dataset with no record", () => {
    const store = useFactoryStore.getState();
    store.setDataset(dataset({ beesYield: 4 }));
    store.setDataset(dataset());
    expect(getGameRates()).toEqual({ beesYield: 1, ic2CropsYield: 1, recipeDurationDivisor: 1 });
  });
});
