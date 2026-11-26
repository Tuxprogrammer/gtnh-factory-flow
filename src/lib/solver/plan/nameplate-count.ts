import type { FactoryProject } from "../../model/types";

/**
 * A plan card's stored count is no control: the solver chooses how many machines run (a pin is `solvePin`). The count
 * is only what the nameplate loop computes a card's flows and EU/t at, which the plan adapter divides back out to one
 * machine's. A card stored at 0 (the mod's export writes the count it solved, 0 for a card it did not need) would hand
 * the solve a machine that draws nothing and a read-out with nothing to scale, so the card would read idle while its
 * wires carry the solve's flow. Such a card is read at 1, as a new card is. A project outside plan mode, where a count
 * is what is built, comes back as it is.
 */
export function withPlanNameplateCounts(project: FactoryProject): FactoryProject {
  if (!project.planMode || project.nodes.every((node) => node.machineCount > 0)) {
    return project;
  }
  return {
    ...project,
    nodes: project.nodes.map((node) => (node.machineCount > 0 ? node : { ...node, machineCount: 1 })),
  };
}
