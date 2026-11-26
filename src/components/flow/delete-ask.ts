import type { FactoryProject } from "@/lib/model/types";
import { expandPocketSelection } from "@/lib/model/pocket-connections";

/**
 * The cards a delete of these board ids takes: a board goes with everything in it, as the delete does, and a shared
 * machine is one card. Drawers, notes and frames are not cards.
 */
export function cardsDeleteTakes(project: FactoryProject, nodeIds: Iterable<string>): number {
  const { itemIds } = expandPocketSelection(project, nodeIds);
  return project.nodes.filter((node) => itemIds.has(node.id)).length;
}

/**
 * Whether the delete asks first: more than one card waits for the player to say so, since a stray Ctrl+A and Delete
 * would otherwise empty the plan in one key. The in-game planner asks the same (jvm/mod ui/DeleteAsk).
 */
export function deleteAsks(cards: number): boolean {
  return cards > 1;
}

export function deleteQuestion(cards: number): string {
  return `Delete ${cards} cards?`;
}
