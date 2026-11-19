import { describe, expect, it } from "vitest";
import type { FactoryNode, MachineConfigControl, Recipe } from "@/lib/model/types";
import { applyMachineHandlerToRecipe } from "@/lib/model/recipe-rules";
import { getMachineStructuralParallels } from "@/lib/solver/machine-effects";
import { getNodePowerReport } from "@/lib/solver/power-report";
import { parallelReading } from "./clean-model";

const control = (id: string, keys: string[]): MachineConfigControl => ({
  id,
  label: id,
  minimumKey: keys[0]!,
  tiers: keys.map((key) => ({ key, label: key, resource: { kind: "item", id: key, amount: 1 } })),
});

// An Industrial Coke Oven: 16 parallels from its structure, 922 EU/t a batch of them before overclocking.
const oven: Recipe = {
  id: "ico-charcoal",
  name: "Industrial Coke Oven: Charcoal",
  machineType: "Industrial Coke Oven",
  minimumTier: "MV",
  durationTicks: 512,
  eut: 60,
  inputs: [{ kind: "item", id: "minecraft:log@32767", amount: 16 }],
  outputs: [{ kind: "item", id: "minecraft:coal@1", amount: 20 }],
  machineConfigControls: [
    control("heatingCoil", ["cupronickel", "kanthal"]),
    control("cokeOvenCasing", ["heat_resistant", "heat_proof"]),
    control("cokeOvenSlices", ["slice-1", "slice-2"]),
  ],
};
const node = (tier: "MV" | "EV", amps: number): FactoryNode => ({
  id: "ico",
  recipeId: oven.id,
  enabled: true,
  position: { x: 0, y: 0 },
  machineCount: 1,
  parallel: 1,
  coilTier: "kanthal",
  overclockTier: tier,
  hatchVoltageTier: tier,
  hatchAmps: amps,
  powerInputMode: "amps",
});

describe("the Parallel reading", () => {
  it("says the power supply caps it when fewer run than the structure allows", () => {
    const at = node("MV", 1);
    const report = getNodePowerReport(oven, at);
    const structure = getMachineStructuralParallels(applyMachineHandlerToRecipe(oven, at), at);
    expect(structure).toBe(16);
    expect(report.parallels).toBeLessThan(structure);
    const chip = parallelReading(report.parallels, structure, report.state === "ok")!;
    expect(chip.value).toBe(`×${report.parallels}`);
    expect(chip.powerCap).toEqual({ running: report.parallels, structure: 16 });
  });

  it("is the structure's own count when the supply pays for all of them", () => {
    const at = node("EV", 1);
    const report = getNodePowerReport(oven, at);
    const chip = parallelReading(report.parallels, 16, report.state === "ok")!;
    expect(chip.value).toBe("×16");
    expect(chip.powerCap).toBeUndefined();
  });

  it("shows one capped parallel, but no chip for a machine of one", () => {
    expect(parallelReading(1, 16, true)?.powerCap).toEqual({ running: 1, structure: 16 });
    expect(parallelReading(1, 1, true)).toBeUndefined();
    // A machine that cannot run at all (tier too low) has no power cap to report.
    expect(parallelReading(1, 16, false)).toBeUndefined();
  });
});
