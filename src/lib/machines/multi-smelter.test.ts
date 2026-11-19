import { describe, expect, it } from "vitest";
import { getRecipeCoilTierControl } from "@/lib/model/recipe-rules";
import type { Recipe } from "@/lib/model/types";
import { getOverclockedRecipeStats } from "@/lib/solver/overclock";
import { getNodePowerReport } from "@/lib/solver/power-report";

// A smelt as this instance's dataset has it: the Furnace map's Multi Smelter handler carries
// no coil control, so the coil must come from the machine table.
const SMELT = {
  id: "smelt-iron",
  name: "Furnace: Iron Ingot",
  machineType: "Furnace",
  minimumTier: "NONE",
  durationTicks: 200,
  eut: 0,
  inputs: [{ kind: "item", id: "raw-iron-ore", amount: 1 }],
  outputs: [{ kind: "item", id: "iron-ingot", amount: 1 }],
  source: { recipeMap: "Furnace" },
  machineHandlers: [
    {
      id: "furnace",
      label: "Furnace",
      kind: "single",
      machineType: "Furnace",
      minimumTier: "NONE",
    },
    {
      id: "multi-smelter",
      label: "Multi Smelter",
      machineType: "Multi Smelter",
      kind: "multiblock",
      minimumTier: "NONE",
    },
  ],
} as unknown as Recipe;

const MULTI_SMELTER = { ...SMELT, machineType: "Multi Smelter" } as Recipe;

function run(tier: string, coilTier?: string) {
  const node = {
    id: "n",
    recipeId: SMELT.id,
    enabled: true,
    machineCount: 1,
    parallel: 1,
    position: { x: 0, y: 0 },
    overclockTier: tier,
    machineHandlerId: "multi-smelter",
    coilTier,
  } as never;
  const stats = getOverclockedRecipeStats(SMELT, node);
  const report = getNodePowerReport(SMELT, node);
  return [stats.durationTicks, stats.eut, report.parallels, report.drawEuT];
}

describe("Multi Smelter coils", () => {
  it("offers the fourteen heating coils, cupronickel first, although the dataset gives none", () => {
    const control = getRecipeCoilTierControl(MULTI_SMELTER, {})!;
    expect(control).toBeDefined();
    expect(control.tiers).toHaveLength(14);
    expect(control.current.key).toBe("cupronickel");
    expect(control.tiers.at(-1)!.key).toBe("eternal");
  });

  it("runs 4 << (coil level - 1) parallels: MTEMultiFurnace.checkMachine", () => {
    // HeatingCoilLevel ordinals: cupronickel is LV (2), eternal is MAX (15). A UV hatch feeds
    // 524288 / 4 parallels, more than any coil gives, so the coil alone sets the count.
    const coils = getRecipeCoilTierControl(MULTI_SMELTER, {})!.tiers.map((tier) => tier.key);
    const parallels = coils.map((coil) => run("UV", coil)[2]);
    expect(parallels).toEqual(coils.map((_, index) => 4 << (index + 2 - 1)));
    expect(parallels[0]).toBe(8);
    expect(parallels.at(-1)).toBe(65536);
  });

  it("keeps a saved card without a coil on cupronickel's 8 parallels", () => {
    expect(run("LV")).toEqual([128, 4, 8, 32]);
    expect(run("MV")).toEqual([64, 16, 8, 128]);
    expect(run("MV", "kanthal")).toEqual([128, 4, 16, 64]);
  });
});
