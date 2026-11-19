import { describe, expect, it } from "vitest";
import { getRecipeMachineHandlers } from "@/lib/model/recipe-rules";
import type { MachineHandler, Recipe } from "@/lib/model/types";

function handler(label: string, kind: "single" | "multiblock"): MachineHandler {
  return {
    id: label.toLowerCase().replace(/\s+/g, "-"),
    label,
    machineType: label,
    kind,
    minimumTier: "LV",
  };
}

function recipe(map: string, eut: number, handlers: MachineHandler[], fluidIn = false): Recipe {
  return {
    id: `${map}-${eut}`,
    name: map,
    machineType: map,
    minimumTier: "LV",
    durationTicks: 10,
    eut,
    inputs: [
      { kind: "item", id: "ore", amount: 1 },
      ...(fluidIn ? [{ kind: "fluid" as const, id: "water", amount: 100 }] : []),
    ],
    outputs: [{ kind: "item", id: "dust", amount: 1 }],
    source: { recipeMap: map },
    machineHandlers: handlers,
  };
}

const offered = (r: Recipe) => getRecipeMachineHandlers(r).map((entry) => entry.label);

const MACERATORS = [
  handler("Macerator", "single"),
  handler("High Pressure Steam Macerator", "single"),
  handler("Steam Macerator", "single"),
  handler("Industrial Maceration Stack", "multiblock"),
  handler("Steam Grinder", "multiblock"),
];

describe("steam machines are offered only for recipes up to 32 EU/t", () => {
  it("drops every steam machine from an MV recipe (Ender Amethyst Ore, 120 EU/t)", () => {
    expect(offered(recipe("Macerator", 120, MACERATORS))).toEqual([
      "Macerator",
      "Industrial Maceration Stack",
    ]);
  });

  it("keeps them at exactly 32 EU/t, the LV voltage they run at", () => {
    expect(offered(recipe("Macerator", 32, MACERATORS))).toEqual(
      MACERATORS.map((entry) => entry.label),
    );
    expect(offered(recipe("Macerator", 33, MACERATORS))).toEqual([
      "Macerator",
      "Industrial Maceration Stack",
    ]);
  });

  it("drops the Steam Separator from an MV centrifuge recipe (Moon Comb, 120 EU/t)", () => {
    const handlers = [handler("Centrifuge", "single"), handler("Steam Separator", "multiblock")];
    expect(offered(recipe("Centrifuge", 120, handlers))).toEqual(["Centrifuge"]);
  });

  it("drops GregGuns' Steam Ammo Press from an MV ammo recipe", () => {
    const handlers = [
      handler("Ammo Press", "single"),
      handler("Steam Ammo Press", "single"),
      handler("Ammunition Works", "multiblock"),
    ];
    expect(offered(recipe("Ammo Press", 120, handlers))).toEqual([
      "Ammo Press",
      "Ammunition Works",
    ]);
  });
});
