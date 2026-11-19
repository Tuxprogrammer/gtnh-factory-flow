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

describe("steam machines that have no input hatch are not offered for recipes with a fluid input", () => {
  it("drops the Steam Separator from a centrifuge recipe with a fluid input (Fertilizer, 16 EU/t)", () => {
    const handlers = [handler("Centrifuge", "single"), handler("Steam Separator", "multiblock")];
    expect(offered(recipe("Centrifuge", 16, handlers, true))).toEqual(["Centrifuge"]);
    expect(offered(recipe("Centrifuge", 16, handlers))).toEqual(["Centrifuge", "Steam Separator"]);
  });

  it("drops the steam singles but keeps the Steam Presser, which takes an input hatch (Thiosulfine Flower, 24 EU/t)", () => {
    const handlers = [
      handler("Forge Hammer", "single"),
      handler("High Pressure Steam Forge Hammer", "single"),
      handler("Steam Forge Hammer", "single"),
      handler("Industrial Sledgehammer", "multiblock"),
      handler("Steam Presser", "multiblock"),
    ];
    expect(offered(recipe("Forge Hammer", 24, handlers, true))).toEqual([
      "Forge Hammer",
      "Industrial Sledgehammer",
      "Steam Presser",
    ]);
  });

  it("keeps the Steam Squasher, Steam Purifier and Steam Blender on fluid recipes", () => {
    for (const [map, machine] of [
      ["Compressor", "Steam Squasher"],
      ["Ore Washer", "Steam Purifier"],
      ["Multiblock Mixer", "Steam Blender"],
    ]) {
      expect(
        offered(recipe(map, 15, [handler(map, "single"), handler(machine, "multiblock")], true)),
      ).toEqual([map, machine]);
    }
  });

  it("drops the Steam Grinder, Steam Fuser and Steam Hearth from a fluid recipe", () => {
    for (const machine of ["Steam Grinder", "Steam Fuser", "Steam Hearth"]) {
      expect(
        offered(recipe("Map", 8, [handler("Map", "single"), handler(machine, "multiblock")], true)),
      ).toEqual(["Map"]);
    }
  });
});
