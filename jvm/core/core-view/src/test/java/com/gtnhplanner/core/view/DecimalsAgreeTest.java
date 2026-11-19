package com.gtnhplanner.core.view;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.base.NumberText;

/**
 * The moved card formatters (Fmt, PowerTiles) start their rounding from BigDecimal.valueOf, which the purity gate
 * allows for now (allow.txt). The swap it asks for, {@code new BigDecimal(NumberText.of(v))}, must read a double as the
 * same value, or a figure on the board would change: here it does for every normal double. A subnormal can differ
 * (5e-324 in JavaScript, 4.9E-324 from the JDK, which always prints a second digit), and every use rounds those to 0.
 */
class DecimalsAgreeTest {

    private static void same(final double v) {
        assertEquals(0, new BigDecimal(NumberText.of(v)).compareTo(BigDecimal.valueOf(v)), () -> "for " + v);
    }

    @Test
    void numberTextReadsEveryDoubleAsValueOfDoes() {
        for (final double v : new double[] { 0, -0.0, 1, 0.1, 0.15, 1.005, 999.995, 1e-7, 1e21, 1.5e300,
            Double.MAX_VALUE, Double.MIN_NORMAL, 123456789.123, 2.5e-5, 6930, 1 / 3.0 }) {
            same(v);
            same(-v);
        }
        final SplittableRandom random = new SplittableRandom(20261119);
        for (int i = 0; i < 200_000; i++) {
            final double bits = Double.longBitsToDouble(random.nextLong());
            if (Double.isFinite(bits) && Math.abs(bits) >= Double.MIN_NORMAL) same(bits);
            same(random.nextDouble() * Math.pow(10, random.nextInt(-8, 16)));
            same(Math.round(random.nextDouble() * 1e6) / 1000.0);
        }
    }
}
