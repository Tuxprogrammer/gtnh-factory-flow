import { beforeEach, describe, expect, it } from "vitest";
import {
  PROJECT_SCHEMA_VERSION,
  type FactoryNode,
  type FactoryProject,
  type MachineHandler,
  type Recipe,
} from "@/lib/model/types";
import { useFactoryStore } from "./factory-store";

// "Combine N into one machine" must leave a card a player could have built:
// the machine one of the cards chose, a tier every recipe starts at, whole
// hatch amps, and only a machine the dataset names.

const LCR: MachineHandler = {
  id: "large-chemical-reactor",
  label: "Large Chemical Reactor",
  machineType: "Large Chemical Reactor",
  minimumTier: "LV",
  kind: "multiblock",
};
const MCR: MachineHandler = {
  id: "mega-chemical-reactor",
  label: "Mega Chemical Reactor",
  machineType: "Mega Chemical Reactor",
  minimumTier: "LV",
  kind: "multiblock",
};

function recipe(id: string, map: string, extra: Partial<Recipe> = {}): Recipe {
  return {
    id,
    name: id,
    machineType: map,
    minimumTier: "LV",
    durationTicks: 100,
    eut: 30,
    inputs: [{ kind: "item", id: `${id}-in`, amount: 1, displayName: `${id} in` }],
    outputs: [{ kind: "item", id: `${id}-out`, amount: 1, displayName: `${id} out` }],
    ...extra,
  };
}

function card(id: string, recipeId: string, extra: Partial<FactoryNode> = {}): FactoryNode {
  return {
    id,
    recipeId,
    machineCount: 1,
    parallel: 1,
    overclockTier: "EV",
    hatchVoltageTier: "EV",
    hatchAmps: 1,
    powerEuT: 2048,
    enabled: true,
    position: { x: 0, y: 0 },
    ...extra,
  };
}

function plan(
  recipes: Recipe[],
  nodes: FactoryNode[],
  edges: FactoryProject["edges"] = [],
): FactoryProject {
  return {
    schemaVersion: PROJECT_SCHEMA_VERSION,
    id: "combine",
    name: "Combine",
    fuelProfiles: [],
    recipes,
    nodes,
    edges,
    storages: [],
    metadata: { createdAt: "2026-11-22T00:00:00.000Z", updatedAt: "2026-11-22T00:00:00.000Z" },
  };
}

const combine = (ids: string[]) => {
  const host = useFactoryStore.getState().combineNodesIntoMachine(ids);
  const nodes = useFactoryStore.getState().project.nodes;
  return { host, nodes, card: nodes.find((node) => node.id === host)! };
};

describe("Combine into one machine", () => {
  beforeEach(() => {
    useFactoryStore.getState().setProject(plan([], []));
  });

  it("keeps the machine a card chose, whatever the plan order", () => {
    const chem = (id: string) =>
      recipe(id, "Large Chemical Reactor", { machineHandlers: [LCR, MCR] });
    useFactoryStore
      .getState()
      .setProject(
        plan(
          [chem("a"), chem("b"), chem("c")],
          [
            card("n-a", "a"),
            card("n-b", "b", { machineHandlerId: MCR.id, hatchVoltageTier: "IV", powerEuT: 8192 }),
            card("n-c", "c"),
          ],
          [{ id: "w", source: "n-a", target: "n-c", resourceKind: "item", resourceId: "a-out" }],
        ),
      );
    const { host, nodes, card: merged } = combine(["n-a", "n-b", "n-c"]);
    expect(host).toBe("n-b");
    expect(nodes.map((node) => node.id)).toEqual(["n-b"]);
    expect(merged.machineHandlerId).toBe(MCR.id);
    expect(merged.hatchVoltageTier).toBe("IV");
    expect([merged.recipeId, ...merged.extraRecipes!.map((extra) => extra.recipeId)]).toEqual([
      "b",
      "a",
      "c",
    ]);
  });

  it("raises the hatches to a tier every joined recipe starts at", () => {
    const tower: MachineHandler = {
      id: "distillation-tower",
      label: "Distillation Tower",
      machineType: "Distillation Tower",
      minimumTier: "LV",
      kind: "multiblock",
    };
    const towerIv: MachineHandler = { ...tower, minimumTier: "IV" };
    useFactoryStore
      .getState()
      .setProject(
        plan(
          [
            recipe("salt", "Distillation Tower", { machineHandlers: [tower] }),
            recipe("osmium", "Distillation Tower", {
              minimumTier: "IV",
              eut: 480,
              machineHandlers: [towerIv],
            }),
          ],
          [card("n-salt", "salt"), card("n-os", "osmium")],
        ),
      );
    const { card: merged } = combine(["n-salt", "n-os"]);
    expect(merged.hatchVoltageTier).toBe("IV");
    expect(merged.hatchAmps).toBe(1);
    expect(merged.powerEuT).toBe(8192);
  });

  it("adds whole amps where one parallel of a joined recipe needs more", () => {
    const tower: MachineHandler = {
      id: "distillation-tower",
      label: "Distillation Tower",
      machineType: "Distillation Tower",
      minimumTier: "LV",
      kind: "multiblock",
    };
    useFactoryStore
      .getState()
      .setProject(
        plan(
          [
            recipe("small", "Distillation Tower", { machineHandlers: [tower] }),
            recipe("big", "Distillation Tower", { eut: 5000, machineHandlers: [tower] }),
          ],
          [card("n-small", "small"), card("n-big", "big")],
        ),
      );
    const { card: merged } = combine(["n-small", "n-big"]);
    expect(merged.hatchVoltageTier).toBe("EV");
    expect(merged.hatchAmps).toBe(3);
    expect(merged.powerEuT).toBe(3 * 2048);
  });

  it("rounds a fractional hatch setting up to whole amps", () => {
    const solidifier: MachineHandler = {
      id: "mass-solidifier",
      label: "Mass Solidifier",
      machineType: "Mass Solidifier",
      minimumTier: "LV",
      kind: "multiblock",
    };
    useFactoryStore
      .getState()
      .setProject(
        plan(
          [
            recipe("na", "Fluid Solidifier", { machineHandlers: [solidifier] }),
            recipe("k", "Fluid Solidifier", { machineHandlers: [solidifier] }),
          ],
          [
            card("n-na", "na", {
              machineHandlerId: solidifier.id,
              hatchAmps: 2.009765625,
              powerEuT: 4116,
            }),
            card("n-k", "k", { machineHandlerId: solidifier.id, hatchAmps: 2, powerEuT: 4096 }),
          ],
        ),
      );
    const { card: merged } = combine(["n-na", "n-k"]);
    expect(merged.hatchAmps).toBe(3);
    expect(merged.powerEuT).toBe(3 * 2048);
  });

  it("stores no machine for a map that exports none", () => {
    useFactoryStore
      .getState()
      .setProject(
        plan(
          [recipe("s", "Multiblock Electrolyzer"), recipe("ca", "Multiblock Electrolyzer")],
          [card("n-s", "s"), card("n-ca", "ca")],
        ),
      );
    const { card: merged } = combine(["n-s", "n-ca"]);
    expect(merged.extraRecipes).toHaveLength(1);
    expect(merged).not.toHaveProperty("machineHandlerId");
  });

  it("adds a recipe to such a map's card without naming a machine", () => {
    const calcium = recipe("ca", "Multiblock Electrolyzer");
    useFactoryStore
      .getState()
      .setProject(plan([recipe("s", "Multiblock Electrolyzer"), calcium], [card("n-s", "s")]));
    expect(useFactoryStore.getState().addRecipeToNode("n-s", calcium)).toBe(true);
    const merged = useFactoryStore.getState().project.nodes[0]!;
    expect(merged.extraRecipes).toHaveLength(1);
    expect(merged).not.toHaveProperty("machineHandlerId");
  });
});

