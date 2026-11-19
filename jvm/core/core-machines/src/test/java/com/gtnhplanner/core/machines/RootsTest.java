package com.gtnhplanner.core.machines;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RootsTest {

    /** A perfect cube's root is the exact integer, on every runtime (TeaVM's Math.cbrt is one ulp off for 262144). */
    @Test
    void perfectCubesGiveExactRoots() {
        for (long n = 0; n <= 4096; n++) assertEquals((double) n, Roots.cbrt((double) (n * n * n)), "cube of " + n);
    }

    @Test
    void otherNumbersFollowMathCbrt() {
        assertEquals(Math.cbrt(100), Roots.cbrt(100));
        assertEquals(Math.cbrt(0.5), Roots.cbrt(0.5));
        assertEquals(Math.cbrt(-27), Roots.cbrt(-27));
    }
}
