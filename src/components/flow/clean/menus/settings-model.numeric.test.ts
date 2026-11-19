import { describe, expect, it } from "vitest";
import type { FactoryNode, Recipe } from "@/lib/model/types";
import {
  numericSettingRow,
  settingControls,
  steppedOption,
  type SettingControl,
} from "./settings-model";

// A numeric machine control (the Neutron Activator's pipe height, the Bacterial Vat's fill and shutter, the Extreme
// Entity Crusher's weapon damage) resolves to a single tier, its current value. The sheet and the card's chips must
// still offer it, as a number the player types or steps.
const activator: Recipe = {
  id: "na",
  name: "Neutron Activator: Adamantine Dust",
  machineType: "Neutron Activator",
  minimumTier: "NONE",
  durationTicks: 200,
  eut: 0,
  inputs: [{ kind: "fluid", id: "naquadah-adamantium solution", amount: 1000 }],
  outputs: [{ kind: "item", id: "bartworks:gt.bwmetagenerateddust@10066", amount: 1 }],
};

const node = (tiers?: Record<string, string>): FactoryNode => ({
  id: "n",
  recipeId: "na",
  machineCount: 1,
  parallel: 1,
  overclockTier: "NONE",
  enabled: true,
  position: { x: 0, y: 0 },
  ...(tiers ? { machineConfigTiers: tiers } : {}),
});

const pipe = (tiers?: Record<string, string>) =>
  settingControls(activator, node(tiers)).find((control) => control.id === "speedingPipeCasing");

describe("numeric machine controls on the clean card", () => {
  it("lists the Neutron Activator's pipe height, 4 by default, then the height the card is set to", () => {
    expect(pipe()).toMatchObject({
      label: "Pipe height",
      value: "4",
      currentKey: "4",
      numeric: { min: 4 },
    });
    expect(pipe({ speedingPipeCasing: "12" })).toMatchObject({ value: "12", currentKey: "12" });
  });

  it("steps a number by its step and stops at its limits instead of wrapping", () => {
    expect(steppedOption(pipe({ speedingPipeCasing: "12" })!, 1)?.key).toBe("13");
    expect(steppedOption(pipe({ speedingPipeCasing: "12" })!, -1)?.key).toBe("11");
    expect(steppedOption(pipe()!, -1)).toBeUndefined();
    const fill: SettingControl = {
      id: "fill",
      group: "Machine",
      label: "Kept full %",
      value: "50",
      warn: false,
      options: [{ key: "50", label: "50" }],
      currentKey: "50",
      pinDefault: false,
      numeric: { min: 0, max: 100, step: 0.1 },
    };
    expect(steppedOption(fill, 1)?.key).toBe("50.1");
    expect(steppedOption({ ...fill, currentKey: "100", value: "100" }, 1)).toBeUndefined();
  });

  it("takes a typed number snapped and clamped to the control's range, and ignores what is not a number", () => {
    const chosen: string[] = [];
    const row = numericSettingRow(pipe({ speedingPipeCasing: "12" })!, (key) => chosen.push(key))!;
    expect(row).toMatchObject({
      id: "speedingPipeCasing",
      label: "Pipe height",
      value: "12",
      text: "12",
    });
    row.commit("30");
    row.commit("2");
    row.commit("17.8");
    row.commit("tall");
    row.commit("");
    row.commit("12");
    row.step(1);
    expect(chosen).toEqual(["30", "4", "17", "13"]);
  });

  it("leaves a control with a list of options to the option row", () => {
    const list: SettingControl = {
      id: "coil",
      group: "Machine",
      label: "Coil",
      value: "a",
      warn: false,
      options: [
        { key: "a", label: "a" },
        { key: "b", label: "b" },
      ],
      currentKey: "a",
      pinDefault: true,
    };
    expect(numericSettingRow(list, () => {})).toBeUndefined();
  });
});
