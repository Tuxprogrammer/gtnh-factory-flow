import { describe, expect, it } from "vitest";
import type { FactoryNode, MachineTier, Recipe } from "@/lib/model/types";
import { enrichPassiveProductionRecipe } from "@/lib/model/passive-production";
import { applyRecipeInputOverrides } from "@/lib/model/recipe-input-overrides";
import { getMachineStructuralParallels } from "@/lib/solver/machine-effects";
import { getOverclockedRecipeStats } from "@/lib/solver/overclock";
import { isMultiblockRecipe } from "@/lib/solver/power";
import { getNodePowerReport } from "@/lib/solver/power-report";
import { prefersCuratedMachineMath } from "@/lib/solver/runtime-calculation";
import {
  ALGAE_COMPOST,
  HEAVY_COMPOST_ID,
  algaePondCompostPerRun,
  getAlgaePondControls,
} from "./algae-pond";

// GT5U 5.09.54.265 MTEAlgaePond and RecipeLoaderAlgaePond. The dataset carries each tier's recipe as NEI shows it:
// map "Algae Pond", ULV, 0 EU/t, no inputs, special value = the tier, durations at this pack's 1/64 rate, and the
// generic OverclockCalculator ladder that halves the duration per tier.
function pondRecipe(tier: number, durationTicks: number): Recipe {
  return {
    id: `algae-${tier}`,
    name: "Algae Pond: Algae Biomass",
    machineType: "Algae Pond",
    minimumTier: "ULV",
    eut: 0,
    durationTicks,
    specialValue: tier,
    metadata: { recipeMapId: "gt.recipe.algae_pond", specialValue: tier },
    nei: { additionalInfo: [`Special value: ${tier}`] },
    inputs: [],
    outputs: [{ kind: "item", id: "miscutils:item.basicagrichemitem", amount: 10 }],
    runtimeCalculation: {
      oracleEligible: true,
      sourceKind: "gregtech-overclock-calculator",
      status: "computed",
      variants: [
        { id: "tier-ulv", label: "ULV", overclockTier: "ULV", durationTicks, eut: 0, parallel: 1 },
        {
          id: "tier-mv",
          label: "MV",
          overclockTier: "MV",
          durationTicks: Math.ceil(durationTicks / 2),
          eut: 0,
          parallel: 1,
        },
      ],
    },
  } as Recipe;
}

function hatch(tier: MachineTier, compost?: boolean): FactoryNode {
  return {
    overclockTier: tier,
    hatchVoltageTier: tier,
    hatchAmps: 1,
    machineConfigTiers:
      compost === undefined ? {} : { [ALGAE_COMPOST]: compost ? "compost" : "none" },
  } as unknown as FactoryNode;
}

const tier0 = enrichPassiveProductionRecipe(pondRecipe(0, 32));
const tier3 = enrichPassiveProductionRecipe(pondRecipe(3, 22));
const tier9 = enrichPassiveProductionRecipe(pondRecipe(9, 1));
const tier15 = enrichPassiveProductionRecipe(pondRecipe(15, 1));

describe("Algae Farm (MTEAlgaePond)", () => {
  it("draws 9/10 of its hatch tier's voltage and needs that tier: the dataset says ULV and 0 EU/t", () => {
    expect([tier0, tier3, tier9].map((recipe) => [recipe.minimumTier, recipe.eut])).toEqual([
      ["ULV", 7],
      ["HV", 460],
      ["UHV", 1887436],
    ]);
    // No hatch reaches tier 15; it grows only with compost on a MAX hatch.
    expect([tier15.minimumTier, tier15.eut]).toEqual(["MAX", 1932735276]);
  });

  it("is a one-parallel multiblock on curated math that never overclocks", () => {
    expect(isMultiblockRecipe(tier3)).toBe(true);
    expect(prefersCuratedMachineMath(tier3)).toBe(true);
    expect(getMachineStructuralParallels(tier3, hatch("HV"))).toBe(1);
    expect(getOverclockedRecipeStats(tier0, hatch("ULV"))).toMatchObject({
      durationTicks: 32,
      eut: 7,
      overclockSteps: 0,
    });
    // The exported ladder halves the duration at MV; the game runs the recipe as written (ofNoOverclock).
    expect(getOverclockedRecipeStats(tier0, hatch("MV"))).toMatchObject({
      durationTicks: 32,
      eut: 7,
    });
  });

  it("grows a recipe only on one hatch of its tier", () => {
    const own = getNodePowerReport(tier3, hatch("HV"));
    expect(own.recipeGateReason).toBeUndefined();
    expect(own.state).toBe("ok");
    for (const tier of ["MV", "EV"] as const) {
      const report = getNodePowerReport(tier3, hatch(tier));
      expect(report.state).toBe("over-tier");
      expect(report.recipeGateReason).toBe(
        "Needs one HV energy hatch. The pond grows the recipe of its hatch's tier.",
      );
    }
  });

  it("with compost grows one tier above its hatch, at that hatch's draw, using compostForTier compost a run", () => {
    expect([0, 1, 2, 3, 7, 8, 14].map(algaePondCompostPerRun)).toEqual([1, 1, 2, 4, 64, 64, 64]);
    expect(getAlgaePondControls(tier0)).toEqual([]);
    expect(getAlgaePondControls(tier3)[0]).toMatchObject({ id: ALGAE_COMPOST, defaultKey: "none" });

    const composted = hatch("MV", true);
    expect(getOverclockedRecipeStats(tier3, composted)).toMatchObject({
      durationTicks: 22,
      eut: 115,
    });
    const report = getNodePowerReport(tier3, composted);
    expect(report.recipeGateReason).toBeUndefined();
    expect(report.state).toBe("ok");
    expect(getNodePowerReport(tier3, hatch("HV", true)).recipeGateReason).toBe(
      "Needs one MV energy hatch. With compost the pond grows the recipe one tier above its hatch.",
    );
    const inputs = applyRecipeInputOverrides(tier3, composted).inputs;
    expect(inputs).toEqual([expect.objectContaining({ id: HEAVY_COMPOST_ID, amount: 2 })]);
    expect(
      applyRecipeInputOverrides(applyRecipeInputOverrides(tier3, composted), composted).inputs,
    ).toHaveLength(1);
    expect(applyRecipeInputOverrides(tier3, hatch("HV")).inputs).toEqual([]);

    // Tier 9 on a UV hatch: 64 compost a run, 471,859 EU/t.
    expect(getOverclockedRecipeStats(tier9, hatch("UV", true)).eut).toBe(471859);
    expect(applyRecipeInputOverrides(tier9, hatch("UV", true)).inputs[0].amount).toBe(64);
  });

  it("starts tier 15 on compost, the only way it grows", () => {
    expect(getAlgaePondControls(tier15)[0].defaultKey).toBe("compost");
    expect(getNodePowerReport(tier15, hatch("MAX")).recipeGateReason).toBeUndefined();
    expect(applyRecipeInputOverrides(tier15, hatch("MAX")).inputs[0]).toMatchObject({
      id: HEAVY_COMPOST_ID,
      amount: 64,
    });
    expect(getNodePowerReport(tier15, hatch("MAX", false)).recipeGateReason).toBe(
      "No energy hatch reaches tier 15. This recipe grows only with compost on a MAX hatch.",
    );
  });
});
