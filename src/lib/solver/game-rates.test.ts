import { afterEach, describe, expect, it } from "vitest";
import type { FactoryNode, Recipe } from "@/lib/model/types";
import { applyMachineHandlerToRecipe } from "@/lib/model/recipe-rules";
import { enrichPassiveProductionRecipe } from "@/lib/model/passive-production";
import { getGameRates, resolveGameRates, setActiveGameRates } from "@/lib/model/game-rates";
import { datasetManifestSchema } from "@/lib/datasets/schemas";
import { getMachineOutputMultiplier } from "./machine-effects";

/**
 * The pack's rate mod (GTNHRates) as the dataset's `gameRates` record: Forestry's housings get beesYield items from
 * each produce roll, the Crop Manager multiplies its drop rounds by ic2CropsYield, and a dataset with no record reads
 * as 1 so nothing moves. The Industrial Apiary, the Mega Apiary and the Industrial Farm do not go through those hooks.
 */
const PACK = { beesYield: 4, ic2CropsYield: 4, recipeDurationDivisor: 64 };

afterEach(() => setActiveGameRates(undefined));

type NodeLike = Pick<
  FactoryNode,
  "machineConfigTiers" | "machineHandlerId" | "overclockTier" | "coilTier"
>;

function beeRecipe(chance = 0.3): Recipe {
  return enrichPassiveProductionRecipe({
    machineHandlers: [
      {
        id: "apiary",
        label: "Apiary",
        machineType: "Apiary",
        minimumTier: "NONE",
        eut: 0,
        kind: "single",
      },
    ],
    id: "bee-test",
    name: "Bee Produce: Test Bee",
    machineType: "Bee Produce",
    minimumTier: "NONE",
    durationTicks: 550,
    eut: 0,
    inputs: [
      {
        kind: "item",
        id: "factoryflow:bee_species:test",
        amount: 1,
        displayName: "Test Bee",
        consumed: false,
      },
    ],
    outputs: [
      { kind: "item", id: "forestry:beecombs@0", amount: 1, displayName: "Honey Comb", chance },
    ],
    source: { recipeMap: "Bee Produce" },
  } as Recipe);
}

function beeMultiplier(housing: string, tiers: Record<string, string> = {}, chance = 0.3): number {
  const node = {
    machineConfigTiers: tiers,
    machineHandlerId: housing,
    overclockTier: "MV",
  } as unknown as NodeLike;
  const recipe = applyMachineHandlerToRecipe(beeRecipe(chance), node);
  return getMachineOutputMultiplier(recipe, node, recipe.outputs[0]!, "MV");
}

function cropRecipe(): Recipe {
  return enrichPassiveProductionRecipe({
    id: "cropsnh-crop-argentia",
    name: "Crop Farm: Argentia",
    machineType: "Crop Farm",
    minimumTier: "NONE",
    durationTicks: 3328,
    eut: 0,
    inputs: [],
    outputs: [
      { kind: "item", id: "cropsnh:materialleaf@26", amount: 2.29, displayName: "Argentia Leaf" },
    ],
    metadata: {
      cropsNh: {
        tier: 7,
        growthPoints: 1400,
        dropChance: 0.6983373,
        growthCycleTicks: 256,
        growthMultiplier: 1,
        drops: [{ id: "cropsnh:materialleaf@26", stackSize: 1, weight: 10000 }],
      },
    },
    source: { recipeMap: "Crop Farm" },
  } as Recipe);
}

function cropMultiplier(handler: string | undefined): number {
  const node = { machineConfigTiers: {}, machineHandlerId: handler } as unknown as NodeLike;
  const recipe = cropRecipe();
  return getMachineOutputMultiplier(recipe, node, recipe.outputs[0]!, "LV");
}

