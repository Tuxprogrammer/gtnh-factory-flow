import { describe, expect, it } from "vitest";
import { powerPlannerData } from "../planner-data";
import { getPowerSource } from "../registry";
import { buildPowerSettingsReader, type PowerSetting } from "../types";

/**
 * Steam makers against the pack's Java (GT5U 5.09.54.20). Each case names
 * the class whose rule it pins.
 */
function compute(sourceId: string, settings: Record<string, string> = {}) {
  const source = getPowerSource(sourceId);
  if (!source) {
    throw new Error(`No power source ${sourceId}`);
  }
  return source.compute(buildPowerSettingsReader(source, settings));
}

function setting(sourceId: string, settingId: string): PowerSetting | undefined {
  return getPowerSource(sourceId)?.settings.find((entry) => entry.id === settingId);
}

function optionKeys(sourceId: string, settingId: string): string[] {
  const found = setting(sourceId, settingId);
  return found?.type === "select" ? found.options.map((option) => option.key) : [];
}

function flow(model: ReturnType<typeof compute>, name: string): number | undefined {
  return [...model.inputs, ...model.outputs].find((line) => line.name === name)?.perSecond;
}

describe("Thermal Boiler (RecipesGregTech.thermalBoilerRecipes)", () => {
  it("turns 1000 L/s of lava into 16,000 L/s of plain Steam, pahoehoe lava and obsidian", () => {
    // Obsidian is the 10% chance per 1000 L that MTEThermalBoiler keeps without a Lava Filter.
    const model = compute("thermal-boiler", { fluid: "Lava", intake: "1000" });
    expect(model.outputs).toEqual([
      { name: "Steam", perSecond: 16_000, unit: "L" },
      { name: "Pahoehoe Lava", perSecond: 1000, unit: "L" },
      { name: "Obsidian", perSecond: 0.1, unit: "item" },
    ]);
    expect(flow(model, "Water")).toBe(100);
  });

  it("turns pahoehoe lava into plain Steam at the same 16 per L, and obsidian at 37%", () => {
    const model = compute("thermal-boiler", { fluid: "Pahoehoe Lava", intake: "1000" });
    expect(model.outputs).toEqual([
      { name: "Steam", perSecond: 16_000, unit: "L" },
      { name: "Obsidian", perSecond: 0.37, unit: "item" },
    ]);
  });

  it("keeps hot coolant and hot solar salt on superheated steam", () => {
    const coolant = compute("thermal-boiler", { fluid: "Hot Coolant", intake: "500" });
    expect(coolant.outputs[0]).toEqual({ name: "SH Steam", perSecond: 100_000, unit: "L" });
    const salt = compute("thermal-boiler", { fluid: "Hot Solar Salt", intake: "100" });
    expect(salt.outputs[0]).toEqual({ name: "SH Steam", perSecond: 100_000, unit: "L" });
  });

  it("has no circuit setting: MTEThermalBoiler reads none", () => {
    expect(setting("thermal-boiler", "tier")).toBeUndefined();
  });
});

