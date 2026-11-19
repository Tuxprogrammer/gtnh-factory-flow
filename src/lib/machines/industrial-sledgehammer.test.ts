import { describe, expect, it } from "vitest";
import type { FactoryNode, MachineTier, Recipe } from "@/lib/model/types";
import {
  applyMachineHandlerToRecipe,
  getRecipeMachineConfigTierControls,
} from "@/lib/model/recipe-rules";
import { getMachineStructuralParallels } from "@/lib/solver/machine-effects";
import { getMachineTableControls } from "./machine-table";
import { getOverclockedRecipeStats } from "@/lib/solver/overclock";
import { getNodePowerReport } from "@/lib/solver/power-report";

// gt.recipe.hammer:5362355ae3a4efc6 as this instance's dataset has it: 1 tick at
// 30 EU/t, 9 pieces out. Its Industrial Sledgehammer handler carries only the
// scraper's display-only parallel knob, no solenoid.
const sunnarium: Recipe = {
  id: "oracle:stable-2.9.0-tuxmine:gregtech:gt-recipe-hammer:5362355ae3a4efc6",
  name: "Forge Hammer: Sunnarium Piece",
  machineType: "Forge Hammer",
  minimumTier: "LV",
  durationTicks: 1,
  eut: 30,
  inputs: [{ kind: "item", id: "advancedsolarpanel:asp_crafting_items", amount: 1 }],
  outputs: [{ kind: "item", id: "advancedsolarpanel:asp_crafting_items@9", amount: 9 }],
  machineHandlers: [
    {
      id: "forge-hammer",
      label: "Forge Hammer",
      kind: "single",
      machineType: "Forge Hammer",
      minimumTier: "LV",
      maximumTier: "UMV",
    },
    {
      id: "industrial-sledgehammer",
      label: "Industrial Sledgehammer",
      kind: "multiblock",
      machineType: "Industrial Sledgehammer",
      minimumTier: "LV",
      durationTicks: 1,
      machineConfigControls: [
        {
          id: "machineParallel",
          label: "Parallels",
          minimumKey: "fixed-6",
          tiers: [
            {
              key: "fixed-6",
              label: "6 Parallels",
              parallelMultiplier: 6,
              resource: { kind: "item", id: "x", amount: 1 },
            },
          ],
        },
      ],
    },
  ],
};

function sledge(tier: MachineTier, amps: number, solenoid?: string): FactoryNode {
  return {
    machineHandlerId: "industrial-sledgehammer",
    overclockTier: tier,
    hatchVoltageTier: tier,
    hatchAmps: amps,
    ...(solenoid ? { machineConfigTiers: { solenoidCoil: solenoid } } : {}),
  } as unknown as FactoryNode;
}

describe("Industrial Sledgehammer solenoid (MTEIndustrialForgeHammer)", () => {
  it("offers the solenoid, MV through UMV, defaulting to MV as plans saved without one", () => {
    const node = sledge("EV", 1);
    const controls = getRecipeMachineConfigTierControls(
      applyMachineHandlerToRecipe(sunnarium, node),
      node,
    );
    const solenoid = controls.find((control) => control.id === "solenoidCoil");
    expect(solenoid?.current.key).toBe("mv");
    expect(solenoid?.tiers.map((tier) => tier.key)).toEqual([
      "mv",
      "hv",
      "ev",
      "iv",
      "luv",
      "zpm",
      "uv",
      "uhv",
      "uev",
      "uiv",
      "umv",
    ]);
  });

  it.each([
    ["mv", 48],
    ["hv", 72],
    ["uv", 192],
    ["umv", 288],
  ])(
    "gives %s solenoids 6 x solenoid tier x 4 (one EV hatch) = %i parallels",
    (solenoid, parallels) => {
      const node = sledge("EV", 1, solenoid);
      expect(
        getMachineStructuralParallels(applyMachineHandlerToRecipe(sunnarium, node), node),
      ).toBe(parallels);
    },
  );

  it("runs what the solenoid and the supply allow", () => {
    // Four EV hatches (8 A) sum to IV voltage: UV solenoid gives 6 x 8 x 5 = 240
    // parallels, the half-tick run banks to 480 a tick under the cap of
    // 16,384 / 30 = 546: 480 x 9 x 20 = 86,400 pieces a second. An MV solenoid
    // gives 60 parallels, cheap enough for one overclock: 240 a tick, half that.
    const rate = (node: FactoryNode) => {
      const stats = getOverclockedRecipeStats(sunnarium, node);
      return (getNodePowerReport(sunnarium, node).parallels * 9 * 20) / stats.durationTicks;
    };
    expect(getNodePowerReport(sunnarium, sledge("EV", 8, "uv")).parallels).toBe(240);
    expect(rate(sledge("EV", 8, "uv"))).toBe(86400);
    expect(rate(sledge("EV", 8))).toBe(43200);
  });

  it("gives the Large Thermal Refinery the same solenoid knob it reads (MTEIndustrialThermalCentrifuge)", () => {
    expect(
      getMachineTableControls("Large Thermal Refinery").map((control) => control.id),
    ).toContain("solenoidCoil");
  });
});
