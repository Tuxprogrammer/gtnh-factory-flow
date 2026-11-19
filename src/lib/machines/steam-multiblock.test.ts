import { describe, expect, it } from "vitest";
import { gtnhFuelProfiles } from "@/lib/model/fuels";
import { PROJECT_SCHEMA_VERSION, type FactoryProject, type Recipe } from "@/lib/model/types";
import { getOverclockedRecipeStats } from "@/lib/solver/overclock";
import { calculateThroughput } from "@/lib/solver/throughput";

// Two recipes as this instance's dataset has them (durations at 1/64 of stock).
// Raw Iron Ore in a macerator: 7 ticks, 2 EU/t.
const RAW_IRON: Recipe = {
  id: "oracle:stable-2.9.0-tuxmine:gregtech:gt-recipe-macerator:53407c0a22bb7e99",
  name: "Macerator: Crushed Iron Ore",
  machineType: "Macerator",
  minimumTier: "ULV",
  durationTicks: 7,
  eut: 2,
  inputs: [{ kind: "item", id: "raw-iron-ore", amount: 1 }],
  outputs: [{ kind: "item", id: "crushed-iron-ore", amount: 2 }],
  source: { recipeMap: "Macerator" },
  machineHandlers: [
    {
      id: "macerator",
      label: "Macerator",
      machineType: "Macerator",
      kind: "single",
      minimumTier: "LV",
    },
    {
      id: "high-pressure-steam-macerator",
      label: "High Pressure Steam Macerator",
      machineType: "High Pressure Steam Macerator",
      kind: "single",
      minimumTier: "LV",
    },
    {
      id: "steam-macerator",
      label: "Steam Macerator",
      machineType: "Steam Macerator",
      kind: "single",
      minimumTier: "LV",
    },
    // The dataset bakes the tooltip's 125% into the handler (6 ticks); the table seeds from the base 7.
    {
      id: "steam-grinder",
      label: "Steam Grinder",
      machineType: "Steam Grinder",
      kind: "multiblock",
      minimumTier: "LV",
      durationTicks: 6,
    },
  ],
};
// Sunnarium to Sunnarium Piece in a forge hammer: 1 tick, 30 EU/t.
const SUNNARIUM: Recipe = {
  id: "oracle:stable-2.9.0-tuxmine:gregtech:gt-recipe-hammer:5362355ae3a4efc6",
  name: "Forge Hammer: Sunnarium Piece",
  machineType: "Forge Hammer",
  minimumTier: "LV",
  durationTicks: 1,
  eut: 30,
  inputs: [{ kind: "item", id: "sunnarium", amount: 1 }],
  outputs: [{ kind: "item", id: "sunnarium-piece", amount: 9 }],
  source: { recipeMap: "Forge Hammer" },
  machineHandlers: [
    {
      id: "forge-hammer",
      label: "Forge Hammer",
      machineType: "Forge Hammer",
      kind: "single",
      minimumTier: "LV",
    },
    {
      id: "steam-presser",
      label: "Steam Presser",
      machineType: "Steam Presser",
      kind: "multiblock",
      minimumTier: "LV",
      durationTicks: 1,
    },
  ],
};

const node = (machineHandlerId: string, pressure?: "normal" | "high") => ({
  overclockTier: "LV" as const,
  machineHandlerId,
  machineConfigTiers: pressure ? { steamPressure: pressure } : {},
});

/** What OverclockCalculator.ofNoOverclock(..).setDurationModifier(1.6 / tierMachine) returns: Java's double maths, rounded up. */
const gameSteamMultiTicks = (ticks: number, tierMachine: 1 | 2) =>
  Math.max(1, Math.ceil(ticks * (1.6 / tierMachine)));

describe("steam multiblock speed (MTESteam* createProcessingLogic)", () => {
  it("rounds the bronze and steel durations up, as ofNoOverclock does", () => {
    expect(getOverclockedRecipeStats(RAW_IRON, node("steam-grinder", "normal")).durationTicks).toBe(
      gameSteamMultiTicks(7, 1),
    );
    expect(gameSteamMultiTicks(7, 1)).toBe(12);
    expect(getOverclockedRecipeStats(RAW_IRON, node("steam-grinder", "high")).durationTicks).toBe(
      gameSteamMultiTicks(7, 2),
    );
    expect(gameSteamMultiTicks(7, 2)).toBe(6);
  });

  it("banks no sub-tick speed: a 1-tick recipe takes 2 ticks on bronze and 1 on steel", () => {
    expect(
      getOverclockedRecipeStats(SUNNARIUM, node("steam-presser", "normal")).durationTicks,
    ).toBe(2);
    expect(getOverclockedRecipeStats(SUNNARIUM, node("steam-presser", "high")).durationTicks).toBe(
      1,
    );
  });

  it("runs 8 parallels at either pressure, so steel is twice bronze only where the rounding allows", () => {
    const perSecond = (pressure: "normal" | "high") => {
      const project: FactoryProject = {
        schemaVersion: PROJECT_SCHEMA_VERSION,
        id: "steam",
        name: "Steam",
        recipes: [SUNNARIUM],
        nodes: [
          {
            id: "presser",
            recipeId: SUNNARIUM.id,
            machineCount: 1,
            parallel: 1,
            enabled: true,
            position: { x: 0, y: 0 },
            ...node("steam-presser", pressure),
          },
        ],
        edges: [],
        fuelProfiles: gtnhFuelProfiles,
        selectedFuelProfileId: "biodiesel",
      };
      return calculateThroughput(project, { generatedAt: "fixed" }).nodes.presser
        .operationRatePerSecond;
    };
    expect(perSecond("normal")).toBeCloseTo((8 * 20) / 2);
    expect(perSecond("high")).toBeCloseTo((8 * 20) / 1);
  });

  it("leaves the steam singleblocks at twice the recipe's time (bronze) and the recipe's time (steel)", () => {
    // SteamOverclockDescriber: bronze (1 EU, x2 duration), steel (2 EU, x1 duration); whole multipliers, no rounding.
    expect(getOverclockedRecipeStats(RAW_IRON, node("steam-macerator")).durationTicks).toBe(14);
    expect(
      getOverclockedRecipeStats(RAW_IRON, node("high-pressure-steam-macerator")).durationTicks,
    ).toBe(7);
  });
});
