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

// Combine's result is a fact about the cards, never about which one hosts:
// the same cards in any plan order and any selection order give the same
// machine, tiers, amps, settings and solved power.
describe("Combine is the same whichever card hosts", () => {
  const permutations = <T,>(items: T[]): T[][] =>
    items.length <= 1
      ? [items]
      : items.flatMap((item, index) =>
          permutations([...items.slice(0, index), ...items.slice(index + 1)]).map((rest) => [
            item,
            ...rest,
          ]),
        );
  const MACHINE_FIELDS = [
    "machineHandlerId",
    "overclockTier",
    "hatchVoltageTier",
    "hatchAmps",
    "powerEuT",
    "powerInputMode",
    "energyHatchType",
    "coilTier",
    "machineConfigTiers",
  ] as const;
  const outcome = (recipes: Recipe[], nodes: FactoryNode[], ids: string[]) => {
    useFactoryStore.getState().setProject(plan(recipes, nodes));
    const { card: merged } = combine(ids);
    return {
      machine: Object.fromEntries(MACHINE_FIELDS.map((field) => [field, merged[field]])),
      sections: [merged.recipeId, ...merged.extraRecipes!.map((extra) => extra.recipeId)].sort(),
      totalEuT: useFactoryStore.getState().lastResult.totalEuT,
    };
  };
  const everyOrder = (recipes: Recipe[], nodes: FactoryNode[]) => {
    const results = permutations(nodes).flatMap((order) =>
      permutations(nodes.map((node) => node.id)).map((ids) => outcome(recipes, order, ids)),
    );
    for (const result of results) expect(result).toEqual(results[0]);
    return results[0]!;
  };
  const OTHER: MachineHandler = { ...MCR, id: "other-reactor", label: "Other Reactor" };
  const casing = {
    id: "casing",
    label: "Casing",
    minimumKey: "t1",
    tiers: ["t1", "t2", "t3"].map((key) => ({
      key,
      label: key,
      parallelMultiplier: Number(key.slice(1)),
      resource: { kind: "item" as const, id: `casing-${key}`, amount: 1, displayName: key },
    })),
  };
  const chem = (id: string, extra: Partial<Recipe> = {}) =>
    recipe(id, "Large Chemical Reactor", {
      machineHandlers: [LCR, MCR, OTHER],
      machineConfigControls: [casing],
      ...extra,
    });

  it("takes the highest tier and the largest supply any card had", () => {
    const merged = everyOrder(
      [chem("a"), chem("b"), chem("c")],
      [
        card("n-a", "a"),
        card("n-b", "b", { hatchVoltageTier: "LuV", powerEuT: 32768 }),
        card("n-c", "c", { hatchVoltageTier: "IV", hatchAmps: 8, powerEuT: 8 * 8192 }),
      ],
    );
    expect(merged.machine).toMatchObject({
      hatchVoltageTier: "LuV",
      overclockTier: "LuV",
      hatchAmps: 2,
      powerEuT: 2 * 32768,
    });
  });

  it("keeps a tier every card agrees on", () => {
    const merged = everyOrder(
      [chem("a"), chem("b")],
      [
        card("n-a", "a", { overclockTier: "HV", hatchVoltageTier: "IV" }),
        card("n-b", "b", { overclockTier: "LV", hatchVoltageTier: "IV" }),
      ],
    );
    expect(merged.machine).toMatchObject({ hatchVoltageTier: "IV", hatchAmps: 1, overclockTier: "IV" });
  });

  it("raises the tier for a recipe that needs more, in every order", () => {
    const merged = everyOrder(
      [chem("a"), chem("b"), chem("iv", {
          minimumTier: "IV",
          eut: 480,
          machineHandlers: [{ ...LCR, minimumTier: "IV" }, MCR, OTHER],
        })],
      [card("n-a", "a", { hatchVoltageTier: "HV", powerEuT: 512 }), card("n-b", "b"), card("n-iv", "iv")],
    );
    expect(merged.machine).toMatchObject({ hatchVoltageTier: "IV", hatchAmps: 1 });
    expect(merged.totalEuT).toBeGreaterThan(0);
  });

  it("runs the machine most cards name", () => {
    const merged = everyOrder(
      [chem("a"), chem("b"), chem("c")],
      [
        card("n-a", "a", { machineHandlerId: OTHER.id }),
        card("n-b", "b", { machineHandlerId: MCR.id }),
        card("n-c", "c", { machineHandlerId: MCR.id }),
      ],
    );
    expect(merged.machine.machineHandlerId).toBe(MCR.id);
  });

  it("takes the highest machine setting any card had", () => {
    const merged = everyOrder(
      [chem("a"), chem("b"), chem("c")],
      [
        card("n-a", "a", { machineConfigTiers: { casing: "t2" } }),
        card("n-b", "b", { machineConfigTiers: { casing: "t3" } }),
        card("n-c", "c"),
      ],
    );
    expect(merged.machine.machineConfigTiers).toEqual({ casing: "t3" });
  });

  it("ranks stored settings that are numbers by value and tier names by tier", () => {
    const merged = everyOrder(
      [chem("a"), chem("b"), chem("c")],
      [
        card("n-a", "a", { machineConfigTiers: { rings: "9", hatch: "MV", top: "ZPM" } }),
        card("n-b", "b", { machineConfigTiers: { rings: "10", hatch: "HV", top: "UV" } }),
        card("n-c", "c", { machineConfigTiers: { rings: "2", hatch: "LV" } }),
      ],
    );
    expect(merged.machine.machineConfigTiers).toEqual({ rings: "10", hatch: "HV", top: "UV" });
  });

  it("runs a single block at the highest tier any card ran at", () => {
    const single = (id: string, minimumTier: Recipe["minimumTier"] = "LV") =>
      recipe(id, "Chemical Reactor", { minimumTier, eut: minimumTier === "MV" ? 120 : 30 });
    const bare = { hatchVoltageTier: undefined, hatchAmps: undefined, powerEuT: undefined };
    const merged = everyOrder(
      [single("lv"), single("hv"), single("mv", "MV")],
      [
        card("n-lv", "lv", { ...bare, overclockTier: "LV" }),
        card("n-hv", "hv", { ...bare, overclockTier: "HV" }),
        card("n-mv", "mv", { ...bare, overclockTier: "MV" }),
      ],
    );
    expect(merged.machine.overclockTier).toBe("HV");
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
