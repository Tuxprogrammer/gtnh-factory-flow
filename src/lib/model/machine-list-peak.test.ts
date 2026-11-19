import { describe, expect, it } from "vitest";
import { buildMachineList } from "./machine-list";
import {
  PROJECT_SCHEMA_VERSION,
  type FactoryNode,
  type FactoryProject,
  type Recipe,
} from "./types";
import { calculateThroughput } from "@/lib/solver/throughput";

const bender: Recipe = {
  id: "light",
  name: "Copper Plate",
  machineType: "Bender",
  minimumTier: "LV",
  durationTicks: 20,
  eut: 8,
  inputs: [{ kind: "item", id: "copper", amount: 1 }],
  outputs: [{ kind: "item", id: "plate", displayName: "Copper Plate", amount: 1 }],
};
const heavy: Recipe = { ...bender, id: "heavy", eut: 24 };

/** One solved card at `solved` machines' worth, with the count rule given. */
function line(solved: number, pin: Partial<FactoryNode> = {}, recipe: Recipe = bender) {
  const p: FactoryProject = {
    schemaVersion: PROJECT_SCHEMA_VERSION,
    id: "peak",
    name: "Peak",
    fuelProfiles: [],
    recipes: [recipe, heavy],
    edges: [],
    solveMode: true,
    nodes: [
      {
        id: "n0",
        recipeId: recipe.id,
        machineCount: 1,
        parallel: 1,
        enabled: true,
        overclockTier: "LV",
        position: { x: 0, y: 0 },
        ...pin,
      },
    ],
  };
  const result = calculateThroughput(p);
  result.nodes.n0!.theoreticalMachinesRequired = solved;
  result.nodes.n0!.utilization = 1;
  return { p, result, entry: buildMachineList(p, result)[0]! };
}

describe("peak and average draw on a solved board", () => {
  it("peak bills every machine an Up to card has; average bills what runs", () => {
    // Up to 3 benders, one machine's worth running: the card's power row reads 24 at Peak and 8 at Avg.
    expect(line(1, { solvePin: 3 }).entry).toMatchObject({ count: 1, euT: 24, avgEuT: 8 });
  });

  it("peak bills an Exactly card's machines at full draw while they run part time", () => {
    expect(line(0.25, { solvePin: 1, solvePinExact: true }).entry).toMatchObject({
      count: 0.25,
      euT: 8,
      avgEuT: 2,
    });
  });

  it("a card with no set count bills whole machines at peak and what the solve runs on average", () => {
    expect(line(0.25).entry).toMatchObject({ count: 0.25, euT: 8, avgEuT: 2 });
    expect(line(1.5).entry).toMatchObject({ count: 1.5, euT: 16, avgEuT: 12 });
    expect(line(2).entry).toMatchObject({ count: 2, euT: 16, avgEuT: 16 });
  });

  it("an Up to card with no count is a card with none set", () => {
    expect(line(0.25, { solvePin: 0, solvePinExact: false }).entry).toMatchObject({
      euT: 8,
      avgEuT: 2,
    });
  });

  it("a shared card keeps billing each recipe at its solved machines, as its own power row does", () => {
    const { p, result } = line(0.25, { solvePin: 2, extraRecipes: [{ recipeId: "heavy" }] });
    result.nodes["n0#r1"]!.theoreticalMachinesRequired = 0.5;
    result.nodes["n0#r1"]!.utilization = 1;
    expect(buildMachineList(p, result)[0]).toMatchObject({ count: 0.75, euT: 18, avgEuT: 14 });
  });
});
