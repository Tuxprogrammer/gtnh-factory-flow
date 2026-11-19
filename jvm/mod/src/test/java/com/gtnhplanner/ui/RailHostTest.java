package com.gtnhplanner.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.view.rail.RailMath;

/**
 * What the mod itself works out for the overview rail ({@link RailHost}): the steam a machine line burns and the
 * machines it is billed at its peak. The lines, the machine list and the figures are the core's, pinned by the shared
 * conformance cases ({@code conformance/view/rail-cases.json}).
 */
class RailHostTest {

    @Test
    void aSteamLineBurnsItsHungriestRecipeOnEveryBilledMachineAtPeakAndWhatRunsOnAverage() {
        final double[] perTick = { 6, 3 }, machines = { 1.25, 0.5 };
        assertArrayEquals(
            new double[] { 6 * 20 * 2, 6 * 20 * 1.25 + 3 * 20 * 0.5 },
            RailHost.steam(perTick, machines, 2),
            1e-9);
    }

    @Test
    void aLineThatBurnsNoSteamHasNoSteamFigure() {
        assertNull(RailHost.steam(new double[] { 0, 0 }, new double[] { 3, 1 }, 4));
    }

    @Test
    void aRecipeThatBurnsNoSteamIsLeftOutOfTheAverage() {
        assertArrayEquals(
            new double[] { 5 * 20 * 3, 5 * 20 * 2 },
            RailHost.steam(new double[] { 5, 0 }, new double[] { 2, 9 }, 3),
            1e-9);
    }

    @Test
    void peakBillsWholeMachinesUnlessACountIsSet() {
        // Row 22: a card with no set count is billed whole machines; a set count is billed as set (or the solve's
        // count when that is more), never rounded up past it.
        assertEquals(2, RailMath.peakCount(1.75, null, false), 1e-9);
        assertEquals(4.5, RailMath.peakCount(1.75, 4.5, false), 1e-9);
        assertEquals(1.75, RailMath.peakCount(1.75, 1.0, false), 1e-9);
    }
}
