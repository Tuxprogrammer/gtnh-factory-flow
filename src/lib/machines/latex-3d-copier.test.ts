import { describe, expect, it } from "vitest";
import type { FactoryNode, MachineTier, Recipe, RuntimeCalculation } from "@/lib/model/types";
import {
  getMachineDurationMultiplier,
  getMachineEutMultiplier,
  getMachineStructuralParallels,
} from "@/lib/solver/machine-effects";
import { getOverclockedRecipeStats } from "@/lib/solver/overclock";
import { isMultiblockRecipe } from "@/lib/solver/power";
import { getNodePowerReport } from "@/lib/solver/power-report";
import { prefersCuratedMachineMath } from "@/lib/solver/runtime-calculation";

// Each machine is the only one on its recipe map, so the dataset exports no
// handler list and the recipes arrive under the map's name. These are real
// dataset recipes (durations at this instance's 1/64), compacted to the fields
// the machine math reads, with the exported 2x-per-tier runtime ladder.
function ladder(minimumTier: MachineTier, durationTicks: number, eut: number): RuntimeCalculation {
  return {
    oracleEligible: true,
    sourceKind: "gregtech-overclock-calculator",
    status: "computed",
    variants: [
      {
        id: "min",
        label: minimumTier,
        overclockTier: minimumTier,
        durationTicks,
        eut,
        parallel: 1,
      },
      { id: "ev", label: "EV", overclockTier: "EV", durationTicks: 1, eut: 448, parallel: 1 },
    ],
  };
}

// gt.recipe.cable:d739b82bfc359e27, MTELatex's map.
const cable: Recipe = {
  id: "oracle:stable-2.9.0-tuxmine:gregtech:gt-recipe-cable:d739b82bfc359e27",
  name: "Cable Coating: 1x Annealed Copper Cable",
  machineType: "Cable Coating",
  minimumTier: "ULV",
  durationTicks: 2,
  eut: 7,
  inputs: [
    { kind: "item", id: "gregtech:gt.blockmachines@1380", amount: 1 },
    { kind: "fluid", id: "molten.rubber", amount: 144 },
  ],
  outputs: [{ kind: "item", id: "gregtech:gt.blockmachines@1386", amount: 1 }],
  runtimeCalculation: ladder("ULV", 2, 7),
};

// gt.recipe.industrialchisel:759849b664902597, MTEIndustrialChisel's map.
const chisel: Recipe = {
  id: "oracle:stable-2.9.0-tuxmine:gregtech:gt-recipe-industrialchisel:759849b664902597",
  name: "Industrial Chisel: Quite Clear Glass",
  machineType: "Industrial Chisel",
  minimumTier: "LV",
  durationTicks: 1,
  eut: 15,
  inputs: [{ kind: "item", id: "minecraft:glass", amount: 1 }],
  outputs: [{ kind: "item", id: "enderio:blockfusedquartz@1", amount: 1 }],
  runtimeCalculation: ladder("LV", 1, 15),
};

function hatches(tier: MachineTier, amps: number, machineConfigTiers = {}): FactoryNode {
  return {
    overclockTier: tier,
    hatchVoltageTier: tier,
    hatchAmps: amps,
    machineConfigTiers,
  } as unknown as FactoryNode;
}

function perSecond(recipe: Recipe, node: FactoryNode): number {
  const stats = getOverclockedRecipeStats(recipe, node);
  const { parallels } = getNodePowerReport(recipe, node);
  return (recipe.outputs[0]!.amount * parallels * 20) / stats.durationTicks;
}

describe("L.A.T.E.X. and Industrial 3D Copying Machine under their map names", () => {
  it("are multiblocks on the curated math", () => {
    for (const recipe of [cable, chisel]) {
      expect(isMultiblockRecipe(recipe)).toBe(true);
      expect(prefersCuratedMachineMath(recipe)).toBe(true);
    }
  });

  it("runs the L.A.T.E.X. at twice speed, 85% EU, 8 parallels per voltage tier (MTELatex)", () => {
    const ev = hatches("EV", 1);
    expect(getMachineDurationMultiplier(cable, ev)).toBe(0.5);
    expect(getMachineEutMultiplier(cable, ev)).toBe(0.85);
    expect(getMachineStructuralParallels(cable, ev)).toBe(32);
    // An Elastic Singularity in the controller slot doubles the base.
    const singularity = hatches("EV", 1, { latexSingularity: "elastic-singularity" });
    expect(getMachineStructuralParallels(cable, singularity)).toBe(64);
    // One EV hatch: 32 parallels at 5.95 EU/t buy one overclock past the 1
    // tick run, which the game banks as 64 parallels a tick.
    expect(perSecond(cable, ev)).toBe(1280);
  });

  it("runs the 3D Copying Machine at three times speed, 75% EU, 16 parallels per voltage tier (MTEIndustrialChisel)", () => {
    const ev = hatches("EV", 1);
    expect(getMachineDurationMultiplier(chisel, ev)).toBeCloseTo(1 / 3, 12);
    expect(getMachineEutMultiplier(chisel, ev)).toBe(0.75);
    expect(getMachineStructuralParallels(chisel, ev)).toBe(64);
    // Four EV hatches sum to IV voltage (80 parallels at 11.25 EU/t, one
    // overclock); the third-tick run and the overclock bank as 682 a tick (the
    // game's under-one-tick rule), 682 x 20 = 13,640 a second.
    expect(perSecond(chisel, hatches("EV", 4))).toBe(13640);
  });
});
