// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { act, cleanup, renderHook } from "@testing-library/react";
import { ReactFlowProvider } from "@xyflow/react";
import type { ReactNode } from "react";
import { enrichPassiveProductionRecipe } from "@/lib/model/passive-production";
import { PROJECT_SCHEMA_VERSION, type FactoryProject, type Recipe } from "@/lib/model/types";
import { getVoltageTierMaxEuT } from "@/lib/model/tiers";
import { solveBooks } from "@/store/solve-books";
import { useFactoryStore } from "@/store/factory-store";
import type { RecipeNodeData } from "../../RecipeNode";
import { useCropCardModel } from "./use-special";

vi.mock("@/lib/compact-view", () => ({ isCompactViewport: () => false }));

const crop = (): Recipe =>
  enrichPassiveProductionRecipe({
    id: "crop",
    name: "Crop Farm: Argentia",
    machineType: "Crop Farm",
    minimumTier: "NONE",
    durationTicks: 3328,
    eut: 0,
    inputs: [
      {
        kind: "item",
        id: "factoryflow:cropsnh_seed:argentia",
        amount: 1,
        consumed: false,
        displayName: "Argentia Seeds",
      },
    ],
    outputs: [{ kind: "item", id: "leaf", amount: 2.29, displayName: "Argentia Leaf" }],
    metadata: {
      cropsNh: {
        tier: 7,
        growthPoints: 1400,
        dropChance: 0.6983373,
        growthCycleTicks: 256,
        growthMultiplier: 1,
        drops: [{ id: "leaf", stackSize: 1, weight: 10000 }],
      },
    },
    source: { recipeMap: "Crop Farm" },
  });

function load(managerTier: string): RecipeNodeData {
  const recipe = crop();
  const project: FactoryProject = {
    schemaVersion: PROJECT_SCHEMA_VERSION,
    id: "crop-amps",
    name: "Crop amps",
    solveMode: true,
    recipes: [recipe],
    nodes: [
      {
        id: "farm",
        recipeId: recipe.id,
        machineCount: 40,
        solvePin: 40,
        parallel: 1,
        overclockTier: "NONE",
        enabled: true,
        machineConfigTiers: { cropManagerTier: managerTier },
        position: { x: 0, y: 0 },
      },
    ],
    edges: [],
    storages: [],
    fuelProfiles: [],
  };
  const result = solveBooks(project);
  useFactoryStore.setState({ project, lastResult: result, isReadOnly: false });
  return {
    recipe,
    projectNode: project.nodes[0],
    result: { ...result.nodes.farm, theoreticalMachinesRequired: 40000, utilization: 1 },
  } as unknown as RecipeNodeData;
}

const wrapper = ({ children }: { children: ReactNode }) => (
  <ReactFlowProvider>{children}</ReactFlowProvider>
);

beforeEach(() => {
  vi.stubGlobal(
    "ResizeObserver",
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  );
});
afterEach(() => {
  act(() => useFactoryStore.getState().setPowerDisplayUnit("eu"));
  cleanup();
  vi.unstubAllGlobals();
});

it("reads the harvester's draw in amps of its own tier under Amps, and in EU/t otherwise", () => {
  const data = load("2");
  const eu = renderHook(() => useCropCardModel(data, false), { wrapper }).result.current.view.power;
  expect(eu?.unit).toBe("EU/t");
  const euT = Number(eu!.number.replace(/,/g, ""));
  expect(euT).toBeGreaterThan(0);

  act(() => useFactoryStore.getState().setPowerDisplayUnit("amps"));
  const amps = renderHook(() => useCropCardModel(data, false), { wrapper }).result.current.view
    .power;
  expect(amps?.unit).toBe("A MV");
  expect(Number(amps!.number.replace(/,/g, ""))).toBeCloseTo(euT / getVoltageTierMaxEuT("MV"), 0);
});
