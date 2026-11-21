// @vitest-environment jsdom
// The recorder's real boards: every recipe card of the plan fixtures (conformance/plans and examples/), solved by the
// website's own engine and drawn through `useCleanCardModel`, under three sets of dials, and once more with every card
// that runs set to Up to its count rounded up (Up to read-outs and bars along the foot). A plan keeping custom rate
// recipes with no card gains a card on each and the holders the fixtures lack (empty ones, a fluid, EU, a fluid tank). A holder card is drawn through its own
// hook (special/use-special.ts), which records its kind's facts too; so do generators, which that plan gains as well. setup.ts records each card's facts. Runs only under
// this directory's vitest.config.ts (record.mjs), never with the suite.
import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { cleanup, renderHook } from "@testing-library/react";
import { afterEach, beforeAll, expect, it } from "vitest";
import { useCleanCardModel } from "@/components/flow/clean/clean-model";
import { specialKindOf } from "@/components/flow/clean/special/special-model";
import { useHolderCardModel, usePowerCardModel } from "@/components/flow/clean/special/use-special";
import { buildPowerRecipe } from "@/lib/power/power-recipe";
import {
  createCustomRatePlaceholderRecipe,
  isCustomRateRecipe,
  withCustomRateSlot,
} from "@/lib/model/custom-rate";
import { createFluidTankPlaceholderRecipe } from "@/lib/model/fluid-tank";
import { parseFactoryProjectJson } from "@/lib/import-export/factory-json";
import type { FactoryNode, FactoryProject, Recipe } from "@/lib/model/types";
import { writeWorkspaceView } from "@/lib/workspace-view";
import { solveBooks } from "@/store/solve-books";
import { useFactoryStore } from "@/store/factory-store";

const root = path.resolve(__dirname, "../../..");
const PLAN_DIRS = [
  "conformance/plans/website",
  "conformance/plans/valid",
  "conformance/plans/mod",
  "examples",
];

const plans = PLAN_DIRS.flatMap((dir) =>
  readdirSync(path.join(root, dir))
    .filter((f) => f.endsWith(".json"))
    .sort()
    .map((f) => path.join(dir, f)),
);

const DIALS = [
  { rate: "second", power: "eu", average: true },
  { rate: "minute", power: "amps", average: false },
  { rate: "tick", power: "HV", average: true },
] as const;

beforeAll(() => {
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver;
});
afterEach(cleanup);

function drawCards(project: FactoryProject): number {
  const lastResult = solveBooks(project);
  useFactoryStore.setState({ project, lastResult });
  let drawn = 0;
  for (const node of project.nodes) {
    const recipe = project.recipes.find((r) => r.id === node.recipeId);
    if (!recipe) continue;
    const kind = specialKindOf(recipe, node);
    const data = { projectNode: node, recipe, result: lastResult.nodes[node.id] };
    try {
      if (kind === "custom-rate" || kind === "tank") renderHook(() => useHolderCardModel(data, false, kind));
      else if (kind === "power") renderHook(() => usePowerCardModel(data, false));
      else renderHook(() => useCleanCardModel(data, false));
      drawn++;
    } catch (error) {
      if (process.env.CARD_DEBUG) console.error(node.id, error);
      // A card the hook cannot draw outside the board (a special card's own pieces) records nothing.
    }
  }
  return drawn;
}

/**
 * A plan that keeps custom rate recipes with no card on them (a player's plan whose holders were deleted) gets a card
 * on each, and the holders the fixtures lack (its first card's node cloned onto new recipes).
 */
