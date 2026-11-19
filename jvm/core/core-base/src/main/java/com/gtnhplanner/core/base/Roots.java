package com.gtnhplanner.core.base;

/** Roots that come out the same on every target the core runs on. */
public final class Roots {

    private Roots() {}

    /**
     * The cube root, {@link ExactMath#cbrt} (fdlibm, StrictMath's bits on every target): exact on a perfect cube
     * (262144 gives 64, where the web compile's own Math.cbrt gives 63.999999999999986 and a floor then lands one low).
     * Kept for its callers; new code calls ExactMath.
     */
    public static double cbrt(final double x) {
        return ExactMath.cbrt(x);
    }
}
