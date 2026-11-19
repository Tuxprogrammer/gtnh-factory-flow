import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { gunzipSync } from "node:zlib";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { PROJECT_SCHEMA_VERSION, type FactoryNode, type FactoryProject, type Recipe } from "@/lib/model/types";
import { getGameRates, setActiveGameRates, type ActiveGameRates } from "@/lib/model/game-rates";
import { applyMachineHandlerToRecipe, getSelectedMachineHandler, isSteamMachineHandler } from "@/lib/model/recipe-rules";
import { applyRecipeInputOverrides } from "@/lib/model/recipe-input-overrides";
import { stripBeeFrameSlotInputs } from "@/lib/model/bee-display";
import { buildMachineList } from "@/lib/model/machine-list";
import { setActivePowerDisplayUnit, setActiveRateUnit } from "@/lib/format/rate-unit";
import { getOverclockedRecipeStats } from "@/lib/solver/overclock";
import {
  applyMachineOutputMultipliers,
  getMachineOutputMultiplier,
  getMachineParallelMultiplier,
  getMachineStructuralParallels,
} from "@/lib/solver/machine-effects";
import { getNodePowerReport, getNodeSteamReport, hasPowerReport } from "@/lib/solver/power-report";
import { calculateThroughput } from "@/lib/solver/throughput";
import { closeBoundaries } from "@/lib/solver/close-boundaries";
import { deriveCardFace } from "@/components/flow/clean/clean-model";
import { cardTipFacts } from "@/components/flow/clean/menus/card-tips";
import {
  applyCardOutputMultipliers,
  cardMachineMath,
  cardMachineMathCounts,
  clearCardMachineMath,
  resetCardMachineMathCounts,
} from "./machines";
import { withAdopt } from "./adopt";
import { loadCore, resetCoreForTests, wasmGcSupported } from "./loader";

// The card's one readout (machines.ts `cardMachineMath`): the same figures every consumer computed for itself before,
// computed once per (recipe object, node content, game rates), and never stale when any of those changes.

interface Input {
  recipe: Recipe;
  node: FactoryNode;
  gameRates?: Partial<ActiveGameRates>;
}

function testInputs(): Input[] {
  const lines = gunzipSync(
    readFileSync(path.join(process.cwd(), "conformance", "machines", "website-test-inputs.jsonl.gz")),
  )
    .toString("utf8")
    .split("\n")
    .filter(Boolean)
    .map((line) => JSON.parse(line) as Input);
  // A recorded node is what the maths read; a card always has an id.
  return lines.map((line, i) => ({ ...line, node: { ...line.node, id: line.node.id ?? `n${i}` } as FactoryNode }));
}

const INPUTS = testInputs();

function rates(input: Input): ActiveGameRates {
  return { beesYield: 1, ic2CropsYield: 1, recipeDurationDivisor: 1, ...input.gameRates };
}

/** What each consumer computed for itself before the readout, from the same calls. */
function figuresAsBefore(recipe: Recipe, node: FactoryNode) {
  const nodeRecipe = applyRecipeInputOverrides(recipe, node);
  const effective = applyMachineHandlerToRecipe(nodeRecipe, node);
  const stats = getOverclockedRecipeStats(nodeRecipe, node);
  return {
    handler: getSelectedMachineHandler(recipe, node),
    nodeRecipe,
    effective,
    stats,
    power: hasPowerReport(nodeRecipe, node) ? getNodePowerReport(nodeRecipe, node) : undefined,
    steam: getNodeSteamReport(nodeRecipe, node),
    parallel: getMachineParallelMultiplier(effective, node),
    structural: getMachineStructuralParallels(effective, node),
    outputs: effective.outputs.map((output) => getMachineOutputMultiplier(effective, node, output, stats.tier)),
    display: applyMachineOutputMultipliers(stripBeeFrameSlotInputs(effective), node, stats.tier),
  };
}

