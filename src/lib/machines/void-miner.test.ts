import { describe, expect, it } from "vitest";
import type { FactoryNode, MachineTier, Recipe } from "@/lib/model/types";
import { cardMachineMathTs } from "@/lib/core/machines";
import { enrichPassiveProductionRecipe } from "@/lib/model/passive-production";
import { applyRecipeInputOverrides } from "@/lib/model/recipe-input-overrides";
import { applyMachineHandlerToRecipe } from "@/lib/model/recipe-rules";
import { prefersCuratedMachineMath } from "@/lib/solver/runtime-calculation";
import { VOID_MINER_NOBLE_GAS } from "./void-miner";

// GT5U 5.09.54.275 MTEVoidMinerBase and VoidMinerRecipes. The dataset carries one recipe per dimension as NEI shows
// it: the planet marker (not consumed), the ore pool with each share rounded to 0.01% as its chance, 1 tick, 0 EU,
// handlers in the export's order (III, I, II), and the OverclockCalculator ladder on those numbers.
function dimensionRecipe(): Recipe {
  return {
    id: "void-miner-test",
    name: "Void Miner: Fayalite Ore",
    machineType: "Void Miner",
    minimumTier: "ULV",
    eut: 0,
    durationTicks: 1,
    source: { recipeMap: "Void Miner" },
    metadata: { recipeMapId: "gt.recipe.voidminer", specialValue: 0 },
    inputs: [{ kind: "item", id: "gtneioreplugin:blockdimensiondisplay_rb", amount: 1, consumed: false }],
    // Shares that sum to 0.993, as the Deep Dark's 331 rounded ones do.
    outputs: [
      { kind: "item", id: "ore:a", amount: 1, chance: 0.5 },
      { kind: "item", id: "ore:b", amount: 1, chance: 0.293 },
      { kind: "item", id: "ore:c", amount: 1, chance: 0.2 },
    ],
    machineHandlers: [
      { id: "void-miner-iii", label: "Void Miner III", kind: "multiblock", machineType: "Void Miner III", minimumTier: "ULV" },
      { id: "void-miner-i", label: "Void Miner I", kind: "multiblock", machineType: "Void Miner I", minimumTier: "ULV" },
      { id: "void-miner-ii", label: "Void Miner II", kind: "multiblock", machineType: "Void Miner II", minimumTier: "ULV" },
    ],
    runtimeCalculation: {
      oracleEligible: true,
      sourceKind: "gregtech-overclock-calculator",
      status: "computed",
      variants: [{ id: "tier-ulv", label: "ULV", overclockTier: "ULV", durationTicks: 1, eut: 0, parallel: 1 }],
    },
  } as Recipe;
}

const recipe = enrichPassiveProductionRecipe(dimensionRecipe());

function card(handler: string, tier: MachineTier, gas?: string): FactoryNode {
  return {
    machineHandlerId: handler,
    overclockTier: tier,
    hatchVoltageTier: tier,
    hatchAmps: 1,
    machineConfigTiers: gas ? { [VOID_MINER_NOBLE_GAS]: gas } : {},
  } as unknown as FactoryNode;
}

/** What the card makes and uses a second: ores summed over the pool, and each input. */
function perSecond(node: FactoryNode) {
  const inputsApplied = applyRecipeInputOverrides(recipe, node);
  const math = cardMachineMathTs(inputsApplied, node);
  const cyclesPerSecond = 20 / math.stats.durationTicks;
  const effective = applyMachineHandlerToRecipe(inputsApplied, node);
  const ores = effective.outputs.reduce(
    (total, output, index) => total + output.amount * (output.chance ?? 1) * math.outputMultipliers[index],
    0,
  );
  const consumed = effective.inputs.filter((input) => input.consumed !== false);
  return {
    eut: math.stats.eut,
    durationTicks: math.stats.durationTicks,
    ores: ores * cyclesPerSecond,
    inputs: Object.fromEntries(consumed.map((input) => [input.id, input.amount * cyclesPerSecond])),
  };
}

describe("Void Miner (MTEVoidMinerBase)", () => {
  it("draws VP[tier + 5] on a 10-tick cycle: the dataset says 0 EU/t and 1 tick", () => {
    expect(recipe.machineHandlers?.map((handler) => [handler.label, handler.minimumTier, handler.eut])).toEqual([
      ["Void Miner I", "LuV", 30720],
      ["Void Miner II", "ZPM", 122880],
      ["Void Miner III", "UV", 491520],
    ]);
    expect([recipe.minimumTier, recipe.eut, recipe.durationTicks]).toEqual(["LuV", 30720, 10]);
    expect(recipe.runtimeCalculation).toBeUndefined();
    expect(perSecond(card("void-miner-i", "LuV"))).toMatchObject({ eut: 30720, durationTicks: 10 });
    expect(perSecond(card("void-miner-ii", "ZPM"))).toMatchObject({ eut: 122880, durationTicks: 10 });
    expect(perSecond(card("void-miner-iii", "UV"))).toMatchObject({ eut: 491520, durationTicks: 10 });
  });

  it("makes 2, 4 and 6 ores a second, one draw a cycle across the pool", () => {
    expect(perSecond(card("void-miner-i", "LuV")).ores).toBeCloseTo(2, 9);
    expect(perSecond(card("void-miner-ii", "ZPM")).ores).toBeCloseTo(4, 9);
    expect(perSecond(card("void-miner-iii", "UV")).ores).toBeCloseTo(6, 9);
    // Each ore's share of the one draw, scaled so the rounded shares sum to one.
    [0.5, 0.293, 0.2].forEach((share, index) => expect(recipe.outputs[index].chance).toBeCloseTo(share / 0.993, 12));
  });

  it("never overclocks or parallels: a stronger hatch changes nothing", () => {
    expect(prefersCuratedMachineMath(applyMachineHandlerToRecipe(recipe, card("void-miner-i", "LuV")))).toBe(true);
    expect(perSecond(card("void-miner-i", "UHV"))).toMatchObject({ eut: 30720, durationTicks: 10 });
    expect(perSecond(card("void-miner-i", "UHV")).ores).toBeCloseTo(2, 9);
  });

  it("multiplies the ores by the noble gas boost for 1 L of gas a cycle", () => {
    expect(perSecond(card("void-miner-i", "LuV", "neon"))).toMatchObject({ inputs: { neon: 2 } });
    expect(perSecond(card("void-miner-i", "LuV", "neon")).ores).toBeCloseTo(8, 9);
    expect(perSecond(card("void-miner-ii", "ZPM", "krypton")).ores).toBeCloseTo(32, 9);
    expect(perSecond(card("void-miner-iii", "UV", "xenon")).ores).toBeCloseTo(96, 9);
    const oganesson = perSecond(card("void-miner-iii", "UV", "oganesson"));
    expect(oganesson.ores).toBeCloseTo(384, 9);
    expect(oganesson.inputs).toEqual({ oganesson: 2 });
    expect(oganesson.eut).toBe(491520);
    // No gas, no input: the planet marker is never used up.
    expect(perSecond(card("void-miner-iii", "UV")).inputs).toEqual({});
  });
});
