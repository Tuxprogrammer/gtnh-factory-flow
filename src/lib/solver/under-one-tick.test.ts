import { describe, expect, it } from "vitest";
import { readConformanceJson } from "@/test-support/conformance";
import type { FactoryNode, MachineConfigControl, MachineTier, Recipe } from "@/lib/model/types";
import { getOverclockedRecipeStats } from "./overclock";
import { getNodePowerReport } from "./power-report";
import { multiplierUnderOneTick } from "./under-one-tick";

// conformance/machines/subtick-parallels.json: the pack's own OverclockCalculator and ParallelHelper's power lines on
// very short recipes, run on the JVM. core-machines' UnderOneTick is held to the same file.
interface SubTickCase {
  name: string;
  family: string;
  in: {
    multiblock: boolean;
    recipeEut: number;
    recipeTicks: number;
    tier: MachineTier;
    amps: number;
    maxParallel: number;
    durationDecreasePerOC: number;
  };
  tail: { duration: number; overclocks: number; heatOverclocksAvailable: number };
  game: {
    status: string;
    multiplier?: number;
    parallels?: number;
    durationTicks?: number;
    eut?: number;
  };
}
const golden = readConformanceJson("machines", "subtick-parallels.json") as {
  cases: SubTickCase[];
};

function hatch(tier: MachineTier, extra: Partial<FactoryNode> = {}): FactoryNode {
  return {
    overclockTier: tier,
    hatchVoltageTier: tier,
    hatchAmps: 1,
    ...extra,
  } as unknown as FactoryNode;
}

/** Recipes a tick and the parallels, as the card works them. */
function run(recipe: Recipe, node: FactoryNode) {
  const stats = getOverclockedRecipeStats(recipe, node);
  const report = getNodePowerReport(recipe, node);
  return {
    parallels: report.parallels,
    perTick: report.parallels / stats.durationTicks,
    eut: stats.eut,
  };
}

function compressor(machineType: string, durationTicks: number): Recipe {
  return {
    id: "compressor:test",
    name: "test",
    machineType,
    minimumTier: "ULV",
    durationTicks,
    eut: 2,
    inputs: [],
    outputs: [{ kind: "item", id: "test:out", amount: 1 }],
  } as unknown as Recipe;
}

function blastFurnace(durationTicks: number): Recipe {
  const coil = (key: string, heat: number) => ({
    key,
    label: key,
    heat,
    resource: { kind: "item", id: "c", amount: 1 },
  });
  const control = {
    id: "heatingCoil",
    label: "Heating Coil",
    minimumKey: "cupronickel",
    tiers: [coil("cupronickel", 1801), coil("nichrome", 3601), coil("trinium", 9001)],
  } as unknown as MachineConfigControl;
  return {
    id: "ebf:test",
    name: "test",
    machineType: "Blast Furnace",
    minimumTier: "MV",
    durationTicks,
    eut: 120,
    nei: { additionalInfo: ["Special value: 1800"] },
    machineConfigControls: [control],
    inputs: [],
    outputs: [{ kind: "item", id: "test:out", amount: 1 }],
  } as unknown as Recipe;
}

describe("the game's multiplier under one tick", () => {
  it("is the game's on every multiblock case", () => {
    const cases = golden.cases.filter((c) => c.in.multiblock && c.game.status === "ok");
    expect(cases.length).toBeGreaterThanOrEqual(50);
    for (const c of cases) {
      const got = multiplierUnderOneTick(
        c.tail.duration,
        c.tail.overclocks,
        c.tail.heatOverclocksAvailable,
        c.in.durationDecreasePerOC,
      );
      expect(got, c.name).toBe(c.game.multiplier);
    }
  });

  it("runs a 1-tick Large Electric Compressor recipe at twice the plain ceil(1 / duration)", () => {
    // Half a tick before overclocks: the game multiplies 2 parallels by 4 at LV, so 8 a tick.
    expect(run(compressor("Large Electric Compressor", 1), hatch("LV"))).toMatchObject({
      parallels: 2,
      perTick: 8,
    });
  });

  it("matches the game's compressor cards on one hatch, case by case", () => {
    const machines: Record<string, string> = {
      "Large Electric Compressor": "Large Electric Compressor",
      "Black Hole Compressor": "Pseudostable Black Hole Containment Field",
    };
    const cases = golden.cases.filter(
      (c) => machines[c.family] && c.in.amps === 1 && c.in.recipeEut === 2,
    );
    expect(cases).toHaveLength(23);
    for (const c of cases) {
      const got = run(compressor(machines[c.family]!, c.in.recipeTicks), hatch(c.in.tier));
      expect(got.parallels, c.name).toBe(c.in.maxParallel);
      expect(got.perTick, c.name).toBeCloseTo(c.game.parallels! / c.game.durationTicks!, 6);
    }
  });

  it("counts heat overclocks the game's way on a blast furnace", () => {
    // 2 ticks, one heat overclock (Nichrome at EV: 2001 K over the recipe): a quarter of a tick, so the game counts
    // one regular overclock as needed below zero and runs 8 a tick, not 4.
    const nichrome = run(
      blastFurnace(2),
      hatch("EV", { coilTier: "nichrome" } as Partial<FactoryNode>),
    );
    const game = golden.cases.find(
      (c) => c.name === "Electric Blast Furnace 2t EV, 1 heat OC",
    )!.game;
    expect(nichrome.perTick).toBeCloseTo(game.parallels! / game.durationTicks!, 9);
    expect(Math.ceil(nichrome.eut)).toBe(game.eut);
    // 3 ticks, three of four heat overclocks paid for at IV (Trinium: 7501 K over): the supply caps it at 102 a tick.
    const trinium = run(
      blastFurnace(3),
      hatch("IV", { coilTier: "trinium" } as Partial<FactoryNode>),
    );
    const capped = golden.cases.find(
      (c) => c.name === "Electric Blast Furnace 3t IV, 4 heat OCs",
    )!.game;
    expect(trinium.perTick).toBeCloseTo(capped.parallels! / capped.durationTicks!, 9);
  });
});