describe("heat exchanger circuit (MTEHeatExchanger, MTEAdvHeatExchanger, MTEExtremeHeatExchanger)", () => {
  it("is a programmed circuit from 1 to 25", () => {
    for (const id of ["large-heat-exchanger", "whakawhiti-wera-xl", "extreme-heat-exchanger"]) {
      expect(setting(id, "tier")).toMatchObject({
        type: "number",
        label: "Circuit",
        min: 1,
        max: 25,
      });
    }
  });

  it("computes a saved circuit 5 exactly as before", () => {
    // Lava: threshold 1000 - 4 x 37.5 = 850, cap 1700, SH at 80 per L, 94% efficiency.
    const model = compute("large-heat-exchanger", { fluid: "Lava", intake: "2000", tier: "5" });
    expect(model.outputs[0].name).toBe("SH Steam");
    expect(model.outputs[0].perSecond).toBeCloseTo(1700 * 80 * 0.94, 6);
    expect(flow(model, "Lava")).toBe(1700);
  });

  it("truncates the LHE threshold to whole litres, as MTEHeatExchanger's int field does", () => {
    // Lava on circuit 2: 1000 - 37.5 = 962.5, kept as 962, so the cap is 1924.
    const model = compute("large-heat-exchanger", { fluid: "Lava", intake: "2000", tier: "2" });
    expect(flow(model, "Lava")).toBe(1924);
    expect(model.stats.find((line) => line.label === "Threshold")?.value).toBe("962 L/s");
  });

  it("lowers the LHE threshold down to circuit 25", () => {
    // Hot coolant: 800 - 24 x 30 = 80 L/s, so 100 L/s is over it.
    const model = compute("large-heat-exchanger", {
      fluid: "Hot Coolant",
      intake: "100",
      tier: "25",
    });
    expect(model.outputs[0].name).toBe("SH Steam");
    expect(model.outputs[0].perSecond).toBeCloseTo(100 * 200 * (1 - 0.015 * 24), 6);
  });

  it("never lets the EHE threshold fall below 1", () => {
    // Hot solar salt: 1600 - 24 x 150 is negative; the Java clamps it to 1.
    const model = compute("extreme-heat-exchanger", {
      fluid: "Hot Solar Salt",
      intake: "1",
      tier: "25",
    });
    expect(model.outputs[0].name).toBe("SC Steam");
    expect(model.stats.find((line) => line.label === "Threshold")?.value).toBe("1 L/s");
  });

  it("rounds the EHE's steam down to whole 160 L lots a tick", () => {
    // Hot coolant at circuit 2: (int) (1,280,000 x 0.985 x 8,000 / 128,000) = 78,800 a tick, so 492 lots of
    // 160 L: 78,720 L/t of SC steam, 1,574,400 L/s, not 1,576,000.
    const model = compute("extreme-heat-exchanger", { fluid: "Hot Coolant", intake: "8000", tier: "2" });
    expect(model.outputs[0]).toMatchObject({ name: "SC Steam", perSecond: 1_574_400 });
    expect(flow(model, "Distilled Water")).toBe(9840);
  });

  it("caps the Whakawhiti Wera XL at twice its lowered threshold", () => {
    // Lava at circuit 10: threshold 32,000 - 9 x 1,200 = 21,200, so at most 42,400 L/s.
    const model = compute("whakawhiti-wera-xl", { fluid: "Lava", intake: "64000", tier: "10" });
    expect(flow(model, "Lava")).toBe(42_400);
    expect(flow(model, "Pahoehoe Lava")).toBe(42_400);
    expect(model.outputs[0].perSecond).toBeCloseTo(42_400 * 80 * (1 - 0.015 * 9), 6);
    expect(model.warnings).toEqual(["Intake is capped at 42,400 L/s for this fluid."]);
  });

  it("keeps the XL at its full 64,000 L/s on circuit 1", () => {
    const model = compute("whakawhiti-wera-xl", { fluid: "Lava", intake: "64000", tier: "1" });
    expect(flow(model, "Lava")).toBe(64_000);
  });
});

