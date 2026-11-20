// @vitest-environment jsdom
// The recorder's real boards: every recipe card of the plan fixtures (conformance/plans and examples/), solved by the
// website's own engine and drawn through `useCleanCardModel`, under three sets of dials, and once more with every card
// that runs set to Up to its count rounded up (Up to read-outs and bars along the foot). setup.ts records each card's
// facts. Runs only under this directory's vitest.config.ts (record.mjs), never with the suite.
import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { cleanup, renderHook } from "@testing-library/react";
import { afterEach, beforeAll, expect, it } from "vitest";
import { useCleanCardModel } from "@/components/flow/clean/clean-model";
import { parseFactoryProjectJson } from "@/lib/import-export/factory-json";
import type { FactoryProject } from "@/lib/model/types";
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
    try {
      renderHook(() =>
        useCleanCardModel({ projectNode: node, recipe, result: lastResult.nodes[node.id] }, false),
      );
      drawn++;
    } catch {
      // A card the hook cannot draw outside the board (a special card's own pieces) records nothing.
    }
  }
  return drawn;
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
  const project = parseFactoryProjectJson(readFileSync(path.join(root, file), "utf8"));
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
