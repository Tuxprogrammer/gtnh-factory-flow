package com.gtnhplanner.core.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Hand-checked spellings; the golden test holds the rest to the website. */
class FormatTest {

    @Test
    void compactLadder() {
        assertEquals("0", Format.compact(0));
        assertEquals("0.033", Format.compact(0.03333));
        assertEquals("123.5", Format.compact(123.45));
        assertEquals("6.93k", Format.compact(6930));
        assertEquals("1M", Format.compact(999_999.9));
        assertEquals("1E", Format.compact(1e18));
        assertEquals("-900", Format.compact(-900));
    }

    @Test
    void halfWayFollowsToFixedOnTheBinaryValue() {
        assertEquals("1", Format.compact(1.005));
        assertEquals("9.99", Format.compact(9.995));
    }

    @Test
    void nothingThrowsOnANumberThatIsNotFinite() {
        assertEquals("unbounded", Format.compact(Double.NaN));
        assertEquals("unbounded", Format.compact(Double.NEGATIVE_INFINITY));
        assertEquals("unbounded", Format.powerValue(Double.POSITIVE_INFINITY));
        assertEquals("NaN", Format.solvedMachines(Double.NaN));
        assertEquals("Infinity", Format.solvedMachines(Double.POSITIVE_INFINITY));
        assertTrue(Double.isNaN(Format.energyPerUnit(120, 0)));
    }

    @Test
    void stableKeepsAWidth() {
        assertEquals("999", Format.compactStable(999.4));
        assertEquals("1.23k", Format.compactStable(1234));
        assertEquals("180.0k", Format.compactStable(180_000));
        assertEquals("1.00E", Format.compactStable(1e18));
    }

    @Test
    void thousandsAndTrailingZeros() {
        assertEquals("1,234,567.89", Format.thousands("1234567.89"));
        assertEquals("-1,234", Format.thousands(-1234));
        assertEquals("1.5", Format.trimZeros("1.500"));
        assertEquals("10", Format.trimZeros("10.00"));
        assertEquals("100", Format.trimZeros("100"));
    }

    @Test
    void ratesCarryTheirUnit() {
        assertEquals("900/hr", Format.slotRate(0.25, RateUnit.HOUR, "item"));
        assertEquals("180k L/hr", Format.slotRate(50, RateUnit.HOUR, "fluid"));
        assertEquals("0.25/t", Format.slotRate(5, RateUnit.TICK, "item"));
        assertEquals("<0.01 EU/t", Format.slotRate(0.0001, RateUnit.SECOND, "power"));
    }

    @Test
    void machineCountsAndShares() {
        assertEquals("<0.001", Format.solvedMachines(0.0004));
        assertEquals("12.3", Format.solvedMachines(12.34));
        assertEquals("100", Format.solvedMachines(100.0000005));
        assertEquals("101", Format.solvedMachines(100.00001));
        assertEquals("1,234,567.123457", Format.machineListCount(1234567.1234567));
        assertEquals("33.33%", Format.ratioShare(1 / 3.0));
        assertEquals("<0.01%", Format.ratioShare(0.00001));
        assertEquals(">99.99%", Format.ratioShare(0.99999));
    }
}
