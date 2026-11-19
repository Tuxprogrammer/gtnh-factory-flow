import { describe, expect, it } from "vitest";
import type { FactoryNode, MachineTier, Recipe } from "@/lib/model/types";
import { getRecipeMachineConfigTierControls } from "@/lib/model/recipe-rules";
import { normalizeHatchInput } from "@/lib/solver/hatch-input";
import { getOverclockedRecipeStats } from "@/lib/solver/overclock";
import { isMultiblockRecipe } from "@/lib/solver/power";
import { getNodePowerReport } from "@/lib/solver/power-report";
import { prefersCuratedMachineMath } from "@/lib/solver/runtime-calculation";

// gg.recipe.componentassemblyline:35e4fccde7d87e29, as this instance's dataset
// has it: no handler list (the CoAL is the only machine on its map), special
// value 9 = a UHV casing, 750 ticks at 491,520 EU/t (UV). The exported runtime
// ladder is OverclockCalculator's plain 1 A one.
const motor: Recipe = {
  id: "oracle:stable-2.9.0-tuxmine:gregtech:gg-recipe-componentassemblyline:35e4fccde7d87e29",
  name: "Component Assembly Line: Electric Motor (UHV)",
  machineType: "Component Assembly Line",
  minimumTier: "UV",
  durationTicks: 750,
  eut: 491520,
  nei: { additionalInfo: ["Special value: 9"] },
  inputs: [{ kind: "item", id: "gregtech:gt.blockmachines@11321", amount: 24 }],
  outputs: [{ kind: "item", id: "gregtech:gt.metaitem.01@32596", amount: 64 }],
  runtimeCalculation: {
    oracleEligible: true,
    sourceKind: "gregtech-overclock-calculator",
    status: "computed",
    variants: [
      { id: "uv", label: "UV", overclockTier: "UV", durationTicks: 750, eut: 491520, parallel: 1 },
      {
        id: "uhv",
        label: "UHV",
        overclockTier: "UHV",
        durationTicks: 375,
        eut: 1966080,
        parallel: 1,
      },
    ],
  },
};

function hatch(tier: MachineTier, amps: number, casing?: string): FactoryNode {
  return {
    overclockTier: tier,
    hatchVoltageTier: tier,
    hatchAmps: amps,
    ...(casing ? { machineConfigTiers: { componentAssemblyLineCasing: casing } } : {}),
  } as unknown as FactoryNode;
}

const ticks = (node: FactoryNode) => getOverclockedRecipeStats(motor, node).durationTicks;

describe("Component Assembly Line", () => {
  it("is a multiblock on the curated math, so its hatch amps count", () => {
    expect(isMultiblockRecipe(motor)).toBe(true);
    expect(prefersCuratedMachineMath(motor)).toBe(true);
    // A card saved as a 1 A singleblock at UHV loads as one UHV hatch.
    const loaded = normalizeHatchInput(motor, { overclockTier: "UHV" } as FactoryNode, true);
    expect(loaded).toMatchObject({ hatchVoltageTier: "UHV", hatchAmps: 1 });
    expect(getOverclockedRecipeStats(motor, loaded).durationTicks).toBe(375);
  });

  it("overclocks on the whole supply, multi-amp and laser hatches included (setProcessingLogicPower)", () => {
    // getMaxInputEu() at 1 A: whole power-of-four steps of the supply over 491,520 EU/t.
    expect(ticks(hatch("UHV", 1))).toBe(375);
    expect(ticks(hatch("UHV", 4))).toBe(187);
    expect(ticks(hatch("UHV", 16))).toBe(93);
    expect(ticks(hatch("UV", 64))).toBe(93);
    expect(ticks(hatch("UHV", 256))).toBe(23);
    expect(getNodePowerReport(motor, hatch("UHV", 4))).toMatchObject({
      parallels: 1,
      drawEuT: 7864320,
      state: "ok",
    });
  });

  it("starts the casing ladder at the recipe's casing and halves the time per tier above it", () => {
    const controls = getRecipeMachineConfigTierControls(motor, hatch("UHV", 4));
    const casing = controls.find((control) => control.id === "componentAssemblyLineCasing");
    expect(casing?.current.key).toBe("uhv");
    expect(casing?.tiers.map((tier) => tier.key)).toEqual([
      "uhv",
      "uev",
      "uiv",
      "umv",
      "uxv",
      "max",
    ]);
    expect(ticks(hatch("UHV", 4, "uev"))).toBe(93);
    expect(ticks(hatch("UHV", 4, "uiv"))).toBe(46);
  });

  it("runs a recipe above its hatch tier when the amps pay for it (unlimited tier skips)", () => {
    expect(getNodePowerReport(motor, hatch("HV", 4096)).state).toBe("ok");
  });
});
