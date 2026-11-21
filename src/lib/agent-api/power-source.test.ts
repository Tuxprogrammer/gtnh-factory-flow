import { describe, expect, it } from "vitest";
import type { MachineHandler } from "@/lib/model/types";
import { steamAllowed, unpoweredKind } from "./power-source";

const machine = (label: string, machineType = label): Pick<MachineHandler, "label" | "machineType"> => ({ label, machineType });

describe("unpoweredKind", () => {
  it("names the steam line by the site's own steam rule", () => {
    for (const label of ["Steam Fuser", "Steam Grinder", "Steam Hearth", "High Pressure Steam Furnace", "High Pressure Alloy Smelter", "Steam Ammo Press"]) {
      expect(unpoweredKind(machine(label), { eut: 0 }), label).toBe("steam");
    }
  });

  it("names the brick machines, and the Coke Oven only when it draws no EU", () => {
    expect(unpoweredKind(machine("Bricked Blast Furnace"), { eut: 0 })).toBe("primitive");
    expect(unpoweredKind(machine("Coke Oven"), { eut: 0 })).toBe("primitive");
    // Older datasets named the Industrial Coke Oven's map "Coke Oven"; it draws EU.
    expect(unpoweredKind(machine("Coke Oven"), { eut: 32 })).toBeUndefined();
    expect(unpoweredKind(machine("Industrial Coke Oven"), { eut: 0 })).toBeUndefined();
  });

  it("keeps electric machines and other 0 EU machines electric, whatever their tier reads", () => {
    // The Multi Smelter's handler reads NONE, as the furnaces do: it is an electric multiblock.
    for (const label of ["Multi Smelter", "Electric Furnace", "Furnace", "Zyngen", "Large Bronze Boiler", "Neutron Activator", "Extreme Heat Exchanger"]) {
      expect(unpoweredKind(machine(label), { eut: 0 }), label).toBeUndefined();
    }
  });
});

describe("steamAllowed", () => {
  it("is off unless steam is true or the cap is ULV", () => {
    expect(steamAllowed(undefined)).toBe(false);
    expect(steamAllowed({ tierCap: "EV" })).toBe(false);
    expect(steamAllowed({ tierCap: "EV", steam: true })).toBe(true);
    expect(steamAllowed({ tierCap: "ULV" })).toBe(true);
    expect(steamAllowed({ steam: false, tierCap: "ULV" })).toBe(true);
  });
});
