import { describe, expect, it } from "vitest";
import { powerPlannerData } from "./planner-data";
import { getPowerSource } from "./registry";
import { buildPowerSettingsReader } from "./types";

/**
 * The plasma fuel list is what RecipeMaps.plasmaFuels holds: ProcessingCell's cellPlasma values for materials built
 * with addPlasma(), plus GT++ RecipeGenPlasma. MTELargeTurbinePlasma.fluidIntoPower makes 0 EU from any other plasma.
 */
function plasmaKeys(sourceId: string): string[] {
  const source = getPowerSource(sourceId);
  const fuel = source?.settings.find((setting) => setting.id === "fuel");
  if (!fuel || fuel.type !== "select") throw new Error(`No fuel select on ${sourceId}`);
  return fuel.options.map((option) => option.key);
}

describe("plasma turbine fuels", () => {
  it("offers the plasmas the game burns and none it makes 0 EU from", () => {
    for (const id of ["large-plasma-generator", "xl-turbo-plasma-turbine", "plasma-generator"]) {
      const keys = plasmaKeys(id);
      for (const burns of ["Selenium Plasma", "Uranium 233 Plasma", "Plutonium-238 Plasma"]) {
        expect(keys, `${id} ${burns}`).toContain(burns);
      }
      for (const dead of [
        "Creon Plasma",
        "Chrome Plasma",
        "Naquadah Plasma",
        "Osmium Plasma",
        "Iridium Plasma",
        "Platinum Plasma",
        "Plutonium-239 Plasma",
      ]) {
        expect(keys, `${id} ${dead}`).not.toContain(dead);
      }
    }
  });

  it("prices a plasma with no hand-written value at 1024 EU/L per unit of mass", () => {
    const values = new Map(powerPlannerData.plasmas.map((row) => [row.name, row.euPerLiter]));
    // Selenium is 79 mass (34 protons, 45 neutrons), Plutonium-238 is 238 mass.
    expect(values.get("Selenium Plasma")).toBe(79 * 1024);
    expect(values.get("Technetium Plasma")).toBe(98 * 1024);
    expect(values.get("Plutonium-238 Plasma")).toBe(238 * 1024);
    // The generator burns litres in proportion: the same power from a richer plasma takes fewer.
    const source = getPowerSource("plasma-generator");
    expect(source).toBeDefined();
    const litres = (fuel: string) =>
      source!.compute(buildPowerSettingsReader(source!, { fuel })).inputs[0].perSecond;
    expect(litres("Selenium Plasma") * 79 * 1024).toBeCloseTo(
      litres("Helium Plasma") * (values.get("Helium Plasma") ?? 0),
      6,
    );
  });
});
