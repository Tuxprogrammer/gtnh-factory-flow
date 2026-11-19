package com.gtnhplanner.core.machines;

/** Roots that come out the same on every target the core runs on. */
public final class Roots {

    private Roots() {}

    /**
     * {@link Math#cbrt}, exact for a perfect cube: the JVM and JavaScript give 64 for 262144, TeaVM's version gives
     * 63.99999999999999, which a floor then turns into 63. Every other value is whatever {@code Math.cbrt} answers.
     */
    public static double cbrt(final double x) {
        final double root = Math.cbrt(x);
        final double whole = Math.rint(root);
        return whole * whole * whole == x ? whole : root;
    }
}
