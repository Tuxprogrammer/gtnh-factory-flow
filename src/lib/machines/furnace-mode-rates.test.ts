import { afterEach, describe, expect, it } from "vitest";
import { setActiveGameRates } from "@/lib/model/game-rates";
import { applyMachineHandlerToRecipe, gtFurnaceDurationTicks } from "@/lib/model/recipe-rules";
import type { Recipe } from "@/lib/model/types";
import { hasPowerReport } from "@/lib/solver/power-report";

// A smelting recipe as this instance's dataset has it (vanilla 200 ticks, 0 EU/t), with the two
// multiblocks the pipeline lists on the Furnace map besides the Multi Smelter. Neither handler
// carries a duration of its own.
const SMELT = {
  id: "oracle:stable-2.9.0-tuxmine:smelting:cf3ba0b988fa3970",
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
      id: "helioflare-power-forge",
      label: "Helioflare Power Forge",
      kind: "multiblock",
      machineType: "Helioflare Power Forge",
      minimumTier: "NONE",
    },
    {
      id: "industrial-arc-furnace",
      label: "Industrial Arc Furnace",
      kind: "multiblock",
      machineType: "Industrial Arc Furnace",
      minimumTier: "NONE",
    },
  ],
} as unknown as Recipe;

const baseTicks = (handler: string) =>
  applyMachineHandlerToRecipe(SMELT, { machineHandlerId: handler }).durationTicks;

afterEach(() => setActiveGameRates(undefined));

describe("furnace modes of multiblocks and the pack's rate mod (GTNHRates GTFurnaces)", () => {
  it("runs the Industrial Arc Furnace and Helioflare smelting on FurnaceBackend's 128-tick recipe", () => {
    // MTEIndustrialArcFurnace and MTESmeltingModule switch to furnaceRecipes, whose
    // FurnaceBackend.overwriteFindRecipe builds a 128-tick recipe, not the vanilla 200.
    expect(baseTicks("industrial-arc-furnace")).toBe(128);
    expect(baseTicks("helioflare-power-forge")).toBe(128);
  });

  it("divides that recipe by the pack's rate, as the mixin on overwriteFindRecipe does", () => {
    setActiveGameRates({ recipeDurationDivisor: 64 });
    expect(baseTicks("industrial-arc-furnace")).toBe(2);
    expect(baseTicks("helioflare-power-forge")).toBe(2);
    const arc = SMELT.machineHandlers!.find((entry) => entry.id === "industrial-arc-furnace")!;
    setActiveGameRates({ recipeDurationDivisor: 100 });
    expect(gtFurnaceDurationTicks(SMELT, arc)).toBe(2);
    setActiveGameRates({ recipeDurationDivisor: 1000 });
    expect(gtFurnaceDurationTicks(SMELT, arc)).toBe(1);
  });

  it("bills the Industrial Arc Furnace's furnace mode FurnaceBackend's 4 EU/t, not the map's 0", () => {
    const arc = applyMachineHandlerToRecipe(SMELT, { machineHandlerId: "industrial-arc-furnace" });
    expect(arc.eut).toBe(4);
    expect(hasPowerReport(SMELT, { machineHandlerId: "industrial-arc-furnace" })).toBe(true);
  });

  it("bills the Helioflare's smelting module FurnaceBackend's 4 EU/t, not the map's 0", () => {
    const forge = applyMachineHandlerToRecipe(SMELT, { machineHandlerId: "helioflare-power-forge" });
    expect(forge.eut).toBe(4);
    expect(hasPowerReport(SMELT, { machineHandlerId: "helioflare-power-forge" })).toBe(true);
    // The vanilla furnace stays unpowered.
    expect(applyMachineHandlerToRecipe(SMELT, { machineHandlerId: "furnace" }).eut).toBe(0);
    expect(hasPowerReport(SMELT, { machineHandlerId: "furnace" })).toBe(false);
  });

  it("leaves the arc furnace's own arc and blast recipes to the dataset", () => {
    setActiveGameRates({ recipeDurationDivisor: 64 });
    const arc = SMELT.machineHandlers!.find((entry) => entry.id === "industrial-arc-furnace")!;
    expect(
      gtFurnaceDurationTicks(
        { machineType: "Arc Furnace", source: { recipeMap: "Arc Furnace" } },
        arc,
      ),
    ).toBeUndefined();
    expect(baseTicks("furnace")).toBe(200);
  });
});
