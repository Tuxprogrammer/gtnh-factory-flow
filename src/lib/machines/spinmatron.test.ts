import { describe, expect, it } from "vitest";
import { getRecipeMachineConfigTierControls } from "@/lib/model/recipe-rules";
import type { FactoryNode, Recipe } from "@/lib/model/types";
import { buildMachineContext } from "@/lib/solver/machine-effects";
import { settingControls } from "@/components/flow/clean/menus/settings-model";
import { getMachineBehaviour, resolveCoefficient } from "./machine-table";

const recipe: Recipe = {
  id: "spin",
  name: "Spinmatron test",
  machineType: "Spinmatron-2737",
  minimumTier: "LV",
  durationTicks: 100,
  eut: 30,
  inputs: [{ kind: "item", id: "feed", amount: 1 }],
  outputs: [{ kind: "item", id: "product", amount: 1 }],
};
const node = (settings: Record<string, string>) => ({
  id: "spinmatron",
  recipeId: recipe.id,
  machineCount: 1,
  parallel: 1,
  overclockTier: "LV",
  enabled: true,
  position: { x: 0, y: 0 },
  machineConfigTiers: settings,
});
const sumControl = (sum: string) =>
  getRecipeMachineConfigTierControls(recipe, node({ sumTurbineTier: sum })).find(
    (control) => control.id === "sumTurbineTier",
  )!;
const parallels = (settings: Record<string, string>) =>
  resolveCoefficient(
    getMachineBehaviour("Spinmatron-2737")!.parallels,
    buildMachineContext(recipe, node(settings)),
    1,
  );

// MTESpinmatron.getSumRotorLevels: 2 rotor slots per structure tier (4 tiers, so 8 rotors), each adding its
// material's tool quality (a huge rotor all of it). Universium's 32 is the highest, so 8 huge Universium rotors sum 256.
describe("Spinmatron rotor level sum", () => {
  it.each(["1", "5", "32", "33", "240", "256"])("offers every sum up to 256 (%s)", (sum) => {
    expect(sumControl(sum).current.key).toBe(sum);
  });

  it("is a typed number 1 to 256 in the card's settings sheet", () => {
    const control = settingControls(recipe, node({ sumTurbineTier: "240" }) as FactoryNode).find(
      (entry) => entry.id === "sumTurbineTier",
    )!;
    expect(control.numeric).toEqual({ min: 1, max: 256 });
    expect(control.currentKey).toBe("240");
  });

  it("clamps a sum past 256 to 256", () => {
    expect(sumControl("300").current.key).toBe("256");
  });

  it("gives 4 parallels per level, x1.25 on Biocatalysed Propulsion Fluid, /32 in heavy mode", () => {
    expect(parallels({ sumTurbineTier: "256" })).toBe(1024);
    expect(
      parallels({ sumTurbineTier: "240", spinmatronFuel: "biocatalysed-propulsion-fluid" }),
    ).toBe(1200);
    expect(parallels({ sumTurbineTier: "256", spinmatronMode: "heavy" })).toBe(32);
    expect(parallels({ sumTurbineTier: "5", spinmatronMode: "heavy" })).toBe(1);
  });
});
