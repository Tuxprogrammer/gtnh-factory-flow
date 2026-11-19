import { describe, expect, it } from "vitest";
import { mockUnneeded } from "@/lib/solver/plan/__fixtures__/mock-extras";
import { computeGroupSummaries } from "./group-summary";

describe("a group summary's machines", () => {
  const withPocket = () => {
    const project = structuredClone(mockUnneeded.project);
    project.pockets = [{ id: "g", name: "G" }] as typeof project.pockets;
    project.nodes.find((n) => n.id === "comp")!.pocketId = "g";
    return project;
  };

  it("counts what an Up to card runs, rounded up", () => {
    const plan = {
      ...mockUnneeded.result.plan!,
      upTo: { comp: { capacity: 6, reached: 4.5, reason: null } },
    };
    const sum = computeGroupSummaries(withPocket(), { ...mockUnneeded.result, plan }, ["g"], true);
    expect(sum.get("g")!.machines).toBe(5);
  });

  it("counts the harvesters an Up to crop card's seeds fill, not the seeds", () => {
    const project = withPocket();
    const node = project.nodes.find((n) => n.id === "comp")!;
    const recipe = project.recipes.find((r) => r.id === node.recipeId)!;
    recipe.metadata = {
      cropsNh: {
        tier: 4,
        growthPoints: 1200,
        dropChance: 0.8,
        growthCycleTicks: 256,
        growthMultiplier: 1,
        drops: [{ id: "berry", stackSize: 2, weight: 10000 }],
      },
    };
    node.machineHandlerId = "crop-manager";
    node.solvePin = 50;
    const plan = {
      ...mockUnneeded.result.plan!,
      upTo: { comp: { capacity: 50, reached: 50, reason: null } },
    };
    const sum = computeGroupSummaries(project, { ...mockUnneeded.result, plan }, ["g"], true);
    expect(sum.get("g")!.machines).toBe(1);
  });

  it("counts an Exactly card at its set count", () => {
    const project = withPocket();
    project.nodes.find((n) => n.id === "comp")!.solvePinExact = true;
    const plan = {
      ...mockUnneeded.result.plan!,
      upTo: { comp: { capacity: 6, reached: 4.5, reason: null } },
    };
    const sum = computeGroupSummaries(project, { ...mockUnneeded.result, plan }, ["g"], true);
    expect(sum.get("g")!.machines).toBe(6);
  });
});
