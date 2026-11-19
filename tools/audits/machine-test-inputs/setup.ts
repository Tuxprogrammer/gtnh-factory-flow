// Records every (recipe, node) the website's machine tests hand the machine maths, for the shadow test
// (src/lib/core/machines-shadow.test.ts): the core must answer each of them as the TypeScript does. Run through
// vitest.config.ts beside this file; each test worker appends its lines to MACHINE_INPUTS_OUT.
import { appendFileSync } from "node:fs";
import { afterAll, vi } from "vitest";
import type { FactoryNode, Recipe } from "@/lib/model/types";
import { getGameRates } from "@/lib/model/game-rates";

const lines = new Set<string>();
// game-rates is not mocked: this is the instance the tests set.

function record(recipe: Recipe, node: Partial<FactoryNode> | undefined): void {
  if (!recipe || typeof recipe !== "object" || !node || typeof node !== "object") return;
  try {
    lines.add(JSON.stringify({ recipe, node, gameRates: getGameRates() }));
  } catch {
    // A cyclic or otherwise unprintable input is not one the core could be handed either.
  }
}

vi.mock("@/lib/solver/overclock", async (importOriginal) => {
  const m = await importOriginal<typeof import("@/lib/solver/overclock")>();
  return {
    ...m,
    getOverclockedRecipeStats: ((recipe, node, ...rest) => {
      const out = m.getOverclockedRecipeStats(recipe, node, ...rest);
      record(recipe as Recipe, node as Partial<FactoryNode>);
      return out;
    }) as typeof m.getOverclockedRecipeStats,
  };
});

vi.mock("@/lib/solver/power-report", async (importOriginal) => {
  const m = await importOriginal<typeof import("@/lib/solver/power-report")>();
  return {
    ...m,
    getNodePowerReport: ((recipe, node) => {
      record(recipe, node as Partial<FactoryNode>);
      return m.getNodePowerReport(recipe, node);
    }) as typeof m.getNodePowerReport,
    getNodeSteamReport: ((recipe, node) => {
      record(recipe, node as Partial<FactoryNode>);
      return m.getNodeSteamReport(recipe, node);
    }) as typeof m.getNodeSteamReport,
  };
});

afterAll(() => {
  const out = process.env.MACHINE_INPUTS_OUT;
  if (out && lines.size) appendFileSync(out, [...lines].join("\n") + "\n");
});
