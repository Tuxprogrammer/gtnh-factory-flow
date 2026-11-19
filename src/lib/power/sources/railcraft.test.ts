import { describe, expect, it } from "vitest";
import { resolvePowerResource } from "../planner-data";
import { buildPowerRecipe } from "../power-recipe";
import { resourceMatchesInput } from "@/lib/model/resources";
import { getPowerSource } from "../registry";
import { buildPowerSettingsReader } from "../types";
import { boilerWarmUpTicks, waterTankFill } from "./railcraft";

/**
 * Railcraft's Water Tank and boiler against Railcraft's source (GTNH fork,
 * tag 9.17.26). The mod's RailcraftTest pins the same values.
 */
function compute(sourceId: string, settings: Record<string, string> = {}) {
  const source = getPowerSource(sourceId);
  if (!source) {
    throw new Error(`No power source ${sourceId}`);
  }
  return source.compute(buildPowerSettingsReader(source, settings));
}

function flow(model: ReturnType<typeof compute>, name: string): number | undefined {
  return [...model.inputs, ...model.outputs].find((line) => line.name === name)?.perSecond;
}

describe("Railcraft Water Tank (TileTankWater.updateEntity)", () => {
  it("fills 8 L every 8 ticks in a forest under open sky", () => {
    const model = compute("railcraft-water-tank");
    expect(model.inputs).toEqual([]);
    expect(model.outputs).toEqual([{ name: "Water", perSecond: 20, unit: "L" }]);
    expect(model.euPerTick).toBe(0);
  });

  it("floors each fill in float maths, as the game does", () => {
    // 10 x 0.9 x 0.5 = 4.5 -> 4 L: 10 L/s, not the wiki's 11.25 (or 33.75 with rain).
    expect(waterTankFill(0.9, "roof")).toBe(4);
    expect(flow(compute("railcraft-water-tank", { humidity: "0.9", sky: "roof" }), "Water")).toBe(
      10,
    );
    expect(flow(compute("railcraft-water-tank", { humidity: "1", sky: "rain" }), "Water")).toBe(75);
    expect(flow(compute("railcraft-water-tank", { humidity: "1.2", sky: "rain" }), "Water")).toBe(
      90,
    );
    expect(flow(compute("railcraft-water-tank", { humidity: "0.4", sky: "snow" }), "Water")).toBe(
      5,
    );
  });

  it("never fills less than 1 L outside the Nether, and nothing in it", () => {
    expect(flow(compute("railcraft-water-tank", { humidity: "0" }), "Water")).toBe(2.5);
    expect(flow(compute("railcraft-water-tank", { humidity: "0.05" }), "Water")).toBe(2.5);
    const nether = compute("railcraft-water-tank", { sky: "nether" });
    expect(nether.outputs).toEqual([]);
    expect(nether.warnings).toEqual(["Makes nothing in the Nether."]);
  });

  it("lists under Other and makes the dataset's water", () => {
    expect(getPowerSource("railcraft-water-tank")?.group).toBe("other");
    expect(resolvePowerResource("Water")?.id).toBe("water");
  });
});

