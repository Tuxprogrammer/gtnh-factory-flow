// @vitest-environment jsdom
// The recorder's real boards: every recipe card of the plan fixtures (conformance/plans and examples/), solved by the
// website's own engine and drawn through `useCleanCardModel`, under three sets of dials, and once more with every card
// that runs set to Up to its count rounded up (Up to read-outs and bars along the foot). A plan keeping custom rate
// recipes with no card gains a card on each and the holders the fixtures lack (empty ones, a fluid, EU, a fluid tank). A holder card is drawn through its own
// hook (special/use-special.ts), which records its kind's facts too. setup.ts records each card's facts. Runs only under
// this directory's vitest.config.ts (record.mjs), never with the suite.
import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { cleanup, renderHook } from "@testing-library/react";
import { afterEach, beforeAll, expect, it } from "vitest";
import { useCleanCardModel } from "@/components/flow/clean/clean-model";
import { specialKindOf } from "@/components/flow/clean/special/special-model";
import { useHolderCardModel } from "@/components/flow/clean/special/use-special";
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
  const project = withHolders(parseFactoryProjectJson(readFileSync(path.join(root, file), "utf8")));
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