/** card-tips' computeFacts as it was, call by call. */
function tipsAsBefore(recipe: Recipe, node: FactoryNode) {
  const f = figuresAsBefore(recipe, node);
  const steam = isSteamMachineHandler(f.handler) || !(f.effective.eut > 0);
  const power = !steam ? f.power : undefined;
  const steamReport = steam ? f.steam : undefined;
  return {
    steam: steamReport !== undefined,
    seconds: f.stats.durationTicks / 20,
    drawPerMachine: steamReport ? steamReport.drawSteamPerTick * 20 : (power?.drawEuT ?? Math.abs(f.stats.eut)),
    parallels: power?.parallels ?? steamReport?.parallels ?? f.parallel,
    recipeTier: f.stats.minimumTier,
    shared: (node.extraRecipes?.length ?? 0) > 0,
    recipes: 1 + (node.extraRecipes?.length ?? 0),
  };
}

function figuresOf(recipe: Recipe, node: FactoryNode) {
  const m = cardMachineMath(recipe, node);
  return {
    handler: m.handler,
    nodeRecipe: m.nodeRecipe,
    effective: m.effectiveRecipe,
    stats: m.stats,
    power: m.powerReport,
    steam: m.steamReport,
    parallel: m.parallel,
    structural: m.structuralParallel,
    outputs: m.outputMultipliers,
    display: applyCardOutputMultipliers(stripBeeFrameSlotInputs(m.effectiveRecipe), m),
  };
}

const ratesBefore = getGameRates();
afterEach(() => {
  setActiveGameRates(ratesBefore);
  clearCardMachineMath();
});

