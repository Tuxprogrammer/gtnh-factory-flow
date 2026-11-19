package com.gtnhplanner.core.base;

/** Roots that come out the same on every target the core runs on. */
public final class Roots {

    private Roots() {}

    /**
     * {@link Math#cbrt}, exact for a perfect cube: the JVM and JavaScript give 64 for 262144, TeaVM's version gives
     * 63.999999999999986, which a floor then turns into 63. Every other value is whatever {@code Math.cbrt} answers,
     * which on the web compile can be several places off the JVM's (cbrt(1e300) is 9.999999999999872e99, not 1e100):
     * never compare one for equality.
     */
    public static double cbrt(final double x) {
        if (x == 0) return x; // keeps the sign of a zero
        final double root = Math.cbrt(x);
        final double whole = Math.floor(root + 0.5);
        return whole * whole * whole == x ? whole : root;
    }
}
