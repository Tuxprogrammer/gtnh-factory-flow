import { describe, expect, it } from "vitest";
import { gtnhFuelProfiles } from "@/lib/model/fuels";
import { applyMachineHandlerToRecipe } from "@/lib/model/recipe-rules";
import {
  PROJECT_SCHEMA_VERSION,
  type FactoryProject,
  type MachineTier,
  type Recipe,
  type RecipeOutput,
} from "@/lib/model/types";
import { applyMachineOutputMultipliers } from "@/lib/solver/machine-effects";
import { calculateThroughput } from "@/lib/solver/throughput";
import { singleblockItemOutputSlots } from "./singleblock-output-slots";

const TIERS = ["LV", "MV", "HV", "EV", "IV", "LuV", "ZPM", "UV", "UHV", "UEV", "UIV", "UMV"];

// Plantball to plant mass as the 2.9 dataset exports it: four slots of the
// same item, the last two chanced. GT rolls output i only for i < slots.
const plantMass = (amount: number, chance?: number): RecipeOutput => ({
  kind: "item",
  id: "dreamcraft:maceratedplantmass",
  amount,
  ...(chance === undefined ? {} : { chance }),
});
const PLANT_OUTPUTS = [plantMass(1), plantMass(1), plantMass(1, 0.5), plantMass(1, 0.25)];

function maceratorRecipe(runtime: boolean): Recipe {
  return {
    id: "plantball",
    name: "Macerator: Plant Mass",
    machineType: "Macerator",
    minimumTier: "LV",
    durationTicks: 20,
    eut: 2,
    inputs: [{ kind: "item", id: "dreamcraft:plantball", amount: 2 }],
    outputs: PLANT_OUTPUTS,
    source: { recipeMap: "Macerator" },
    machineHandlers: [
      { id: "macerator", label: "Macerator", machineType: "Macerator", kind: "single", minimumTier: "LV", maximumTier: "UMV", availableTiers: TIERS },
      { id: "steam-macerator", label: "Steam Macerator", machineType: "Steam Macerator", kind: "single", minimumTier: "LV" },
      { id: "industrial-maceration-stack", label: "Industrial Maceration Stack", machineType: "Industrial Maceration Stack", kind: "multiblock", minimumTier: "LV" },
    ],
    ...(runtime
      ? {
          runtimeCalculation: {
            sourceKind: "gregtech-overclock-calculator",
            status: "computed",
            oracleEligible: true,
            strict: true,
            variants: ["LV", "MV", "HV", "EV", "IV"].map((tier, step) => ({
              id: `tier-${tier.toLowerCase()}`,
              label: tier,
              overclockTier: tier,
              durationTicks: Math.max(1, 20 / 2 ** step),
              eut: 2 * 4 ** step,
              parallel: 1,
              outputs: PLANT_OUTPUTS.map(({ kind, id, amount, chance }) => ({ kind, id, amount, chance })),
            })),
          },
        }
      : {}),
  };
}

function plantMassPerCraft(recipe: Recipe, tier: MachineTier, machineHandlerId = "macerator") {
  const project: FactoryProject = {
    schemaVersion: PROJECT_SCHEMA_VERSION,
    id: "slots",
    name: "Output slots",
    recipes: [recipe],
    nodes: [
      { id: "mac", recipeId: recipe.id, machineCount: 1, parallel: 1, overclockTier: tier, machineHandlerId, enabled: true, position: { x: 0, y: 0 } },
    ],
    edges: [],
    fuelProfiles: gtnhFuelProfiles,
    selectedFuelProfileId: "biodiesel",
  };
  const node = calculateThroughput(project, { generatedAt: "fixed" }).nodes.mac;
  return node.outputs["item:dreamcraft:maceratedplantmass"].amountPerSecond / node.operationRatePerSecond;
}

describe("single-block item output slots", () => {
  it("follows the macerator's slots by tier (MTEBasicMachineWithRecipeBuilder setSlotsCount)", () => {
    const macerator = { machineType: "Macerator" };
    expect(["LV", "MV", "HV", "EV", "IV", "UMV"].map((tier) => singleblockItemOutputSlots(macerator, tier as "LV"))).toEqual([1, 1, 2, 3, 4, 4]);
    expect(singleblockItemOutputSlots({ machineType: "Mixer" }, "MV")).toBe(1);
    expect(singleblockItemOutputSlots({ machineType: "Mixer" }, "HV")).toBe(4);
    expect(singleblockItemOutputSlots({ machineType: "Steam Macerator" }, "LV")).toBe(1);
    expect(singleblockItemOutputSlots({ machineType: "Bending Machine" }, "LV")).toBeUndefined();
    expect(singleblockItemOutputSlots({ machineType: "Macerator", machineProfile: { machineType: "Macerator", minimumTier: "LV", kind: "multiblock" } }, "LV")).toBeUndefined();
  });

  it("credits an LV macerator with plantball's first output only, from the runtime variants", () => {
    const recipe = maceratorRecipe(true);
    expect(plantMassPerCraft(recipe, "LV")).toBeCloseTo(1);
    expect(plantMassPerCraft(recipe, "MV")).toBeCloseTo(1);
    expect(plantMassPerCraft(recipe, "HV")).toBeCloseTo(2);
    expect(plantMassPerCraft(recipe, "EV")).toBeCloseTo(2.5);
    expect(plantMassPerCraft(recipe, "IV")).toBeCloseTo(2.75);
  });

  it("applies the same limit without runtime variants, to steam macerators, and not to multiblocks", () => {
    const recipe = maceratorRecipe(false);
    expect(plantMassPerCraft(recipe, "MV")).toBeCloseTo(1);
    expect(plantMassPerCraft(recipe, "LuV")).toBeCloseTo(2.75);
    expect(plantMassPerCraft(recipe, "LV", "steam-macerator")).toBeCloseTo(1);
    expect(plantMassPerCraft(recipe, "LV", "industrial-maceration-stack")).toBeCloseTo(2.75);
  });

  it("shows an output with no slot at zero on the card, keeping the port", () => {
    const ore: Recipe = {
      ...maceratorRecipe(false),
      outputs: [
        { kind: "item", id: "crushed", amount: 2 },
        { kind: "item", id: "stone-dust", amount: 1, chance: 0.1 },
      ],
    };
    const effective = applyMachineHandlerToRecipe(ore, { machineHandlerId: "macerator" });
    const lv = applyMachineOutputMultipliers(effective, {}, "LV").outputs;
    expect(lv.map((output) => [output.id, output.amount])).toEqual([["crushed", 2], ["stone-dust", 0]]);
    const hv = applyMachineOutputMultipliers(effective, {}, "HV").outputs;
    expect(hv.map((output) => [output.id, output.amount])).toEqual([["crushed", 2], ["stone-dust", 1]]);
  });
});
