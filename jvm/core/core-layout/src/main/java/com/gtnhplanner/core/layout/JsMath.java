package com.gtnhplanner.core.layout;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * JavaScript's number functions, to the bit, for the code ported from the website: the router's routes must come out
 * the same in the game, in the browser through the compiled core and in the TypeScript router they were ported from.
 * {@code Math.round} and {@code Math.hypot} differ from Java's, so they are written out here; the trigonometry is
 * {@link StrictMath} (fdlibm, which V8's own {@code atan2} is a port of).
 */
public final class JsMath {

    private JsMath() {}

    /** JavaScript's Math.round: the nearest integer, halves toward positive infinity. */
    public static double round(final double x) {
        if (Double.isNaN(x) || Double.isInfinite(x)) return x;
        final double f = Math.floor(x);
        return x - f >= 0.5 ? f + 1 : f;
    }

    /** JavaScript's Math.sign, as an int (NaN reads as 0). */
    public static int sign(final double x) {
        return x > 0 ? 1 : x < 0 ? -1 : 0;
    }

    /**
     * JavaScript's Math.hypot of two numbers as V8 computes it: both divided by the larger, squared and added with
     * Kahan compensation, the root scaled back. Java's {@link Math#hypot} rounds differently in the last bit.
     */
    public static double hypot(final double a, final double b) {
        if (Double.isInfinite(a) || Double.isInfinite(b)) return Double.POSITIVE_INFINITY;
        if (Double.isNaN(a) || Double.isNaN(b)) return Double.NaN;
        final double x = Math.abs(a), y = Math.abs(b);
        final double max = x > y ? x : y;
        if (max == 0) return 0;
        double sum = 0, compensation = 0;
        final double n0 = x / max, s0 = n0 * n0 - compensation;
        double preliminary = sum + s0;
        compensation = preliminary - sum - s0;
        sum = preliminary;
        final double n1 = y / max, s1 = n1 * n1 - compensation;
        preliminary = sum + s1;
        sum = preliminary;
        return Math.sqrt(sum) * max;
    }

    /**
     * JavaScript's String(n) for the numbers a request or the dials hold (integers, and decimals between 1e-6 and
     * 1e21): the shortest decimal that reads back as the same double. A stand-in until core-base's NumberText lands;
     * used only for keys compared as text.
     */
    public static String text(final double n) {
        if (Double.isNaN(n)) return "NaN";
        if (Double.isInfinite(n)) return n > 0 ? "Infinity" : "-Infinity";
        if (n == 0) return "0";
        if (n == Math.floor(n) && Math.abs(n) < 1e15) return Long.toString((long) n);
        final BigDecimal exact = new BigDecimal(n);
        for (int digits = 1; digits <= 17; digits++) {
            final BigDecimal shortest = exact.round(new MathContext(digits));
            if (shortest.doubleValue() == n) return shortest.stripTrailingZeros()
                .toPlainString();
        }
        return exact.toPlainString();
    }
}
