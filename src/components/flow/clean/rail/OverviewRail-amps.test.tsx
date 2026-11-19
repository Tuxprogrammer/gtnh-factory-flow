// @vitest-environment jsdom
import { afterEach, beforeAll, expect, it, vi } from "vitest";
import { act, cleanup, render } from "@testing-library/react";
import { mockLeftovers } from "@/lib/solver/plan/__fixtures__/mock-extras";
import type { FactoryNode, FactoryProject } from "@/lib/model/types";
import { formatCompact } from "@/lib/model/resources";
import { getVoltageTierMaxEuT } from "@/lib/model/tiers";
import { buildPowerRecipe, powerCardTier } from "@/lib/power/power-recipe";
import { solveBooks } from "@/store/solve-books";
import { useFactoryStore } from "@/store/factory-store";
import { OverviewRail } from "./OverviewRail";
import { buildRailModel } from "./rail-model";

vi.mock("@/lib/compact-view", () => ({ isCompactViewport: () => false }));

beforeAll(() => {
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver;
});

afterEach(() => {
  act(() => useFactoryStore.getState().setPowerDisplayUnit("eu"));
  cleanup();
});

/** The leftovers plan's LV machines and a Large Gas Turbine card beside them. */
function loadWithTurbine() {
  const turbine = buildPowerRecipe("large-gas-turbine", undefined, "r-lgt")!;
  const node = {
    id: "lgt",
    recipeId: turbine.id,
    machineCount: 1,
    parallel: 1,
    overclockTier: "NONE",
    solvePin: 1,
    enabled: true,
    position: { x: 0, y: 0 },
  } as FactoryNode;
  const base = mockLeftovers.project;
  const project: FactoryProject = {
    ...base,
    recipes: [...base.recipes, turbine],
    nodes: [...base.nodes, node],
  };
  const result = solveBooks(project);
  useFactoryStore.setState({ project, lastResult: result, isReadOnly: false });
  return { turbine, rail: buildRailModel(project, result) };
}

it("reads each machine line in amps of its own card's tier under Amps; the totals stay EU/t", () => {
  const { turbine, rail } = loadWithTurbine();
  const { container } = render(<OverviewRail />);
  const lineOf = (id: string) =>
    container.querySelector(`[data-rail-machine="${id}"] .rail-eu`)?.textContent ?? "";
  const lgt = rail.machines.find((m) => m.nodeId === "lgt")!;
  const user = rail.machines.find((m) => m.nodeId !== "lgt" && m.tier && (m.euT ?? 0) > 0)!;
  const tier = powerCardTier(turbine) as NonNullable<typeof user.tier>;
  expect(lgt.madeEuT).toBeGreaterThan(0);
  expect(lineOf("lgt").startsWith(`+${formatCompact(lgt.madeEuT!)}`)).toBe(true);
  expect(lineOf(user.nodeId).startsWith(formatCompact(user.euT!))).toBe(true);

  // A plain switch, nothing else touched: the memoised lines must follow it.
  act(() => useFactoryStore.getState().setPowerDisplayUnit("amps"));
  const madeAmps = formatCompact(lgt.madeEuT! / getVoltageTierMaxEuT(tier));
  const usedAmps = formatCompact(user.euT! / getVoltageTierMaxEuT(user.tier!));
  expect(lineOf("lgt")).toMatch(new RegExp(`^\\+${madeAmps.replace(".", "\\.")}( A ${tier})?$`));
  expect(lineOf(user.nodeId)).toMatch(
    new RegExp(`^${usedAmps.replace(".", "\\.")}( A ${user.tier})?$`),
  );
  // A total adds cards of different tiers: no one tier to count it in.
  const totals = [...container.querySelectorAll(".rail-sec-end, .rail-power b")].map(
    (e) => e.textContent ?? "",
  );
  expect(totals.some((text) => text.includes("EU/t"))).toBe(true);
  expect(totals.every((text) => !/ A [A-Za-z]+$/.test(text))).toBe(true);

  act(() => useFactoryStore.getState().setPowerDisplayUnit("eu"));
  expect(lineOf("lgt").startsWith(`+${formatCompact(lgt.madeEuT!)}`)).toBe(true);
});
