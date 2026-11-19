package com.gtnhplanner.core.base;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;

import org.junit.jupiter.api.Test;

/** JsMath.rem is Java's {@code %} on doubles, bit for bit, the sign of a zero and NaN included. */
class JsMathRemTest {

    private static void same(final double x, final double y) {
        assertEquals(Double.doubleToLongBits(x % y), Double.doubleToLongBits(JsMath.rem(x, y)), x + " % " + y);
    }

    @Test
    void agreesOnTheEdges() {
        final double[] edges = { 0.0, -0.0, 1, -1, 0.1, -0.1, 0.001, 2, 3, 5, 10, 100, 123456.789, 1e10, 1e15, 1e300,
            -1e300, 3.4028236e38, Double.MIN_VALUE, -Double.MIN_VALUE, Double.MIN_NORMAL, 2.5e-310, 1e-300,
            Double.MAX_VALUE, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN, Math.PI * 2, 1.0000001,
            16777217, 0.5, 4503599627370496.0, 9007199254740993.0 };
        for (final double x : edges) {
            for (final double y : edges) same(x, y);
        }
    }

    @Test
    void agreesOnSpreadsOfValues() {
        final Random random = new Random(5);
        for (int i = 0; i < 100000; i++) {
            same(Double.longBitsToDouble(random.nextLong()), Double.longBitsToDouble(random.nextLong()));
            // Close in size, the way a remainder is used.
            final double y = Math.scalb(1 + random.nextDouble(), random.nextInt(60) - 30);
            final double x = (random.nextBoolean() ? 1 : -1) * y * (random.nextInt(50) + random.nextDouble());
            same(x, y);
            same(random.nextInt(1000) * 0.1, 0.1);
            same(random.nextInt(10000) * 0.001 + random.nextInt(3), 0.001);
        }
    }

    @Test
    void theCasesTheWasmOperatorGetsWrong() {
        assertEquals(1.7374062258324052e-12, JsMath.rem(123456.789, 0.001));
        assertEquals(0.0, JsMath.rem(3.4028236e38, 2));
        assertEquals(-0.0, JsMath.rem(-0.1, 0.1));
        assertEquals(Double.NaN, JsMath.rem(0.0, -0.0));
    }
}