describe("Railcraft Boiler (SteamBoiler at full heat)", () => {
  it("runs a 3x3x5 low pressure boiler on coal at 200 L/s of steam a tank", () => {
    const model = compute("railcraft-boiler");
    expect(flow(model, "Steam")).toBe(7200);
    expect(flow(model, "Water")).toBe(45);
    // 1.25 x 36 x (10.8 - 3.6) = 324 fuel units a second; coal burns 1600.
    expect(flow(model, "Coal")).toBeCloseTo(324 / 1600, 10);
    expect(model.euPerTick).toBe(0);
  });

  it("gives GTNH's doubled steam on high pressure tanks", () => {
    const model = compute("railcraft-boiler", { pressure: "high", size: "1x1x1" });
    expect(flow(model, "Steam")).toBe(800);
    expect(flow(model, "Water")).toBe(5);
    // 2.5 x (12.8 - 0.1) = 31.75 units a second.
    expect(flow(model, "Coal")).toBeCloseTo(31.75 / 1600, 10);
  });

  it("burns liquids at their heat per bucket", () => {
    const creosote = compute("railcraft-boiler", { pressure: "high", fuel: "Creosote Oil" });
    // 2.5 x 36 x (12.8 - 3.6) = 828 units a second.
    expect(flow(creosote, "Creosote Oil")).toBeCloseTo(129.375, 10);
    expect(creosote.stats.find((line) => line.label === "Firebox")?.value).toBe("Liquid Fueled");
    expect(
      flow(
        compute("railcraft-boiler", { pressure: "high", fuel: "Diesel (Fuel)" }),
        "Diesel (Fuel)",
      ),
    ).toBeCloseTo(17.25, 10);
    expect(
      flow(compute("railcraft-boiler", { size: "2x2x2", fuel: "Hootch" }), "Hootch"),
    ).toBeCloseTo(100_000 / 36_000, 10);
  });

  it("counts a blaze rod at Railcraft's 1000, not the furnace's 2400", () => {
    const model = compute("railcraft-boiler", { size: "2x2x2", fuel: "Blaze Rod" });
    expect(flow(model, "Blaze Rod")).toBeCloseTo(0.1, 10);
  });

  it("burns wood and coal dusts at their furnace values (fork issue 120)", () => {
    // TileEntityFurnace.getItemBurnTime: a wooden block 300, a stick or sapling 100. GTProxy.getBurnTime's ore
    // dictionary table: dustCoal and dustCharcoal 1600, dustLignite 1200, dustWood 100. 324 fuel units a second.
    for (const [fuel, burn] of [
      ["Oak Log", 300],
      ["Oak Planks", 300],
      ["Stick", 100],
      ["Oak Sapling", 100],
      ["Wood Pulp", 100],
      ["Coal Dust", 1600],
      ["Charcoal Dust", 1600],
      ["Lignite Coal Dust", 1200],
    ] as const) {
      const model = compute("railcraft-boiler", { fuel });
      expect(flow(model, fuel), fuel).toBeCloseTo(324 / burn, 10);
      expect(model.stats.find((line) => line.label === "Firebox")?.value).toBe("Solid Fueled");
    }
    expect(resolvePowerResource("Oak Log")?.id).toBe("minecraft:log");
    expect(resolvePowerResource("Oak Planks")?.id).toBe("minecraft:planks");
    expect(resolvePowerResource("Coal Dust")?.id).toBe("gregtech:gt.metaitem.01@2535");
  });

  it("takes any vanilla log in its Log slot, so a wood farm's spruce wires in", () => {
    const recipe = buildPowerRecipe("railcraft-boiler", { fuel: "Oak Log" }, "boiler")!;
    const log = recipe.inputs.find((input) => input.id === "minecraft:log")!;
    for (const id of ["minecraft:log", "minecraft:log@1", "minecraft:log@3", "minecraft:log2@1"]) {
      expect(resourceMatchesInput({ kind: "item", id }, log), id).toBe(true);
    }
    expect(resourceMatchesInput({ kind: "item", id: "minecraft:planks@1" }, log)).toBe(false);
    const planks = buildPowerRecipe("railcraft-boiler", { fuel: "Oak Planks" }, "boiler")!;
    expect(
      resourceMatchesInput(
        { kind: "item", id: "minecraft:planks@1" },
        planks.inputs.find((input) => input.id === "minecraft:planks")!,
      ),
    ).toBe(true);
  });

  it("states warm-up and pollution", () => {
    expect(boilerWarmUpTicks(1, 500)).toBeCloseTo(4519, 0);
    expect(boilerWarmUpTicks(1, 1000)).toBeCloseTo(9141, 0);
    const model = compute("railcraft-boiler");
    expect(model.stats.find((line) => line.label === "Warm-up")?.value).toBe("2 h 16 min");
    expect(model.stats.find((line) => line.label === "Pollution")?.value).toBe("180/s");
  });

  it("resolves every fuel it offers to a dataset resource", () => {
    const source = getPowerSource("railcraft-boiler");
    const fuel = source?.settings.find((setting) => setting.id === "fuel");
    expect(fuel?.type).toBe("select");
    for (const option of fuel?.type === "select" ? fuel.options : []) {
      expect(resolvePowerResource(option.key), option.key).toBeDefined();
    }
    expect(source?.group).toBe("steam");
  });
});
