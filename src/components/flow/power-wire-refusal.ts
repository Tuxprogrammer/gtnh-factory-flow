import type { FactoryProject } from "@/lib/model/types";
import { POWER_WIRE_REFUSED } from "@/lib/power/power-recipe";

/**
 * The sentence for an EU wire let go on a card that refused it, or nothing. Only another card (a machine or generator)
 * gets it: empty board spawns a drawer, a drawer that takes EU takes the wire, and letting go on the card it started
 * from is a click, not an attempt.
 */
export function powerWireRefusal(
  project: Pick<FactoryProject, "nodes">,
  dragged: { kind?: string; nodeId?: string } | undefined,
  cardId: string | undefined,
): string | undefined {
  if (dragged?.kind !== "power" || !cardId || cardId === dragged.nodeId) return undefined;
  return project.nodes.some((node) => node.id === cardId) ? POWER_WIRE_REFUSED : undefined;
}
