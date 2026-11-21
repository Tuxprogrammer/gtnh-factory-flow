import { describe, expect, it } from "vitest";
import { readConformanceJson } from "@/test-support/conformance";
import { powInt } from "./game-math";
import { getMachineBehaviour, resolveCoefficient } from "./machine-table";

// conformance/machines/coke-oven-discount.json holds GTUtility.powInt's doubles, written from the game's own method on
// the JVM; the Java side (GameMath, MachineTableEntries) is held to the same file.
const golden = readConformanceJson("machines", "coke-oven-discount.json") as {
  cokeOvenDiscount: [number, string, string][];
  total: {
    coilTier: number;
    eut: number;
    parallels: number;
    overclocks: number;
    euPerTick: number;
  };
  powInt: [string, number, string][];
};

const view = new DataView(new ArrayBuffer(8));
const bitsOf = (x: number): string => {
  view.setFloat64(0, x);
  return view.getBigUint64(0).toString(16).padStart(16, "0");
};
const fromBits = (hex: string): number => {
  view.setBigUint64(0, BigInt(`0x${hex}`));
  return view.getFloat64(0);
};

/** The table's EU factor for a machine at a heating coil tier (0 = cupronickel), on an EV hatch with no recipe. */
const discount = (machine: string, coilTier: number): number =>
  resolveCoefficient(
    getMachineBehaviour(machine)?.power,
    { tier: (id) => (id === "heatingCoil" ? coilTier : 0), value: () => 0, voltageTier: 4 },
    1,
  );

describe("coil discounts taken with the game's powInt", () => {
  it("powInt gives the game's double for every base and exponent in the file", () => {
    for (const [baseHex, exp, answer] of golden.powInt) {
      expect(bitsOf(powInt(fromBits(baseHex), exp)), `${baseHex}^${exp}`).toBe(answer);
    }
  });

  it("the Industrial Coke Oven discount is the game's at coil tiers 1 to 14", () => {
    expect(golden.cokeOvenDiscount).toHaveLength(14);
    for (const [tier, answer] of golden.cokeOvenDiscount) {
      expect(bitsOf(discount("Industrial Coke Oven", tier - 1)), `tier ${tier}`).toBe(answer);
    }
  });

  it("keeps the game's last EU/t on a huge total", () => {
    // OverclockCalculator: ceil(eut * parallels * modifier * 4^overclocks); pow's discount made it 877,142,830.
    const t = golden.total;
    const modifier = discount("Industrial Coke Oven", t.coilTier - 1);
    expect(Math.ceil(t.eut * t.parallels * modifier * powInt(4, t.overclocks))).toBe(877_142_829);
    expect(t.euPerTick).toBe(877_142_829);
  });

  it("every other coil machine takes its discount with powInt too", () => {
    for (let t = 0; t < 14; t++) {
      expect(discount("Mega Oil Cracker", t)).toBe(powInt(0.9, t + 1));
      expect(discount("Large Fluid Extractor", t)).toBe(0.8 * powInt(0.9, t));
      expect(discount("Large Thermal Refinery", t)).toBe(0.8 * powInt(0.95, t));
      expect(discount("Mega Alloy Blast Smelter", t)).toBe(powInt(0.95, Math.max(0, t + 1 - 4)));
    }
  });
});
