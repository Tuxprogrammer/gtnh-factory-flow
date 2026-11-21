package com.gtnhplanner.machines.web;

import com.gtnhplanner.core.base.ExactMath;
import com.gtnhplanner.core.base.GameMath;

/**
 * GregTech's {@code OverclockCalculator.calculateMultiplierUnderOneTick} (its non-laser branch, from the overclock count
 * on): how many times a multiblock's parallels are multiplied when its overclocks take a run under one tick.
 * {@code ParallelHelper.determineParallel} multiplies the machine's parallels by it, caps them at what the supply pays
 * for, and the run takes one tick.
 *
 * <p>Kept line for line, quirk included. The regular overclocks it counts as "needed" are measured from the duration
 * after heat overclocks alone, which is already at or under one tick when the recipe is short enough, so that count is
 * zero or negative and every regular overclock (plus the negative count) is taken as extra on top of a correction that
 * already reaches one tick. Once the duration before regular overclocks is at most 1 / durationDecreasePerOC (half a
 * tick for normal overclocks, a quarter for perfect ones), the game runs {@code durationDecreasePerOC^-needed} times
 * more than {@code ceil(speedup / duration)}: a 1-tick recipe in a Large Electric Compressor (half the duration) runs
 * twice that, in the Black Hole Compressor (a fifth) four times. The game's own answers:
 * {@code conformance/machines/subtick-parallels.json}.
 */
public final class UnderOneTick {

    private UnderOneTick() {}

    /** OverclockCalculator.durationDecreasePerHeatOC: a heat overclock divides the duration by 4. */
    private static final double DURATION_DECREASE_PER_HEAT_OC = 4;

    /**
     * The multiplier on a multiblock's parallels.
     *
     * @param duration the recipe's ticks times the machine's duration modifier, before any overclock
     * @param overclocks the overclocks the machine's power and limits allow
     * @param heatOverclocksAvailable heat above the recipe / 1800 on a heat-overclocking machine, else 0
     * @param durationDecreasePerOC what a regular overclock divides the duration by: 2, or 4 for perfect overclocks
     */
    public static double multiplier(final double duration, final int overclocks, final int heatOverclocksAvailable,
        final double durationDecreasePerOC) {
        // Split overclocks into heat-based and regular overclocks.
        final int heatOverclocks = Math.min(heatOverclocksAvailable, overclocks);
        final int regularOverclocks = overclocks - heatOverclocks;

        // Compute the duration after heat overclocks have been applied.
        final double durationAfterHeatOC = duration / GameMath.powInt(DURATION_DECREASE_PER_HEAT_OC, heatOverclocks);

        // Compute required heat overclocks to reach a sub-tick duration.
        final int neededHeatOverclocks = (int) Math
            .ceil((ExactMath.log(duration) / ExactMath.log(DURATION_DECREASE_PER_HEAT_OC)));

        // Compute required regular overclocks to reach sub-tick duration after heat overclocks.
        final int neededRegularOverclocks = (int) Math
            .ceil((ExactMath.log(durationAfterHeatOC) / ExactMath.log(durationDecreasePerOC)));

        // Compute the speedup of all the overclocks after sub-tick has been reached.
        final int extraHeatOverclocks = Math.max(heatOverclocks - neededHeatOverclocks, 0);
        final int extraRegularOverclocks = Math.max(regularOverclocks - neededRegularOverclocks, 0);
        final double heatMultiplier = GameMath.powInt(DURATION_DECREASE_PER_HEAT_OC, extraHeatOverclocks);
        final double regularMultiplier = GameMath.powInt(durationDecreasePerOC, extraRegularOverclocks);

        // Compute a fractional multiplier that accounts for the speedup that is lost due to discretization.
        final double correctionMultiplier;
        if (heatOverclocks >= neededHeatOverclocks) {
            correctionMultiplier = GameMath.powInt(DURATION_DECREASE_PER_HEAT_OC, neededHeatOverclocks) / duration;
        } else if (regularOverclocks >= neededRegularOverclocks) {
            correctionMultiplier = GameMath.powInt(durationDecreasePerOC, neededRegularOverclocks)
                / durationAfterHeatOC;
        } else {
            // Sub-tick is not achieved, so return unit.
            return 1.0;
        }

        return Math.ceil(heatMultiplier * regularMultiplier * correctionMultiplier);
    }
}