describe("a card's machine readout", () => {
  it("gives every consumer the figures it computed for itself before, on every recorded test input", () => {
    let compared = 0;
    for (const input of INPUTS) {
      setActiveGameRates(rates(input));
      let before: ReturnType<typeof figuresAsBefore>;
      try {
        before = figuresAsBefore(input.recipe, input.node);
      } catch {
        // An input the maths cannot read: the readout throws on the same figure (checked below by the tips' fallback).
        continue;
      }
      expect(figuresOf(input.recipe, input.node)).toEqual(before);
      expect(cardTipFacts(input.recipe, input.node)).toEqual(tipsAsBefore(input.recipe, input.node));
      const face = deriveCardFace(input.recipe, input.node);
      expect(face.selectedMachineHandler).toEqual(before.handler);
      expect(face.nodeRecipe).toEqual(before.nodeRecipe);
      expect(face.effectiveRecipe).toEqual(before.effective);
      expect(face.powerReport).toEqual(face.machineDrawsEu && face.tierControl ? before.power : undefined);
      compared++;
    }
    expect(compared).toBeGreaterThan(7000);
  }, 120_000);

  it("is one computation per card: equal content, a moved card and a new solve pin read the same readout", () => {
    const input = INPUTS.find((i) => hasPowerReport(i.recipe, i.node))!;
    setActiveGameRates(rates(input));
    const a = cardMachineMath(input.recipe, input.node);
    const moved = { ...input.node, position: { x: 400, y: 80 }, solvePin: 3 } as FactoryNode;
    expect(cardMachineMath(input.recipe, moved)).toBe(a);
    expect(cardMachineMath(input.recipe, structuredClone(input.node))).toBe(a);
    resetCardMachineMathCounts();
    deriveCardFace(input.recipe, input.node);
    deriveCardFace(input.recipe, moved);
    cardTipFacts(input.recipe, moved);
    void a.powerReport;
    expect(cardMachineMathCounts()).toMatchObject({ readouts: 0, stats: 1, power: 1, kept: 1 });
  });

  it("is never stale: a different recipe object reads its own figures", () => {
    const input = INPUTS.find((i) => i.recipe.durationTicks > 0 && !i.recipe.power)!;
    setActiveGameRates(rates(input));
    const first = cardMachineMath(input.recipe, input.node).stats.durationTicks;
    const slower: Recipe = { ...input.recipe, durationTicks: input.recipe.durationTicks * 4 };
    const second = cardMachineMath(slower, input.node).stats.durationTicks;
    expect(second).not.toBe(first);
    expect(second).toBe(getOverclockedRecipeStats(applyRecipeInputOverrides(slower, input.node), input.node).durationTicks);
  });

  it("is never stale: a game-rates change drops every kept figure", () => {
    // A recipe the pack's duration divisor speeds up (it is not every recipe).
    const ticksAt = (i: Input, divisor: number) => {
      setActiveGameRates({ recipeDurationDivisor: divisor });
      return getOverclockedRecipeStats(i.recipe, i.node).durationTicks;
    };
    const input = INPUTS.find((i) => ticksAt(i, 4) < ticksAt(i, 1))!;
    setActiveGameRates(undefined);
    const plain = cardMachineMath(input.recipe, input.node);
    const plainTicks = plain.stats.durationTicks;
    setActiveGameRates({ recipeDurationDivisor: 4 });
    const fast = cardMachineMath(input.recipe, input.node);
    expect(fast).not.toBe(plain);
    expect(fast.stats.durationTicks).toBeLessThan(plainTicks);
    // The same values in a new record still clear: the store sets one on every dataset set and clear.
    setActiveGameRates({ recipeDurationDivisor: 4 });
    expect(cardMachineMath(input.recipe, input.node)).not.toBe(fast);
  });

  it("is never stale: a wired substitute reads the recipe as the node takes it", () => {
    const input = INPUTS.find((i) => i.recipe.inputs.length > 0 && !i.node.recipeInputOverrides)!;
    setActiveGameRates(rates(input));
    const first = cardMachineMath(input.recipe, input.node);
    expect(first.nodeRecipe).toBe(input.recipe);
    const slot = input.recipe.inputs[0]!;
    const wired = {
      ...input.node,
      recipeInputOverrides: { "0": { ...slot, id: `${slot.id}:substitute`, displayName: "Substitute" } },
    } as FactoryNode;
    const second = cardMachineMath(input.recipe, wired);
    expect(second).not.toBe(first);
    expect(second.nodeRecipe.inputs[0]!.id).toBe(`${slot.id}:substitute`);
    expect(second.nodeRecipe).toEqual(applyRecipeInputOverrides(input.recipe, wired));
  });

  // Every node field the recorded inputs vary: for each, a card whose figures that field changes, read through the
  // cache before and after the change, must read the fresh figures after it.
  const fields = [...new Set(INPUTS.flatMap((i) => Object.keys(i.node)))].filter((f) => f !== "id");
  const mustFind = [
    "overclockTier",
    "machineHandlerId",
    "coilTier",
    "machineConfigTiers",
    "energyHatches",
    "energyHatchType",
    "hatchAmps",
  ];
  it("varies every field the cards' figures read", () => {
    for (const field of mustFind) expect(fields).toContain(field);
  });
  it.each(fields)("is never stale: a change of the node's %s reads fresh figures", (field) => {
    const byRecipe = new Map<string, Input[]>();
    for (const input of INPUTS) {
      const list = byRecipe.get(input.recipe.id) ?? [];
      list.push(input);
      byRecipe.set(input.recipe.id, list);
    }
    const fingerprint = (recipe: Recipe, node: FactoryNode) => {
      try {
        return JSON.stringify(figuresAsBefore(recipe, node));
      } catch {
        return undefined;
      }
    };
    const valueOf = (input: Input) => JSON.stringify(input.node[field as keyof FactoryNode]);
    for (const list of byRecipe.values()) {
      // The values the field takes for this recipe (bounded: the pairs are all of each other's values).
      const values = [...new Map(list.map((input) => [valueOf(input), input])).values()].slice(0, 12);
      if (values.length < 2) continue;
      for (const a of list.slice(0, 4)) {
        setActiveGameRates(rates(a));
        const was = fingerprint(a.recipe, a.node);
        if (was === undefined) continue;
        for (const b of values) {
          if (valueOf(a) === valueOf(b)) continue;
          const changed = { ...a.node, [field]: b.node[field as keyof FactoryNode] } as FactoryNode;
          const want = fingerprint(a.recipe, changed);
          if (want === undefined || want === was) continue;
          clearCardMachineMath();
          expect(JSON.stringify(figuresOf(a.recipe, a.node))).toBe(was);
          expect(JSON.stringify(figuresOf(a.recipe, changed))).toBe(want);
          // And back: the card's previous content is still kept, and still right.
          expect(JSON.stringify(figuresOf(a.recipe, a.node))).toBe(was);
          return;
        }
      }
    }
    // No recorded pair moves a figure through this field: it is still in the key (the whole node is), nothing to show.
    expect(mustFind).not.toContain(field);
  });
});

