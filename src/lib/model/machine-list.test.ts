import { describe, expect, it } from "vitest";
import { buildMachineList, machinesFromUnits, unitsFromMachines } from "./machine-list";
import { PROJECT_SCHEMA_VERSION, type FactoryProject, type Recipe } from "./types";
import { calculateThroughput } from "@/lib/solver/throughput";
import { closeBoundaries } from "@/lib/solver/close-boundaries";

const light: Recipe = {
  id: "light",
  name: "Copper Plate",
  machineType: "Bender",
  minimumTier: "LV",
  durationTicks: 20,
  eut: 8,
  inputs: [{ kind: "item", id: "copper", amount: 1 }],
  outputs: [{ kind: "item", id: "plate", displayName: "Copper Plate", amount: 1 }],
};
const heavy: Recipe = {
  ...light,
  id: "heavy",
  name: "Dense Copper Plate",
  eut: 24,
  outputs: [{ kind: "item", id: "dense", displayName: "Dense Copper Plate", amount: 1 }],
};
function project(): FactoryProject {
  return {
    schemaVersion: PROJECT_SCHEMA_VERSION,
    id: "machine-list",
    name: "Machine list",
    fuelProfiles: [],
    recipes: [light, heavy],
    edges: [],
    nodes: ["light", "light", "heavy"].map((recipeId, index) => ({
      id: `n${index}`,
      recipeId,
      machineCount: [2, 3, 4][index]!,
      parallel: 1,
      enabled: true,
      overclockTier: "LV",
      position: { x: index * 400, y: 0 },
    })),
  };
}

describe("the machine list follows each board card", () => {
  it("keeps identical machines/recipes separate and scales each card's own draw", () => {
    const p = closeBoundaries(project());
    const lines = buildMachineList(p, calculateThroughput(p));
    expect(lines).toHaveLength(3);
    expect(lines.find((line) => line.nodeId === "n0")).toMatchObject({
      count: 2,
      euT: 16,
      avgEuT: 16,
    });
    expect(lines.find((line) => line.nodeId === "n1")).toMatchObject({
      count: 3,
      euT: 24,
      avgEuT: 24,
    });
    expect(lines.find((line) => line.nodeId === "n2")).toMatchObject({
      count: 4,
      euT: 96,
      avgEuT: 96,
    });
    expect(lines.reduce((sum, line) => sum + line.count, 0)).toBe(9);
  });

  it.each([false, true])(
    "uses fractional solved counts below and above the stored count (pool=%s)",
    (poolMode) => {
      const p = closeBoundaries({ ...project(), solveMode: true, poolMode });
      [0.125, 6.25, 101.375].forEach((count, index) => {
        p.nodes[index]!.solvePin = count;
      });
      const result = calculateThroughput(p);
      const lines = buildMachineList(p, result);
      for (const [index, count] of [0.125, 6.25, 101.375].entries()) {
        const line = lines.find((entry) => entry.nodeId === `n${index}`)!;
        expect(line.count).toBeCloseTo(count);
        expect(line.euT).toBeCloseTo(count * (index === 2 ? 24 : 8));
        expect(line.avgEuT).toBeCloseTo(line.euT!);
        expect(line.avgEuT).toBeCloseTo(result.nodes[line.nodeId]!.euT);
      }
      expect(lines.reduce((sum, line) => sum + line.euT!, 0)).toBeCloseTo(result.totalEuT);
    },
  );

  it("retains zero-demand cards at zero and does not bill retained nameplate results", () => {
    const p = closeBoundaries({ ...project(), solveMode: true });
    const lines = buildMachineList(p, calculateThroughput(p));
    expect(lines).toHaveLength(3);
    expect(lines.every((line) => line.count === 0 && line.euT === 0 && line.avgEuT === 0)).toBe(
      true,
    );
  });

  it("does not count parallel operations as more machines", () => {
    const p = closeBoundaries(project());
    p.nodes[0]!.parallel = 4;
    const line = buildMachineList(p, calculateThroughput(p)).find(
      (entry) => entry.nodeId === "n0",
    )!;
    expect(line.count).toBe(2);
    expect(line.euT).toBe(64);
  });

  it("keeps shared recipes on one card and weights their different draws correctly", () => {
    const p = project();
    p.nodes = [{ ...p.nodes[0]!, extraRecipes: [{ recipeId: "heavy" }] }];
    const result = calculateThroughput(p);
    // A shared card spends 25% on 8 EU/t and 75% on 24 EU/t.
    result.nodes.n0!.utilization = 0.25;
    result.nodes["n0#r1"]!.utilization = 0.75;
    expect(buildMachineList(p, result)).toEqual([
      expect.objectContaining({ count: 2, euT: 48, avgEuT: 40 }),
    ]);
    p.solveMode = true;
    result.nodes.n0!.theoreticalMachinesRequired = 0.25;
    result.nodes["n0#r1"]!.theoreticalMachinesRequired = 0.5;
    result.nodes.n0!.utilization = result.nodes["n0#r1"]!.utilization = 1;
    // A shared pool bills whole machines at its hungriest recipe at peak: 1 x 24, not 0.75 x 24.
    expect(buildMachineList(p, result)).toEqual([
      expect.objectContaining({ count: 0.75, euT: 24, avgEuT: 14 }),
    ]);
  });

  it("weights generation and steam by solved counts too", () => {
    const p = project();
    p.solveMode = true;
    p.recipes = [
      { ...light, id: "power", eut: 0, power: { sourceId: "test", euPerTick: 32, stats: [] } },
      { ...light, id: "steam", machineType: "Steam Grinder" },
    ];
    p.nodes = [
      { ...p.nodes[0]!, recipeId: "power" },
      { ...p.nodes[1]!, recipeId: "steam" },
    ];
    const result = calculateThroughput(p);
    result.nodes.n0!.theoreticalMachinesRequired = 2.5;
    result.nodes.n1!.theoreticalMachinesRequired = 0.125;
    const lines = buildMachineList(p, result);
    expect(lines.find((entry) => entry.nodeId === "n0")).toMatchObject({
      count: 2.5,
      madeEuT: 80,
      avgMadeEuT: 80,
    });
    const steam = lines.find((entry) => entry.nodeId === "n1")!;
    expect(steam.count).toBe(0.125);
    expect(steam.steamLs).toBeGreaterThan(0);
    // Peak bills the whole machine that 0.125 of a machine needs; the average stays the solved fraction.
    expect(steam.avgSteamLs).toBeCloseTo(steam.steamLs! * 0.125, 9);
    expect(steam.euT).toBeUndefined();
  });
});

