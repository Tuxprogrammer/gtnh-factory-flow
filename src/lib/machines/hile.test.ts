import { describe, expect, it } from "vitest";
import {
  PROJECT_SCHEMA_VERSION,
  type FactoryNode,
  type FactoryProject,
  type Recipe,
} from "@/lib/model/types";
import {
  applyMachineHandlerToRecipe,
  getRecipeMachineConfigTierControls,
} from "@/lib/model/recipe-rules";
import { getMachineStructuralParallels } from "@/lib/solver/machine-effects";
import { getOverclockedRecipeStats } from "@/lib/solver/overclock";
import { describePowerStall, getNodePowerReport } from "@/lib/solver/power-report";
import { calculateThroughput } from "@/lib/solver/throughput";
import { HILE_SOURCES } from "./hile";

const recipe: Recipe = {
  id: "hile-test",
  name: "Laser engraving",
  machineType: "Hyper-Intensity Laser Engraver",
  minimumTier: "LV",
  eut: 30,
  durationTicks: 350,
  inputs: [{ kind: "item", id: "wafer", amount: 1 }],
  outputs: [{ kind: "item", id: "chip", amount: 1 }],
  // Actual old exporter key, with deliberately misleading scraped effects.
  machineConfigControls: [
    {
      id: "laserSource",
      label: "Laser Source",
      minimumKey: "a256",
      tiers: [
        {
          key: "a256",
          label: "256A Laser",
          parallelMultiplier: 99,
          resource: { kind: "item", id: "source", amount: 1 },
        },
      ],
    },
  ],
};
const node: FactoryNode = {
  id: "hile",
  recipeId: recipe.id,
  machineCount: 1,
  parallel: 1,
  position: { x: 0, y: 0 },
  enabled: true,
  overclockTier: "IV",
  hatchVoltageTier: "IV",
  hatchAmps: 1,
  machineConfigTiers: { laserSource: "uhv-65536" },
};

