import { afterEach, describe, expect, it } from "vitest";
import { setActiveGameRates } from "@/lib/model/game-rates";
import { gtFurnaceDurationTicks } from "@/lib/model/recipe-rules";
import type { Recipe } from "@/lib/model/types";
import { getOverclockedRecipeStats } from "@/lib/solver/overclock";

const TIERS = ["LV", "MV", "HV", "EV", "IV", "LuV", "ZPM", "UV", "UHV", "UEV", "UIV", "UMV"];

// Raw Iron Ore to Iron Ingot as this instance's dataset has it: the vanilla 200-tick smelt,
// with the furnace handlers the pipeline writes. The Electric Furnace carries GT's stock
// FurnaceBackend numbers (128 t, 4 EU/t), a literal in normalize-oracle-export.mjs: not
// read from the game, so the rate mod's runtime hook is not in it.
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
      id: "electric-furnace",
      label: "Electric Furnace",
      machineType: "Electric Furnace",
      kind: "single",
      minimumTier: "LV",
      durationTicks: 128,
      eut: 4,
      availableTiers: TIERS,
    },
    {
      id: "multi-smelter",
      label: "Multi Smelter",
      machineType: "Multi Smelter",
      kind: "multiblock",
      minimumTier: "NONE",
    },
    {
      id: "high-pressure-steam-furnace",
      label: "High Pressure Steam Furnace",
      machineType: "High Pressure Steam Furnace",
      kind: "single",
      minimumTier: "NONE",
    },
    {
      id: "steam-furnace",
      label: "Steam Furnace",
      machineType: "Steam Furnace",
      kind: "single",
      minimumTier: "NONE",
    },
    {
      id: "steam-hearth",
      label: "Steam Hearth",
      machineType: "Steam Hearth",
      kind: "multiblock",
      minimumTier: "NONE",
      durationTicks: 160,
    },
  ],
} as unknown as Recipe;

const ticks = (handler: string, tier = "LV", settings: Record<string, string> = {}) =>
  getOverclockedRecipeStats(SMELT, {
    overclockTier: tier,
    machineHandlerId: handler,
    machineConfigTiers: settings,
  } as never).durationTicks;

afterEach(() => setActiveGameRates(undefined));

describe("GT furnace timing and the pack's rate mod (GTNHRates GTFurnaces)", () => {
  it("runs GT's stock furnace times when the dataset has no rates record", () => {
    expect(ticks("electric-furnace")).toBe(128);
    expect(ticks("electric-furnace", "MV")).toBe(64);
    expect(ticks("steam-furnace")).toBe(256);
    expect(ticks("high-pressure-steam-furnace")).toBe(128);
    // MTESteamFurnaceMulti: 128 x 1.6 = 204.8 and 128 x 0.8 = 102.4, rounded up.
    expect(ticks("steam-hearth", "LV", { steamPressure: "normal" })).toBe(205);
    expect(ticks("steam-hearth", "LV", { steamPressure: "high" })).toBe(103);
  });

  it("divides them as max(1, ceil(ticks / divisor)), the steam furnaces after their x2", () => {
    setActiveGameRates({ recipeDurationDivisor: 64 });
    expect(ticks("electric-furnace")).toBe(2);
    expect(ticks("electric-furnace", "MV")).toBe(1);
    expect(ticks("steam-furnace")).toBe(4);
    expect(ticks("high-pressure-steam-furnace")).toBe(2);
    expect(ticks("steam-hearth", "LV", { steamPressure: "normal" })).toBe(4);
    expect(ticks("steam-hearth", "LV", { steamPressure: "high" })).toBe(2);
    // The rounding: 256 / 100 = 2.56 -> 3, never below one tick.
    setActiveGameRates({ recipeDurationDivisor: 100 });
    expect(ticks("steam-furnace")).toBe(3);
    setActiveGameRates({ recipeDurationDivisor: 1000 });
    expect(ticks("electric-furnace")).toBe(1);
  });

  it("leaves the Multi Smelter at its own fixed 128 ticks: MTEMultiFurnace never calls FurnaceBackend", () => {
    const handler = SMELT.machineHandlers!.find((entry) => entry.id === "multi-smelter")!;
    expect(gtFurnaceDurationTicks(SMELT, handler)).toBe(128);
    expect(ticks("multi-smelter")).toBe(128);
    setActiveGameRates({ recipeDurationDivisor: 64 });
    expect(gtFurnaceDurationTicks(SMELT, handler)).toBe(128);
    expect(ticks("multi-smelter")).toBe(128);
  });

  it("leaves the vanilla furnace and non-smelting recipes alone", () => {
    setActiveGameRates({ recipeDurationDivisor: 64 });
    expect(ticks("furnace")).toBe(200);
    const furnace = SMELT.machineHandlers!.find((entry) => entry.id === "electric-furnace")!;
    expect(
      gtFurnaceDurationTicks(
        { machineType: "Macerator", source: { recipeMap: "Macerator" } },
        furnace,
      ),
    ).toBeUndefined();
  });
});
