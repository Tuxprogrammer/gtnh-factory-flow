package com.gtnhplanner.core.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * NumberText.javaString and javaDecimal against this JVM's Double.toString and BigDecimal.valueOf (the test JVM is
 * JDK 25; the specification they follow is JDK 19's), on a million doubles of every kind: same characters, and the same
 * BigDecimal down to its scale.
 */
class JavaDecimalTest {

    private static double next(final Random r) {
        switch (r.nextInt(8)) {
            case 0:
                return Double.longBitsToDouble(r.nextLong());
            case 1:
                return Math.scalb(1.0 + r.nextDouble(), r.nextInt(2098) - 1075) * (r.nextBoolean() ? 1 : -1);
            case 2:
                return r.nextInt(2_000_000) / 1000.0 - 1000;
            case 3:
                return (double) r.nextInt(100) * Math.pow(10, r.nextInt(600) - 300);
            case 4:
                return Double.longBitsToDouble(r.nextLong() & 0x800fffffffffffffL); // subnormal
            case 5:
                return r.nextDouble() * 20;
            case 6:
                return (double) (r.nextLong() >> r.nextInt(64));
            default:
                return 1.0 / (1 + r.nextInt(1000)) * Math.pow(10, r.nextInt(20) - 10);
        }
    }

    @Test
    void spellsAndReadsAsTheJdkDoes() {
        final Random r = new Random(19);
        final List<String> wrong = new ArrayList<>();
        final double[] edges = { Double.MIN_VALUE, -Double.MIN_VALUE, 2 * Double.MIN_VALUE, 1e-323, Double.MIN_NORMAL,
            Double.MAX_VALUE, 1e23, 9.999999999999999e22, 2e23, 1e7, 9999999.999999998, 1e-3, 0.0009999999999999998, 0.1,
            100, 1e21, 0.5, 2e-323, 5e-324 };
        for (int i = 0; i < 1_000_000 + edges.length; i++) {
            final double x = i < edges.length ? edges[i] : next(r);
            if (Double.isNaN(x) || Double.isInfinite(x)) continue;
            final String want = Double.toString(x), got = NumberText.javaString(x);
            final BigDecimal wantD = BigDecimal.valueOf(x), gotD = NumberText.javaDecimal(x);
            if ((!want.equals(got) || !wantD.equals(gotD)) && wrong.size() < 10) {
                wrong.add(Long.toHexString(Double.doubleToRawLongBits(x)) + ": JDK " + want + ", core " + got);
            }
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
        assertEquals("-0.0", NumberText.javaString(-0.0));
        assertEquals("NaN", NumberText.javaString(Double.NaN));
        assertThrows(NumberFormatException.class, () -> NumberText.javaDecimal(Double.NaN));
        assertThrows(NumberFormatException.class, () -> NumberText.javaDecimal(Double.POSITIVE_INFINITY));
    }
}