describe("the dataset's game rates", () => {
  it("reads 1 for each factor when the dataset has no record, or a bad one", () => {
    expect(getGameRates()).toEqual({ beesYield: 1, ic2CropsYield: 1, recipeDurationDivisor: 1 });
    expect(resolveGameRates({ beesYield: 4 })).toEqual({
      beesYield: 4,
      ic2CropsYield: 1,
      recipeDurationDivisor: 1,
    });
    expect(
      resolveGameRates({ beesYield: 0, ic2CropsYield: -2, recipeDurationDivisor: Number.NaN }),
    ).toEqual({
      beesYield: 1,
      ic2CropsYield: 1,
      recipeDurationDivisor: 1,
    });
    setActiveGameRates(PACK);
    expect(getGameRates()).toEqual(PACK);
    setActiveGameRates(undefined);
    expect(getGameRates().beesYield).toBe(1);
  });

  it("rides on a manifest version entry", () => {
    const manifest = {
      schemaVersion: 1,
      versions: [
        {
          id: "stable-2.9.0",
          gtnhVersion: "2.9.0",
          channel: "stable",
          publishedAt: "2026-11-16T00:00:00Z",
          manifestPath: "/datasets/gtnh/datasets.manifest.json",
          recipeDatasetPath: "/datasets/gtnh/stable-2.9.0/recipes.json.gz",
          sourceInfo: { sourceId: "gtnh-oracle", generatedAt: "2026-11-16T00:00:00Z" },
          gameRates: PACK,
        },
      ],
    };
    expect(datasetManifestSchema.parse(manifest).versions[0]!.gameRates).toEqual(PACK);
    expect(
      datasetManifestSchema.safeParse({
        ...manifest,
        versions: [{ ...manifest.versions[0], gameRates: { beesYield: 0 } }],
      }).success,
    ).toBe(false);
  });
});

describe("the Crop Manager's harvest rounds", () => {
  it("are unchanged without a record", () => {
    expect(cropMultiplier(undefined)).toBeCloseTo(1.05, 10);
    expect(cropMultiplier("crop-manager")).toBeCloseTo(1.05, 10);
  });

  it("take the dataset's crop yield, and the Industrial Farm does not", () => {
    const farmBefore = cropMultiplier("crop-industrial-farm");
    setActiveGameRates(PACK);
    expect(cropMultiplier(undefined)).toBeCloseTo(1.05 * 4, 10);
    expect(cropMultiplier("crop-manager")).toBeCloseTo(1.05 * 4, 10);
    expect(cropMultiplier("crop-industrial-farm")).toBeCloseTo(farmBefore, 10);
  });

  it("follow a different pack config", () => {
    setActiveGameRates({ ic2CropsYield: 2 });
    expect(cropMultiplier("crop-manager")).toBeCloseTo(1.05 * 2, 10);
  });
});

describe("a Forestry housing's produce rolls", () => {
  const frames = {
    beeFrameSlot1: "magicbees:magic",
    beeFrameSlot2: "magicbees:resilient",
    beeFrameSlot3: "magicbees:magic",
    beeSpeedGene: "blinding",
  };

  it("are unchanged without a record", () => {
    // The Apiary's F(30%) + F(15%), from bee-game-maths.test.ts.
    expect(beeMultiplier("apiary") * 0.3).toBeCloseTo(0.0841469, 6);
  });

  it("give beesYield items a roll in the Apiary, Magic Apiary and Alveary", () => {
    const base = {
      apiary: beeMultiplier("apiary"),
      magic: beeMultiplier("magic-apiary"),
      alveary: beeMultiplier("alveary"),
    };
    setActiveGameRates(PACK);
    expect(beeMultiplier("apiary")).toBeCloseTo(base.apiary * 4, 10);
    expect(beeMultiplier("magic-apiary")).toBeCloseTo(base.magic * 4, 10);
    expect(beeMultiplier("alveary")).toBeCloseTo(base.alveary * 4, 10);
  });

  it("multiply after the cap: a certain roll still gives beesYield items", () => {
    // A 100% product at p + t = 7 caps at one a roll for the first roll; the second roll at 50% does not.
    const plain = beeMultiplier("magic-apiary", frames, 1);
    setActiveGameRates(PACK);
    expect(beeMultiplier("magic-apiary", frames, 1)).toBeCloseTo(plain * 4, 10);
    // Not the cap applied to the four-fold chance: that would give 2 items a cycle at most.
    expect(plain).toBeGreaterThan(1);
  });

  it("leave the Industrial Apiary and the Mega Apiary alone", () => {
    const industrial = beeMultiplier("industrial-apiary");
    const mega = beeMultiplier("mega-apiary");
    setActiveGameRates(PACK);
    expect(beeMultiplier("industrial-apiary")).toBeCloseTo(industrial, 10);
    expect(beeMultiplier("mega-apiary")).toBeCloseTo(mega, 10);
  });
});