describe("large boilers (MTELargeBoilerBase, LargeBoilerFuelBackend)", () => {
  it("offers the superheated boilers only the ALLOWED_FUELS fluids", () => {
    const allowed = [
      "Ether",
      "Gasoline",
      "Cetane-Boosted Diesel",
      "Ethanol Gasoline",
      "Jet Fuel No.3",
      "Jet Fuel A",
      "High Octane Gasoline",
    ];
    for (const id of ["large-titanium-boiler", "large-tungstensteel-boiler"]) {
      expect(
        optionKeys(id, "liquidFuel")
          .filter((key) => key !== "None")
          .sort(),
      ).toEqual([...allowed].sort());
    }
  });

  it("burns a solid for fuel value / 80 ticks, log bonus, times the tier's runtimeBoost", () => {
    // LargeBoilerFuelBackend.getBurntimeRatioTicks(ticks, 20): seconds x max(1, 1 + ln(s/10) x 0.025), to 0.01 s.
    const ratioTicks = (ticks: number) => {
      const seconds = ticks / 20;
      return Math.trunc(Math.round(seconds * Math.max(1, 1 + Math.log(seconds / 10) * 0.025) * 100) / 5);
    };
    const boost: Record<string, (ticks: number) => number> = {
      bronzeSolid: (ticks) => ticks * 2,
      steelSolid: (ticks) => ticks,
      titaniumSolid: (ticks) => Math.trunc((ticks * 3) / 10),
      tungstensteelSolid: (ticks) => Math.trunc((ticks * 15) / 100),
    };
    const tables = powerPlannerData.boilerFuels as Record<
      string,
      Array<{ euPerItem?: number; burnTime?: number }>
    >;
    for (const [table, rule] of Object.entries(boost)) {
      for (const row of tables[table]) {
        expect(row.burnTime).toBeCloseTo(rule(ratioTicks(Math.trunc((row.euPerItem ?? 0) / 80))) / 20, 9);
      }
    }
  });

  it("burns a liquid for the recipe duration LargeBoilerFuelBackend.addRecipe sets, times the tier's runtimeBoost", () => {
    // Whole ticks all the way: base seconds stretched to 0.01 s, then 20 ticks a second, then the boiler's boost.
    const burn = (liquid: string, boiler: string) =>
      flow(compute(boiler, { liquidFuel: liquid, solidFuel: "None" }), liquid);
    expect(burn("Propane", "large-bronze-boiler")).toBeCloseTo(1000 / 23.2, 9);
    expect(burn("Propane", "large-steel-boiler")).toBeCloseTo(1000 / 11.6, 9);
    expect(burn("Butane", "large-steel-boiler")).toBeCloseTo(1000 / 14.95, 9);
    expect(burn("Ethanol Gasoline", "large-titanium-boiler")).toBeCloseTo(1000 / 17.15, 9);
    expect(burn("Ethanol Gasoline", "large-tungstensteel-boiler")).toBeCloseTo(1000 / 8.55, 9);
    // The bronze boiler's boost is exactly twice the steel's.
    const tables = powerPlannerData.boilerFuels as Record<string, Array<{ name: string; burnTime?: number }>>;
    for (const steel of tables.steelLiquid) {
      const bronze = tables.bronzeLiquid.find((row) => row.name === steel.name);
      expect(bronze?.burnTime).toBeCloseTo((steel.burnTime ?? 0) * 2, 9);
    }
  });

  it("burns Solid Super Fuel for 130.7s in bronze and 19.6s in titanium", () => {
    const bronze = compute("large-bronze-boiler", {
      liquidFuel: "None",
      solidFuel: "Solid Super Fuel",
    });
    expect(flow(bronze, "Solid Super Fuel")).toBeCloseTo(1 / 130.7, 12);
    const titanium = compute("large-titanium-boiler", {
      liquidFuel: "None",
      solidFuel: "Solid Super Fuel",
    });
    expect(flow(titanium, "Solid Super Fuel")).toBeCloseTo(1 / 19.6, 12);
    const steel = compute("large-steel-boiler", {
      liquidFuel: "None",
      solidFuel: "Block of Diamond",
    });
    expect(flow(steel, "Block of Diamond")).toBeCloseTo(1 / 706.5, 12);
  });
});

