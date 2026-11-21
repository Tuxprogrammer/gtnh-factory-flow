import type { OverclockRule } from "@/lib/machines/machine-table";
import { powInt } from "@/lib/machines/game-math";

/** OverclockCalculator.durationDecreasePerHeatOC: a heat overclock divides the duration by 4. */
const DURATION_DECREASE_PER_HEAT_OC = 4;

/**
 * GregTech's `OverclockCalculator.calculateMultiplierUnderOneTick` (its non-laser branch, from the overclock count on):
 * how many times a multiblock's parallels are multiplied when its overclocks take a run under one tick.
 * `ParallelHelper.determineParallel` multiplies the machine's parallels by it, caps them at what the supply pays for,
 * and the run takes one tick. The same as core-machines' `UnderOneTick`.
 *
 * Kept line for line, quirk included. The regular overclocks it counts as "needed" are measured from the duration
 * after heat overclocks alone, which is already at or under one tick when the recipe is short enough, so that count is
 * zero or negative and every regular overclock (plus the negative count) is taken as extra on top of a correction that
 * already reaches one tick. Once the duration before regular overclocks is at most 1 / durationDecreasePerOC (half a
 * tick for normal overclocks, a quarter for perfect ones), the game runs `durationDecreasePerOC^-needed` times more
 * than `ceil(speedup / duration)`. The game's own answers: `conformance/machines/subtick-parallels.json`.
 *
 * `duration` is the recipe's ticks times the machine's duration modifier, before any overclock;
 * `heatOverclocksAvailable` is heat above the recipe / 1800 on a heat-overclocking machine, else 0.
 */
export function multiplierUnderOneTick(
  duration: number,
  overclocks: number,
  heatOverclocksAvailable: number,
  durationDecreasePerOC: number,
): number {
  // Java int arithmetic: an overclock factor of 1 (a Tungsten arc furnace electrode) makes a needed count of
  // (int) -Infinity, and the game's subtractions wrap.
  const heatOverclocks = Math.min(heatOverclocksAvailable, overclocks);
  const regularOverclocks = (overclocks - heatOverclocks) | 0;
  const durationAfterHeatOC = duration / powInt(DURATION_DECREASE_PER_HEAT_OC, heatOverclocks);
  const neededHeatOverclocks = javaInt(
    Math.ceil(Math.log(duration) / Math.log(DURATION_DECREASE_PER_HEAT_OC)),
  );
  const neededRegularOverclocks = javaInt(
    Math.ceil(Math.log(durationAfterHeatOC) / Math.log(durationDecreasePerOC)),
  );
  const extraHeatOverclocks = Math.max((heatOverclocks - neededHeatOverclocks) | 0, 0);
  const extraRegularOverclocks = Math.max((regularOverclocks - neededRegularOverclocks) | 0, 0);
  const heatMultiplier = powInt(DURATION_DECREASE_PER_HEAT_OC, extraHeatOverclocks);
  const regularMultiplier = powInt(durationDecreasePerOC, extraRegularOverclocks);

  // A fractional multiplier for the speedup lost to whole ticks.
  let correctionMultiplier: number;
  if (heatOverclocks >= neededHeatOverclocks) {
    correctionMultiplier = powInt(DURATION_DECREASE_PER_HEAT_OC, neededHeatOverclocks) / duration;
  } else if (regularOverclocks >= neededRegularOverclocks) {
    correctionMultiplier =
      powInt(durationDecreasePerOC, neededRegularOverclocks) / durationAfterHeatOC;
  } else {
    // Sub-tick is not reached.
    return 1;
  }
  return Math.ceil(heatMultiplier * regularMultiplier * correctionMultiplier);
}

/** Java's (int) cast of a double: NaN is 0, and anything past the int range is held at its end. */
function javaInt(x: number): number {
  if (Number.isNaN(x)) return 0;
  if (x >= 2147483647) return 2147483647;
  if (x <= -2147483648) return -2147483648;
  return Math.trunc(x);
}

/**
 * The game's parallel multiplier for a multiblock run its overclocks take under one tick, from the planner's rule: a
 * rule with perfect steps and then normal ones is a heat-overclocking machine (its perfect steps are the heat
 * overclocks), a rule with perfect steps only overclocks by its own factor, everything else halves the duration per
 * step. The run lasts 1 / multiplier ticks.
 */
export function ruleMultiplierUnderOneTick(
  rule: OverclockRule,
  duration: number,
  steps: number,
  perfectSteps: number,
): number {
  const heat = rule.maxPerfect > 0 && rule.maxNormal > 0;
  const perfectOnly = rule.maxPerfect > 0 && rule.maxNormal === 0;
  return multiplierUnderOneTick(
    duration,
    javaInt(steps),
    heat ? javaInt(perfectSteps) : 0,
    perfectOnly ? rule.multiplier : 2,
  );
}
