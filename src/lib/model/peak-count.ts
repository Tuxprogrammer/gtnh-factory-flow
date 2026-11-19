/**
 * The machines a card is billed for at its peak power. A card with a count set is billed that count, or what the
 * solve needs when that is more. A card with none is billed whole machines, since a fractional machine cannot be
 * built. A crop card's count and a shared machine's pool (`keepSolved`) keep the solved figure: neither is a whole
 * machine of its own, and a card that is not solving has no solved figure to round. The average draw stays the solved
 * fraction. The Java core's `RailMath.peakCount` is the same rule; `conformance/view/rail/math.json` pins both.
 */
export function peakCount(solved: number, set: number | undefined, keepSolved: boolean): number {
  if (set !== undefined && set > 0) return Math.max(set, solved);
  return keepSolved ? solved : Math.max(0, Math.ceil(solved - 1e-9));
}
