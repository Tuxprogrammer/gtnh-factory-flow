import { describe, expect, it } from "vitest";
import type { FactoryNode, MachineTier, Recipe, RuntimeCalculation } from "@/lib/model/types";
import { getOverclockedRecipeStats } from "./overclock";
import { getNodePowerReport } from "./power-report";
import { describePowerWorking } from "./power-working";

// The game's rule for a multiblock run (ProcessingLogic -> ParallelHelper.determineParallel
// -> OverclockCalculator), worked by hand for every expectation below:
//   cost = ceil(recipe EU/t x EU modifier x heat discount); A = voltage x amps
//   M = calculateMultiplierUnderOneTick() on the structural parallels P
//   ran = min(floor(P x M), floor(A / cost))           <- the cap this file pins
//   EU/t billed on min(P, ran); a sub-tick run takes one tick.
// Real dataset recipes (this instance's durations are 1/64 of stock), compacted.

function ladder(tier: MachineTier, durationTicks: number, eut: number): RuntimeCalculation {
  return {
    oracleEligible: true,
    sourceKind: "gregtech-overclock-calculator",
    status: "computed",
    variants: [{ id: "min", label: tier, overclockTier: tier, durationTicks, eut, parallel: 1 }],
  };
}

// gt.recipe.industrialchisel:759849b664902597 (MTEIndustrialChisel: speed x3,
// 75% EU, 16 parallels per voltage tier, normal overclocks).
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

// gt.recipe.oremill:1679d29398d64a47 (MTEIsaMill: perfect overclocks, 1 parallel).
const milling: Recipe = {
  id: "oracle:stable-2.9.0-tuxmine:gregtech:gt-recipe-oremill:1679d29398d64a47",
  name: "Milling: Milled Netherrack",
  machineType: "Milling",
  minimumTier: "IV",
  durationTicks: 19,
  eut: 7680,
  inputs: [{ kind: "item", id: "minecraft:netherrack", amount: 256 }],
  outputs: [{ kind: "item", id: "miscutils:millednetherrack", amount: 32 }],
  runtimeCalculation: ladder("IV", 19, 7680),
};

// gt.recipe.macerator:2f40264ad6adc626 on the singleblock Macerator handler.
const macerator: Recipe = {
  id: "oracle:stable-2.9.0-tuxmine:gregtech:gt-recipe-macerator:2f40264ad6adc626",
  name: "Macerator: Floral White Powder",
  machineType: "Macerator",
  minimumTier: "ULV",
  durationTicks: 7,
  eut: 2,
  inputs: [{ kind: "item", id: "botania:petal", amount: 1 }],
  outputs: [{ kind: "item", id: "botania:dye", amount: 1 }],
  machineHandlers: [
    {
      id: "macerator",
      label: "Macerator",
      kind: "single",
      machineType: "Macerator",
      minimumTier: "LV",
      maximumTier: "UMV",
    },
  ],
};

function hatch(tier: MachineTier, amps: number): FactoryNode {
  return { overclockTier: tier, hatchVoltageTier: tier, hatchAmps: amps } as unknown as FactoryNode;
}

function run(recipe: Recipe, node: FactoryNode) {
  const stats = getOverclockedRecipeStats(recipe, node);
  const report = getNodePowerReport(recipe, node);
  const perTick = report.parallels / stats.durationTicks;
  return {
    parallels: report.parallels,
    perTick: Math.round(perTick * 1e9) / 1e9,
    perSecond: Math.round(recipe.outputs[0]!.amount * perTick * 20 * 1e6) / 1e6,
    drawEuT: report.drawEuT,
  };
}

describe("parallels banked under one tick are capped by the power supply", () => {
  it("runs a speed-bonus machine at what its hatch pays for (Industrial Chisel, one EV hatch)", () => {
    // P = 16 x 4 = 64, M = ceil(1 / (1/3)) = 3, cap = 2048 / ceil(11.25) = 170.
    // EU/t stays the 64 parallels' 720: OverclockCalculator bills min(P, ran).
    expect(run(chisel, hatch("EV", 1))).toEqual({
      parallels: 64,
      perTick: 170,
      perSecond: 3400,
      drawEuT: 720,
    });
  });

  it("holds a power-starved chisel to the parallels its hatch pays for, not three times that", () => {
    // LV: 32 / 12 = 2 parallels, banked to 6 but capped at 2 a tick.
    expect(run(chisel, hatch("LV", 1))).toMatchObject({ parallels: 2, perTick: 2, perSecond: 40 });
    // MV: the cap divides by the CEILED cost, 128 / 12 = 10 (not 128 / 11.25 = 11).
    expect(run(chisel, hatch("MV", 1))).toEqual({
      parallels: 10,
      perTick: 10,
      perSecond: 200,
      drawEuT: 112.5,
    });
  });

  it("leaves a run the supply covers alone (Industrial Chisel, four EV hatches)", () => {
    // P = 80 (summed IV voltage), one overclock, M = 6: 480 a tick under a cap of 682.
    expect(run(chisel, hatch("EV", 4))).toMatchObject({
      parallels: 80,
      perTick: 480,
      perSecond: 9600,
    });
  });

  it("leaves a perfect-overclock machine alone (IsaMill on one UV hatch)", () => {
    // Three perfect overclocks: 19 / 64 ticks, M = ceil(64 / 19) = 4, cap = 524288 / 7680 = 68.
    expect(run(milling, hatch("UV", 1))).toEqual({
      parallels: 1,
      perTick: 4,
      perSecond: 2560,
      drawEuT: 491520,
    });
  });

  it("leaves a singleblock at one recipe a tick (Macerator at IV)", () => {
    const node = { overclockTier: "IV", machineHandlerId: "macerator" } as unknown as FactoryNode;
    expect(getOverclockedRecipeStats(macerator, node).durationTicks).toBe(1);
    expect(run(macerator, node)).toMatchObject({ parallels: 1, perTick: 1, perSecond: 20 });
  });

  it("writes a capped run's time as a decimal, a whole fraction only when it is one", () => {
    const time = (node: FactoryNode, budget: number) =>
      describePowerWorking(chisel, node, budget).rows.find((row) => row.id === "time")?.supplied;
    // 64 parallels at 170 a tick: 64 / 170 = 0.376 ticks, not "1/3 t".
    expect(time(hatch("EV", 1), 2048)).toBe("0.38 t");
    // Four EV hatches: one overclock past a third of a tick, six a tick.
    expect(time(hatch("EV", 4), 8192)).toBe("1/6 t");
  });
});
