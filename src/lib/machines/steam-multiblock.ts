/**
 * Tick rounding of the steam multiblocks. Their processing logic builds
 * `OverclockCalculator.ofNoOverclock(recipe)`, which sets `noOverclock`, and
 * `calculateOverclock` then returns `(int) Math.ceil(duration * durationModifier)`:
 * the duration rounds UP, not down as an overclocked recipe's does. With
 * `noOverclock` set, `calculateMultiplierUnderOneTick` returns 1, so a run
 * shorter than a tick is billed as a whole tick and banks no parallels.
 */
export function quantiseSteamMultiblockDuration(durationTicks: number): number {
  return Number.isFinite(durationTicks) && durationTicks > 1 ? Math.ceil(durationTicks) : 1;
}
