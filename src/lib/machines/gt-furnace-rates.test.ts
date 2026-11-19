import { afterEach, describe, expect, it } from "vitest";
import { setActiveGameRates } from "@/lib/model/game-rates";
import { gtFurnaceDurationTicks } from "@/lib/model/recipe-rules";
import type { Recipe } from "@/lib/model/types";
import { getOverclockedRecipeStats } from "@/lib/solver/overclock";
import { getNodePowerReport, hasPowerReport } from "@/lib/solver/power-report";

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

  it("bills the Multi Smelter 4 EU/t per parallel: MTEMultiFurnace's RECIPE_EUT, not the map's 0", () => {
    // checkProcessing: parallels = min(coil parallels, supply / 4), overclocks bought with what
    // the parallels leave of one hatch's voltage, 128 ticks halved per step.
    const coils = {
      id: "heatingCoil",
      label: "Heating Coil",
      defaultKey: "cupronickel",
      minimumKey: "cupronickel",
      tiers: [
        { key: "cupronickel", label: "Cupronickel" },
        { key: "kanthal", label: "Kanthal" },
      ],
    };
    const recipe = {
      ...SMELT,
      machineHandlers: SMELT.machineHandlers!.map((handler) =>
        handler.id === "multi-smelter" ? { ...handler, machineConfigControls: [coils] } : handler,
      ),
    } as Recipe;
    const run = (tier: string, coil: string) => {
      const node = {
        id: "n",
        recipeId: recipe.id,
        enabled: true,
        machineCount: 1,
        parallel: 1,
        position: { x: 0, y: 0 },
        overclockTier: tier,
        machineHandlerId: "multi-smelter",
        coilTier: coil,
      } as never;
      const stats = getOverclockedRecipeStats(recipe, node);
      const report = getNodePowerReport(recipe, node);
      return [stats.durationTicks, stats.eut, report.parallels, report.drawEuT];
    };
    expect(run("LV", "cupronickel")).toEqual([128, 4, 8, 32]);
    expect(run("MV", "cupronickel")).toEqual([64, 16, 8, 128]);
    // 16 parallels draw 64 of an MV hatch's 128: no overclock left.
    expect(run("MV", "kanthal")).toEqual([128, 4, 16, 64]);
    // An LV hatch powers 32 / 4 = 8 of the 16.
    expect(run("LV", "kanthal")).toEqual([128, 4, 8, 32]);
  });

  it("gives the electric furnaces a power section although the smelt itself says 0 EU", () => {
    const has = (machineHandlerId: string) => hasPowerReport(SMELT, { machineHandlerId });
    expect(has("multi-smelter")).toBe(true);
    expect(has("electric-furnace")).toBe(true);
    expect(has("furnace")).toBe(false);
    expect(has("steam-furnace")).toBe(false);
    expect(has("steam-hearth")).toBe(false);
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