describe("HILE issue #50", () => {
  it("offers one source selector with all 46 registered hatch combinations", () => {
    const controls = getRecipeMachineConfigTierControls(recipe, node);
    expect(controls.map((control) => control.id)).toEqual(["laserSource"]);
    expect(controls[0].tiers).toHaveLength(46);
    expect(controls[0].current.label).toBe("UHV · 65,536A");
    expect(HILE_SOURCES.filter((s) => s.tier === "IV").map((s) => s.amps)).toEqual([256]);
    expect(HILE_SOURCES.filter((s) => s.tier === "UHV").map((s) => s.amps)).toEqual([
      256, 1024, 4096, 16384, 65536,
    ]);
    expect(HILE_SOURCES.at(-1)).toMatchObject({ tier: "UXV", amps: 536_870_912 });
  });

  it.each([
    [256, 6],
    [1024, 10],
    [4096, 16],
    [16384, 25],
    [65536, 40],
    [262144, 64],
    [1048576, 101],
    [4194304, 161],
    [16777216, 256],
    [536870912, 812],
  ])("gives %iA a structural cap of %i parallels", (amps, parallels) => {
    for (const source of HILE_SOURCES.filter((s) => s.amps === amps)) {
      expect(
        getMachineStructuralParallels(recipe, {
          ...node,
          machineConfigTiers: { laserSource: source.key },
        }),
      ).toBe(parallels);
    }
  });

  it("pays 40 parallels and overclocks from the energy hatches, not laser amps", () => {
    const stats = getOverclockedRecipeStats(recipe, node);
    const power = getNodePowerReport(recipe, node);
    expect(power.poolEuT).toBe(8192);
    expect(power.parallels).toBe(40);
    expect(stats.overclockSteps).toBe(1);
    expect(stats.durationTicks).toBe(50); // 350 / 3.5 / 2
    expect(power.drawEuT).toBe(30 * 0.8 * 40 * 4);
    const low = getNodePowerReport(recipe, { ...node, hatchVoltageTier: "LV" });
    expect(low.poolEuT).toBe(32);
    expect(low.parallels).toBe(1);
    const off = getNodePowerReport(recipe, { ...node, hatchAmps: 0 });
    expect(off.state).toBe("under-powered");
  });

  it("caps overclocks independently at laser source tier + 1", () => {
    const highPower = { ...node, hatchVoltageTier: "UXV" as const, hatchAmps: 64 };
    const withSource = (laserSource: string) =>
      getOverclockedRecipeStats(recipe, {
        ...highPower,
        machineConfigTiers: { laserSource },
      });
    expect(withSource("iv-256").overclockSteps).toBe(5); // LuV ceiling - LV recipe
    expect(withSource("luv-256").overclockSteps).toBe(6);
    expect(withSource("uv-256").overclockSteps).toBe(8);
  });

  it("preserves the named legacy hatch instead of the conflicting dummy count", () => {
    const legacy = { ...node, machineConfigTiers: { laserSource: "a65536", laserAmperage: "64" } };
    expect(getRecipeMachineConfigTierControls(recipe, legacy)[0].current.key).toBe("uhv-65536");
    expect(getMachineStructuralParallels(recipe, legacy)).toBe(40);
    expect(getOverclockedRecipeStats(recipe, legacy)).toEqual(
      getOverclockedRecipeStats(recipe, node),
    );
    expect(
      getRecipeMachineConfigTierControls(recipe, {
        machineConfigTiers: { laserAmperage: "4096" },
      })[0].current.key,
    ).toBe("zpm-4096");
    expect(
      getRecipeMachineConfigTierControls(recipe, { machineConfigTiers: {} })[0].current.key,
    ).toBe("iv-256");
  });

  it("follows the selected handler and discards baked HILE bonuses", () => {
    const family: Recipe = {
      ...recipe,
      machineType: "Laser Engraver",
      machineConfigControls: [],
      machineHandlers: [
        {
          id: "single",
          label: "Precision Laser Engraver",
          machineType: "Laser Engraver",
          minimumTier: "LV",
          kind: "single",
        },
        {
          id: "hile",
          label: recipe.machineType,
          machineType: recipe.machineType,
          minimumTier: "LV",
          kind: "multiblock",
          durationTicks: 100,
          eut: 24,
          machineConfigControls: recipe.machineConfigControls,
        },
      ],
    };
    const selected = { ...node, machineHandlerId: "hile" };
    const effective = applyMachineHandlerToRecipe(family, selected);
    expect(getRecipeMachineConfigTierControls(effective, selected)).toHaveLength(1);
    expect(getOverclockedRecipeStats(family, selected)).toEqual(
      getOverclockedRecipeStats(recipe, node),
    );
    expect(
      getRecipeMachineConfigTierControls(
        applyMachineHandlerToRecipe(family, { machineHandlerId: "single" }),
        node,
      ),
    ).toHaveLength(0);
  });

  it("stalls an over-tier recipe despite ample power and resumes with a higher source", () => {
    const highRecipe = { ...recipe, eut: 122880, minimumTier: "ZPM" as const };
    const lowSource = {
      ...node,
      hatchVoltageTier: "UXV" as const,
      machineConfigTiers: { laserSource: "iv-256" },
    };
    const report = getNodePowerReport(highRecipe, lowSource);
    expect(report.state).toBe("over-tier");
    expect(describePowerStall(report)).toContain("Laser source tier too low");
    const project: FactoryProject = {
      schemaVersion: PROJECT_SCHEMA_VERSION,
      id: "hile-gate",
      name: "HILE gate",
      recipes: [highRecipe],
      nodes: [lowSource],
      fuelProfiles: [],
      storages: [
        { id: "in", kind: "item", resourceId: "wafer", position: { x: -160, y: 0 } },
        { id: "out", kind: "item", resourceId: "chip", position: { x: 160, y: 0 } },
      ],
      edges: [
        { id: "feed", source: "in", target: node.id, resourceKind: "item", resourceId: "wafer" },
        { id: "ship", source: node.id, target: "out", resourceKind: "item", resourceId: "chip" },
      ],
    };
    const stalled = calculateThroughput(project);
    expect(stalled.nodes.hile.powerStalled).toBe(true);
    expect(stalled.edges.ship.transferredPerSecond).toBe(0);
    const working = calculateThroughput({
      ...project,
      nodes: [{ ...lowSource, machineConfigTiers: { laserSource: "luv-256" } }],
    });
    expect(working.nodes.hile.powerStalled).toBe(false);
    expect(working.edges.ship.transferredPerSecond).toBeGreaterThan(0);
  });
});

