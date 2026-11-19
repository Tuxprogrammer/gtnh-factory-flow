import { describe, expect, it } from "vitest";
import type { FactoryNode, MachineTier, Recipe } from "@/lib/model/types";
import { getMachineStructuralParallels } from "@/lib/solver/machine-effects";
import { getOverclockedRecipeStats } from "@/lib/solver/overclock";
import { isMultiblockRecipe } from "@/lib/solver/power";
import { prefersCuratedMachineMath } from "@/lib/solver/runtime-calculation";

// The dataset exports no handler list for a map whose only machine is one
// multiblock, so these recipes reach the solver under the map's own name. The
// exported runtime ladder is OverclockCalculator's plain 2x-per-tier one,
// which the game does not use for either machine.
function mapRecipe(
  machineType: string,
  minimumTier: MachineTier,
  eut: number,
  durationTicks: number,
): Recipe {
  return {
    id: machineType,
    name: `${machineType} recipe`,
    machineType,
    minimumTier,
    eut,
    durationTicks,
    inputs: [{ kind: "item", id: "ore", amount: 1 }],
    outputs: [{ kind: "item", id: "milled", amount: 32 }],
    runtimeCalculation: {
      oracleEligible: true,
      sourceKind: "gregtech-overclock-calculator",
      status: "computed",
      variants: [
        {
          id: "a",
          label: minimumTier,
          overclockTier: minimumTier,
          durationTicks,
          eut,
          parallel: 1,
        },
        {
          id: "b",
          label: "ZPM",
          overclockTier: "ZPM",
          durationTicks: durationTicks / 4,
          eut: eut * 16,
          parallel: 1,
        },
      ],
    },
  };
}

function hatch(tier: MachineTier, amps: number): FactoryNode {
  return { overclockTier: tier, hatchVoltageTier: tier, hatchAmps: amps } as unknown as FactoryNode;
}

// Milled netherrack with an alumina ball: 60 s at IV (BaseItemMilledOre).
const milling = mapRecipe("Milling", "IV", 7680, 1200);
// Netherrack froth: 2 minutes at LuV (RecipeLoaderMilling.addFlotationRecipes).
const flotation = mapRecipe("Flotation Cell", "LuV", 30720, 2400);

describe("IsaMill and Flotation Cell Regulator", () => {
  it("are multiblocks on the curated math under their recipe map names", () => {
    for (const recipe of [milling, flotation]) {
      expect(isMultiblockRecipe(recipe)).toBe(true);
      expect(prefersCuratedMachineMath(recipe)).toBe(true);
      // ProcessingLogic's default: neither machine sets a parallel count.
      expect(getMachineStructuralParallels(recipe, hatch("ZPM", 1))).toBe(1);
    }
  });

  it("overclock perfectly: 4x speed for 4x power, as MTEIsaMill's processing logic enables", () => {
    // In game, LuV runs the 60 s recipe in 15 s and ZPM (or 4A LuV) in 3.75 s.
    expect(getOverclockedRecipeStats(milling, hatch("IV", 1)).durationTicks).toBe(1200);
    expect(getOverclockedRecipeStats(milling, hatch("LuV", 1))).toMatchObject({
      durationTicks: 300,
      eut: 30720,
      perfectOverclockSteps: 1,
    });
    expect(getOverclockedRecipeStats(milling, hatch("ZPM", 1)).durationTicks).toBe(75);
    expect(getOverclockedRecipeStats(milling, hatch("LuV", 4)).durationTicks).toBe(75);
  });

  it("overclock perfectly, as MTEFrothFlotationCell's processing logic enables", () => {
    expect(getOverclockedRecipeStats(flotation, hatch("LuV", 1)).durationTicks).toBe(2400);
    expect(getOverclockedRecipeStats(flotation, hatch("ZPM", 1))).toMatchObject({
      durationTicks: 600,
      eut: 122880,
      perfectOverclockSteps: 1,
    });
    expect(getOverclockedRecipeStats(flotation, hatch("UV", 1)).durationTicks).toBe(150);
  });
});
