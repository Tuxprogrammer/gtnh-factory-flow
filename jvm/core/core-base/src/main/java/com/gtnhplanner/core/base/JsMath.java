package com.gtnhplanner.core.base;

/** JavaScript's arithmetic where it differs from Java's and the website's numbers lean on it. */
public final class JsMath {

    private JsMath() {}

    /**
     * JavaScript's {@code Math.round}: the nearest whole number, a half towards +infinity ({@code -2.5} is {@code -2},
     * Java's {@code Math.rint} gives an even one); a negative number that rounds to zero is {@code -0}. NaN and the
     * infinities stay as they are.
     */
    public static double round(final double n) {
        if (n != n || n == Double.POSITIVE_INFINITY || n == Double.NEGATIVE_INFINITY) return n;
        final double floor = Math.floor(n);
        final double r = n - floor >= 0.5 ? floor + 1 : floor;
        return r == 0 && (n < 0 || 1 / n < 0) ? -0.0 : r;
    }
}