// "Add another recipe" on a card fits the card the same way: a recipe the
// card's tier cannot start raises the hatches, one parallel of it gets whole
// amps, and a recipe no machine on the card runs is refused.
describe("Add another recipe", () => {
  const tower: MachineHandler = {
    id: "distillation-tower",
    label: "Distillation Tower",
    machineType: "Distillation Tower",
    minimumTier: "LV",
    kind: "multiblock",
  };
  const added = () => useFactoryStore.getState().project.nodes[0]!;

  it("raises the hatches to a tier the added recipe starts at", () => {
    const osmium = recipe("osmium", "Distillation Tower", {
      minimumTier: "IV",
      eut: 480,
      machineHandlers: [{ ...tower, minimumTier: "IV" }],
    });
    useFactoryStore
      .getState()
      .setProject(
        plan(
          [recipe("salt", "Distillation Tower", { machineHandlers: [tower] }), osmium],
          [card("n-salt", "salt")],
        ),
      );
    expect(useFactoryStore.getState().addRecipeToNode("n-salt", osmium)).toBe(true);
    expect(added().hatchVoltageTier).toBe("IV");
    expect(added().hatchAmps).toBe(1);
    expect(added().powerEuT).toBe(8192);
  });

  it("adds whole amps where one parallel of the added recipe needs more", () => {
    const big = recipe("big", "Distillation Tower", { eut: 5000, machineHandlers: [tower] });
    useFactoryStore
      .getState()
      .setProject(
        plan(
          [recipe("small", "Distillation Tower", { machineHandlers: [tower] }), big],
          [card("n-small", "small")],
        ),
      );
    expect(useFactoryStore.getState().addRecipeToNode("n-small", big)).toBe(true);
    expect(added().hatchVoltageTier).toBe("EV");
    expect(added().hatchAmps).toBe(3);
    expect(added().powerEuT).toBe(3 * 2048);
  });

  it("raises a single block's tier to the added recipe's minimum", () => {
    const hv = recipe("hv", "Chemical Reactor", { minimumTier: "HV", eut: 480 });
    useFactoryStore
      .getState()
      .setProject(
        plan(
          [recipe("lv", "Chemical Reactor"), hv],
          [
            card("n-lv", "lv", {
              overclockTier: "LV",
              hatchVoltageTier: undefined,
              hatchAmps: undefined,
              powerEuT: undefined,
            }),
          ],
        ),
      );
    expect(useFactoryStore.getState().addRecipeToNode("n-lv", hv)).toBe(true);
    expect(added().overclockTier).toBe("HV");
  });

  it("refuses a recipe no machine on the card runs", () => {
    const other = recipe("other", "Centrifuge");
    useFactoryStore
      .getState()
      .setProject(
        plan([recipe("salt", "Distillation Tower", { machineHandlers: [tower] })], [
          card("n-salt", "salt"),
        ]),
      );
    expect(useFactoryStore.getState().addRecipeToNode("n-salt", other)).toBe(false);
    expect(added().extraRecipes).toBeUndefined();
    expect(added().hatchVoltageTier).toBe("EV");
  });
});