// gt.recipe.laserengraver:deb1c9e6d0b3f15f as this instance's dataset has it:
// 2 ticks at 491,520 EU/t (UV), with the HILE handler carrying the exporter's
// own amps-only "laserSource" knob and its baked x3.5 / x0.8 stats.
const proteus: Recipe = {
  id: "oracle:stable-2.9.0-tuxmine:gregtech:gt-recipe-laserengraver:deb1c9e6d0b3f15f",
  name: "Laser Engraver: Proteus Dungeon Bricks",
  machineType: "Laser Engraver",
  minimumTier: "UV",
  durationTicks: 2,
  eut: 491520,
  inputs: [{ kind: "item", id: "galaxyspace:proteusblocks@2", amount: 4 }],
  outputs: [{ kind: "item", id: "galaxyspace:proteusblocks@3", amount: 1 }],
  machineHandlers: [
    {
      id: "precision-laser-engraver",
      label: "Precision Laser Engraver",
      kind: "single",
      machineType: "Precision Laser Engraver",
      minimumTier: "UV",
    },
    {
      id: "hyper-intensity-laser-engraver",
      label: "Hyper-Intensity Laser Engraver",
      kind: "multiblock",
      machineType: "Hyper-Intensity Laser Engraver",
      minimumTier: "UV",
      durationTicks: 1,
      eut: 393216,
      machineConfigControls: [
        {
          id: "laserSource",
          label: "Laser Source",
          minimumKey: "a256",
          defaultKey: "a256",
          tiers: [256, 1024, 4096, 16384, 65536, 262144, 1048576, 4194304, 16777216].map(
            (amps) => ({
              key: `a${amps}`,
              label: `${amps.toLocaleString("en-US")}A Laser`,
              parallelMultiplier: Math.floor(Math.cbrt(amps)),
              resource: { kind: "item" as const, id: `source-${amps}`, amount: 1 },
            }),
          ),
        },
      ],
    },
  ],
};

function hileNode(laserSource: string | Record<string, string>, amps: number): FactoryNode {
  return {
    ...node,
    recipeId: proteus.id,
    machineHandlerId: "hyper-intensity-laser-engraver",
    overclockTier: "UHV",
    hatchVoltageTier: "UHV",
    hatchAmps: amps,
    machineConfigTiers: typeof laserSource === "string" ? { laserSource } : laserSource,
  };
}

describe("HILE on a real dataset recipe (MTEIndustrialLaserEngraver)", () => {
  it("offers one laser source setting, the hatch a player builds, over the exporter's amps-only knob", () => {
    const effective = applyMachineHandlerToRecipe(proteus, hileNode("uhv-65536", 4));
    const controls = getRecipeMachineConfigTierControls(effective, hileNode("uhv-65536", 4));
    expect(controls.map((control) => control.id)).toEqual(["laserSource"]);
    expect(controls[0].tiers).toHaveLength(46);
    expect(controls[0].current.label).toBe("UHV · 65,536A");
  });

  it("runs the reporter's UHV 65,536 A source at 40 parallels when the energy hatches pay for them", () => {
    // 16 UHV hatches (32 A): (int) cbrt(65536) = 40 parallels at 393,216 EU/t,
    // one overclock (capped at source tier + 1 - UV = 2), and the 2 / 3.5 tick
    // run banks to 4 a tick: 160 a tick, under the supply's cap of 170.
    const configured = hileNode("uhv-65536", 32);
    expect(
      getMachineStructuralParallels(applyMachineHandlerToRecipe(proteus, configured), configured),
    ).toBe(40);
    const report = getNodePowerReport(proteus, configured);
    const stats = getOverclockedRecipeStats(proteus, configured);
    expect(report.parallels).toBe(40);
    expect(stats.overclockSteps).toBe(1);
    expect(report.parallels / stats.durationTicks).toBe(160);
  });

  it("holds the same source to what two UHV hatches pay for", () => {
    // 4 A UHV: floor(8,388,608 / 393,216) = 21 parallels, one run a tick.
    const configured = hileNode("uhv-65536", 4);
    const report = getNodePowerReport(proteus, configured);
    expect(report.parallels).toBe(21);
    expect(getOverclockedRecipeStats(proteus, configured).durationTicks).toBe(1);
  });

  it.each([
    ["uhv-65536", "uhv-65536"],
    // The exporter's amps-only key: the lowest tier that registers that amperage.
    ["a65536", "uhv-65536"],
    // The old separate amperage knob: rounded up to a real hatch.
    [{ laserAmperage: "64" }, "iv-256"],
    [{ laserAmperage: "65536" }, "uhv-65536"],
  ])("loads a plan saved with %j as source %s", (saved, expected) => {
    const configured = hileNode(saved, 32);
    const effective = applyMachineHandlerToRecipe(proteus, configured);
    const source = getRecipeMachineConfigTierControls(effective, configured).find(
      (control) => control.id === "laserSource",
    );
    expect(source?.current.key).toBe(expected);
  });
});