describe("singleblock boilers (MTEBoiler)", () => {
  it("refuses sulfur dust in the coal boilers but not the GT++ Advanced Boiler", () => {
    expect(optionKeys("small-coal-boiler", "solidFuel")).not.toContain("Sulfur Dust");
    expect(optionKeys("large-coal-boiler", "solidFuel")).not.toContain("Sulfur Dust");
    expect(optionKeys("small-coal-boiler", "solidFuel")).toContain("Lithium Dust");
    expect(optionKeys("advanced-boiler", "solidFuel")).toContain("Sulfur Dust");
  });

  it("gives the Advanced Boiler the same item time on every tier", () => {
    // Solid Super Fuel: (10,000 / 2 + 200) degrees x 41/20.
    for (const tier of ["LV", "MV", "HV"]) {
      const model = compute("advanced-boiler", { tier, solidFuel: "Solid Super Fuel" });
      expect(model.inputs[0].perSecond).toBeCloseTo(1 / 10_660, 12);
    }
  });

  it("burns wood and coal dusts in the Advanced Boiler at their furnace values", () => {
    // MTEAdvancedBoilerBase.getBurnTime is GameRegistry.getFuelValue, else TileEntityFurnace.getItemBurnTime: a wooden
    // block 300, a stick, sapling or wood pulp 100, coal dust 1600. Seconds per item (floor(bt/10)/2 + floor(bt/500))
    // x 41/20; a blaze rod keeps vanilla's 2400 here.
    const seconds: Array<[string, number]> = [
      ["Oak Log", 30.75],
      ["Oak Planks", 30.75],
      ["Stick", 10.25],
      ["Oak Sapling", 10.25],
      ["Wood Pulp", 10.25],
      ["Coal Dust", 170.15],
      ["Lignite Coal Dust", 127.1],
      ["Blaze Rod", 254.2],
    ];
    for (const [name, expected] of seconds) {
      const model = compute("advanced-boiler", { solidFuel: name });
      expect(model.inputs[0].name).toBe(name);
      expect(model.inputs[0].perSecond).toBeCloseTo(1 / expected, 12);
    }
    expect(compute("advanced-boiler", { solidFuel: "Oak Log" }).inputs[0].alternatives).toEqual([
      { kind: "item", id: "minecraft:log@32767", displayName: "Log" },
      { kind: "item", id: "minecraft:log2@32767", displayName: "Log" },
    ]);
    expect(
      compute("advanced-boiler", { solidFuel: "Coal" }).inputs[0].alternatives,
    ).toBeUndefined();
    expect(optionKeys("advanced-boiler", "solidFuel")).toEqual(
      expect.arrayContaining([
        "Oak Log",
        "Oak Planks",
        "Stick",
        "Oak Sapling",
        "Wood Pulp",
        "Coal Dust",
      ]),
    );
  });

  it("leaves one obsidian per 1000 L of lava", () => {
    const model = compute("lava-boiler");
    expect(flow(model, "Obsidian")).toBeCloseTo(flow(model, "Lava")! / 1000, 12);
  });

  it("scales the solar boiler's steam and water by its duty", () => {
    const full = compute("solar-boiler", { model: "steel" });
    expect(flow(full, "Steam")).toBe(360);
    const half = compute("solar-boiler", { model: "steel", duty: "50" });
    expect(flow(half, "Steam")).toBe(180);
    expect(flow(half, "Distilled Water")).toBeCloseTo(180 / 160, 12);
  });

  it("states when the solar boiler calcifies on regular water", () => {
    const model = compute("solar-boiler", { model: "bronze", waterKind: "Water" });
    expect(model.warnings).toEqual([
      "Regular water calcifies this boiler: full 120 L/s for 15 hours of run time, then down to 40 L/s by 25 hours. Distilled water does not.",
    ]);
  });
});

describe("unlock chips (the controller's own recipe)", () => {
  it("tiers the exchangers by their controller recipes", () => {
    expect(getPowerSource("thermal-boiler")?.unlock).toBe("IV");
    expect(getPowerSource("large-heat-exchanger")?.unlock).toBe("EV");
    expect(getPowerSource("whakawhiti-wera-xl")?.unlock).toBe("LuV");
    expect(getPowerSource("extreme-heat-exchanger")?.unlock).toBe("IV");
  });
});