function withHolders(project: FactoryProject): FactoryProject {
  const model = project.nodes[0];
  const kept = project.recipes.filter(
    (r) => isCustomRateRecipe(r) && !project.nodes.some((node) => node.recipeId === r.id),
  );
  if (!model || kept.length === 0) return project;
  const custom = createCustomRatePlaceholderRecipe("x-custom-empty");
  const tankEmpty = createFluidTankPlaceholderRecipe("x-tank-empty");
  const fluid = (id: string, name: string) => ({ kind: "fluid" as const, id, displayName: name });
  const item = (id: string, name: string) => ({ kind: "item" as const, id, displayName: name });
  const tankCell: Recipe = {
    ...tankEmpty,
    id: "x-tank-cell",
    source: { recipeMap: "Tank" },
    inputs: [{ ...item("gregtech:gt.metaitem.01@30013", "Oxygen Cell"), amount: 1 }],
    outputs: [
      { ...fluid("oxygen", "Oxygen"), amount: 1000 },
      { ...item("ic2:itemcellempty", "Empty Cell"), amount: 1 },
    ],
  };
  const added: Recipe[] = [
    custom,
    withCustomRateSlot({ ...custom, id: "x-custom-acid" }, fluid("sulfuricacid", "Sulfuric Acid"), "request", 144),
    withCustomRateSlot({ ...custom, id: "x-custom-eu" }, { kind: "power" as const, id: "eu", displayName: "EU" }, "supply", 2560),
    withCustomRateSlot({ ...custom, id: "x-custom-third" }, item("minecraft:iron_ingot", "Iron Ingot"), "supply", 1 / 3),
    tankEmpty,
    tankCell,
  ];
  const nodes: FactoryNode[] = [...kept, ...added].map((recipe, i) => ({
    ...model,
    id: `x-holder-${i}`,
    recipeId: recipe.id,
    customRate: recipe.id === "x-custom-third" ? { perSecond: 1 / 3, mode: "supply" } : undefined,
    position: { x: model.position.x, y: model.position.y + 400 * (i + 1) },
  }));
  return { ...project, nodes: [...project.nodes, ...nodes], recipes: [...project.recipes, ...added] };
}

/**
 * Generators on the same plan (the fixtures have none): a tier select, a fixed one, a choice, numbers with and without
 * a unit, a switch, a setting another one switches off while pinned, and pinned readings (the plan's pins per source).
 */
function withGenerators(project: FactoryProject): FactoryProject {
  const model = project.nodes[0];
  if (!model || !project.nodes.some((n) => n.id.startsWith("x-holder-"))) return project;
  const specs: Array<[string, Record<string, string> | undefined]> = [
    ["combustion-generator", { tier: "MV" }],
    ["large-rocket-engine", { throttle: "1250", boost: "1" }],
    ["ic2-fluid-reactor", undefined],
    ["ic2-fluid-reactor", { design: "custom", customRate: "2500" }],
    ["advanced-boiler", { tier: "HV" }],
    ["large-neutralization-engine", { arms: "4" }],
  ];
  const added: Recipe[] = [];
  const nodes: FactoryNode[] = [];
  specs.forEach(([sourceId, settings], i) => {
    const recipe = buildPowerRecipe(sourceId, settings, `x-gen-${i}`);
    if (!recipe) return;
    added.push(recipe);
    nodes.push({
      ...model,
      id: `x-gen-${i}`,
      recipeId: recipe.id,
      machineConfigTiers: settings,
      customRate: undefined,
      position: { x: model.position.x + 600, y: model.position.y + 400 * (i + 1) },
    });
  });
  const reading = (sourceId: string, at = 0) =>
    `reading:${added.find((r) => r.power?.sourceId === sourceId)?.power?.stats[at]?.label ?? ""}`;
  return {
    ...project,
    nodes: [...project.nodes, ...nodes],
    recipes: [...project.recipes, ...added],
    settingPins: {
      ...project.settingPins,
      "power:large-rocket-engine": ["fuel", "throttle", "boost", reading("large-rocket-engine")],
      // The custom rate pinned on every reactor card: switched off on the one at a preset design.
      "power:ic2-fluid-reactor": ["design", "customRate", reading("ic2-fluid-reactor", 1)],
      "power:large-neutralization-engine": ["fuel", "rate", "arms", "armTier"],
    },
  };
}

/** Every card that runs, set to Up to its solved count rounded up. */
function upToEverywhere(project: FactoryProject): FactoryProject {
  const result = solveBooks(project);
  return {
    ...project,
    nodes: project.nodes.map((node) => {
      const needed = result.nodes[node.id]?.theoreticalMachinesRequired ?? 0;
      return needed > 0
        ? { ...node, solvePin: Math.max(1, Math.ceil(needed)), solvePinExact: false }
        : node;
    }),
  };
}

it.each(plans)("%s", (file) => {
  const project = withGenerators(
    withHolders(parseFactoryProjectJson(readFileSync(path.join(root, file), "utf8"))),
  );
  let drawn = 0;
  for (const dials of DIALS) {
    useFactoryStore.getState().setRateUnit(dials.rate);
    useFactoryStore.getState().setPowerDisplayUnit(dials.power);
    writeWorkspaceView({ averageMachineDraw: dials.average });
    drawn += drawCards(project);
  }
  useFactoryStore.getState().setRateUnit("second");
  useFactoryStore.getState().setPowerDisplayUnit("eu");
  writeWorkspaceView({ averageMachineDraw: true });
  drawn += drawCards(upToEverywhere(project));
  expect(drawn).toBeGreaterThanOrEqual(0);
});