describe("exact invalidation on a board", () => {
  const bender: Recipe = {
    id: "light",
    name: "Copper Plate",
    machineType: "Bender",
    minimumTier: "LV",
    durationTicks: 20,
    eut: 8,
    inputs: [{ kind: "item", id: "copper", amount: 1 }],
    outputs: [{ kind: "item", id: "plate", displayName: "Copper Plate", amount: 1 }],
  };
  function project(): FactoryProject {
    return closeBoundaries({
      schemaVersion: PROJECT_SCHEMA_VERSION,
      id: "cache",
      name: "Cache",
      fuelProfiles: [],
      recipes: [bender],
      edges: [],
      nodes: Array.from({ length: 20 }, (_, index) => ({
        id: `n${index}`,
        recipeId: "light",
        machineCount: 1,
        parallel: 1,
        enabled: true,
        overclockTier: "LV",
        position: { x: index * 400, y: 0 },
      })),
    });
  }

  it("recomputes the edited card only, and nothing for a display dial or an Arrange", () => {
    const p = project();
    const result = calculateThroughput(p);
    buildMachineList(p, result);
    resetCardMachineMathCounts();

    const edited = { ...p, nodes: p.nodes.map((n) => (n.id === "n3" ? { ...n, overclockTier: "MV" } : n)) };
    const lines = buildMachineList(edited, calculateThroughput(edited));
    expect(cardMachineMathCounts()).toMatchObject({ readouts: 1, power: 1, steam: 1 });
    expect(lines.find((l) => l.nodeId === "n3")?.tier).toBe("MV");
    expect(lines.find((l) => l.nodeId === "n2")?.tier).toBe("LV");

    resetCardMachineMathCounts();
    setActiveRateUnit("minute");
    setActivePowerDisplayUnit("amps");
    try {
      buildMachineList(edited, calculateThroughput(edited));
    } finally {
      setActiveRateUnit("second");
      setActivePowerDisplayUnit("eu");
    }
    const arranged = { ...edited, nodes: edited.nodes.map((n, i) => ({ ...n, position: { x: 0, y: i * 300 } })) };
    buildMachineList(arranged, calculateThroughput(arranged));
    expect(cardMachineMathCounts()).toMatchObject({ readouts: 0, stats: 0, power: 0, steam: 0 });
  });
});

/** Numbers to 1e-9 relative (the shadow test's rule), everything else exactly. */
function near(a: unknown, b: unknown): boolean {
  if (typeof a === "number" && typeof b === "number")
    return a === b || (Number.isNaN(a) && Number.isNaN(b)) || Math.abs(a - b) <= 1e-9 * Math.max(Math.abs(a), Math.abs(b));
  if (Array.isArray(a) && Array.isArray(b)) return a.length === b.length && a.every((x, i) => near(x, b[i]));
  if (a && b && typeof a === "object" && typeof b === "object") {
    const keys = new Set([...Object.keys(a), ...Object.keys(b)]);
    return [...keys].every((k) => near((a as Record<string, unknown>)[k], (b as Record<string, unknown>)[k]));
  }
  return a === b;
}

const corePresent = existsSync(path.join(process.cwd(), "public", "core", "manifest.json"));
describe.skipIf(!corePresent)("a card's machine readout through the core (the machines area forced on)", () => {
  beforeAll(async () => {
    resetCoreForTests();
    await loadCore({ target: wasmGcSupported() ? "wasm-gc" : "js" });
  });
  afterAll(() => resetCoreForTests());

  it("gives the consumers the TypeScript's figures, from the core where it covers the card", () => {
    let throughCore = 0;
    let compared = 0;
    withAdopt({ machines: true }, () => {
      INPUTS.forEach((input, i) => {
        if (i % 3 !== 0) return;
        setActiveGameRates(rates(input));
        let before: ReturnType<typeof figuresAsBefore>;
        try {
          before = figuresAsBefore(input.recipe, input.node);
        } catch {
          return;
        }
        resetCardMachineMathCounts();
        const got = figuresOf(input.recipe, input.node);
        throughCore += cardMachineMathCounts().core;
        compared++;
        if (!near(got, before)) expect.fail(`${input.recipe.id}: ${JSON.stringify(got).slice(0, 300)}`);
        expect(near(cardTipFacts(input.recipe, input.node), tipsAsBefore(input.recipe, input.node))).toBe(true);
      });
    });
    expect(compared).toBeGreaterThan(2000);
    expect(throughCore).toBeGreaterThan(compared * 0.8);
  }, 300_000);
});