describe("power card tiers in the machine list", () => {
  it("never reads a heat exchanger's pipe tier as a voltage", async () => {
    // Player crash report, 2026-09-25: an LHE stores `tier: "1"` (its pipe
    // tier), and the MACHINES panel looked "1" up in the voltage colours.
    const { buildPowerRecipe } = await import("@/lib/power/power-recipe");
    const lheSettings = { fluid: "Hot Coolant", intake: "1000", tier: "1" };
    const turbineSettings = { tier: "EV" };
    const lhe = buildPowerRecipe("large-heat-exchanger", lheSettings, "r-lhe")!;
    const turbine = buildPowerRecipe("steam-turbine", turbineSettings, "r-turbine")!;
    const p: FactoryProject = {
      schemaVersion: PROJECT_SCHEMA_VERSION,
      id: "power-tiers",
      name: "Power tiers",
      fuelProfiles: [],
      recipes: [lhe, turbine],
      edges: [],
      nodes: [
        {
          id: "lhe",
          recipeId: "r-lhe",
          machineCount: 100,
          parallel: 1,
          enabled: true,
          overclockTier: "EV",
          position: { x: 0, y: 0 },
          machineConfigTiers: lheSettings,
        },
        {
          id: "turbine",
          recipeId: "r-turbine",
          machineCount: 1,
          parallel: 1,
          enabled: true,
          overclockTier: "EV",
          position: { x: 400, y: 0 },
          machineConfigTiers: turbineSettings,
        },
      ],
    };
    const lines = buildMachineList(p, calculateThroughput(p));
    expect(lines.find((line) => line.nodeId === "lhe")?.tier).toBeUndefined();
    expect(lines.find((line) => line.nodeId === "turbine")?.tier).toBe("EV");
  });
});

describe("crop cards in the machine list", () => {
  const crop: Recipe = {
    id: "oil-berry",
    name: "Oil Berry",
    machineType: "Crop Farm",
    minimumTier: "NONE",
    durationTicks: 256,
    eut: 0,
    inputs: [],
    outputs: [{ kind: "item", id: "berry", displayName: "Oil Berry", amount: 1 }],
    metadata: {
      cropsNh: {
        tier: 4,
        growthPoints: 1200,
        dropChance: 0.8,
        growthCycleTicks: 256,
        growthMultiplier: 1,
        drops: [{ id: "berry", stackSize: 2, weight: 10000 }],
      },
    },
  } as Recipe;
  const cropProject = (
    seeds: number,
    handler: string,
    tiers: Record<string, string>,
  ): FactoryProject => ({
    schemaVersion: PROJECT_SCHEMA_VERSION,
    id: "crops",
    name: "Crops",
    fuelProfiles: [],
    recipes: [crop],
    edges: [],
    nodes: [
      {
        id: "crop",
        recipeId: "oil-berry",
        machineCount: seeds,
        parallel: 1,
        enabled: true,
        overclockTier: "NONE",
        position: { x: 0, y: 0 },
        machineHandlerId: handler,
        machineConfigTiers: tiers,
      },
    ],
  });
  const listed = (p: FactoryProject) => buildMachineList(p, calculateThroughput(p))[0]!;

  it("counts the Crop Managers the seeds fill, and says how many seeds fill one", () => {
    // An LV Crop Manager works 11 x 11 sticks on three layers, minus its own block: 362.
    const fifty = listed(cropProject(50, "crop-manager", {}));
    expect(fifty).toMatchObject({ count: 1, seedsPerMachine: 362 });
    expect(listed(cropProject(362, "crop-manager", {})).count).toBe(1);
    expect(listed(cropProject(363, "crop-manager", {})).count).toBe(2);
  });

  it("counts the Industrial Farms the seeds fill", () => {
    // A tier 5 (IV) seed bed holds 27 x 27 seeds.
    const farm = listed(cropProject(50, "crop-industrial-farm", { cropSeedBedTier: "5" }));
    expect(farm).toMatchObject({ count: 1, seedsPerMachine: 729 });
  });

  it("converts between a card's own units and machines", () => {
    expect(machinesFromUnits({ seedsPerMachine: 362 }, 50)).toBe(1);
    expect(machinesFromUnits({ seedsPerMachine: 362 }, 363)).toBe(2);
    expect(machinesFromUnits({ seedsPerMachine: 362 }, 0)).toBe(0);
    expect(machinesFromUnits({}, 4.5)).toBe(4.5);
    expect(unitsFromMachines({ seedsPerMachine: 362 }, 2)).toBe(724);
    expect(unitsFromMachines({}, 2)).toBe(2);
  });
});
