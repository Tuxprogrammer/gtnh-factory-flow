package com.gtnhplanner.core.base;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RootsTest {

    @Test
    void aPerfectCubeGivesItsWholeRoot() {
        for (long n = 1; n <= 100000; n++) assertEquals(n, Roots.cbrt((double) n * n * n), "cbrt(" + n + "^3)");
        assertEquals(64, Roots.cbrt(262144));
        assertEquals(-4, Roots.cbrt(-64));
        assertEquals(0, Roots.cbrt(0));
    }

    @Test
    void anythingElseIsMathCbrt() {
        for (final double x : new double[] { 2, 100, 0.5, -27, 123456.789, 1e-10, 3.4028236e38 }) {
            assertEquals(Math.cbrt(x), Roots.cbrt(x), "cbrt(" + x + ")");
        }
    }
}
