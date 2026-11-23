/**
 * Plans the game's item renames moved on this page (`withRenamedProjectResources` inside the quiet recipe refresh).
 *
 * Opening never writes: a live plan's room keeps the ids it holds until the player edits. The first real edit then
 * sends the whole move ahead of itself (live-session.ts), so the stored plan goes from the old ids to the new ones in
 * one step and is never part one, part the other.
 */
const renamedPlans = new Set<string>();

export function notePlanRenamed(planId: string): void {
  renamedPlans.add(planId);
}

/** Whether `planId` was moved and the move is not yet sent (once: asking uses it up). */
export function takePlanRenamed(planId: string): boolean {
  return renamedPlans.delete(planId);
}
