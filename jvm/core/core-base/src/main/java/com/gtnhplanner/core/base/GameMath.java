package com.gtnhplanner.core.base;

/**
 * Arithmetic the game does in its own way, which a different way of doing it answers differently in the last digit.
 *
 * <p>{@link #powInt} is GregTech's {@code GTUtility.powInt}: a power by repeated squaring, whose rounding differs from
 * {@code pow}'s (fdlibm's, in {@link ExactMath#pow}, is correct to under one ulp, squaring accumulates a few). The
 * coil discounts of the heating-coil multiblocks (0.98 per tier on the Industrial Coke Oven, 0.95 on the Large Thermal
 * Refinery, 0.9 on the Mega Oil Cracker and the Large Fluid Extractor) are taken with it, and a total in the hundreds of
 * millions of EU/t shows that last digit as a whole EU/t. Only multiplications and a bit pattern are used, so the answer
 * is the same on the JVM and on both web targets.
 */
public final class GameMath {

    private GameMath() {}

    /**
     * {@code GTUtility.powInt(base, exp)}: {@code base} to the integer {@code exp}, by squaring; a negative exponent is
     * the reciprocal of the positive one.
     */
    public static double powInt(final double base, final int exp) {
        if (exp > 0) return bySquaring(base, exp);
        if (exp < 0) return 1.0 / bySquaring(base, -exp);
        return 1.0;
    }

    /** {@code GTUtility.powBySquaring}, for a non-negative exponent. */
    private static double bySquaring(final double base, final int exp) {
        // Bases 2 and 4 are built from the exponent bits, as the game does: an exact power, infinity past the range.
        if (base == 2) return exp > 1023 ? Double.POSITIVE_INFINITY : Double.longBitsToDouble(exp + 1023L << 52);
        if (base == 4) return exp > 511 ? Double.POSITIVE_INFINITY : Double.longBitsToDouble(exp * 2L + 1023L << 52);
        double result = 1.0;
        double square = base;
        int rest = exp;
        while (rest > 0) {
            if ((rest & 1) == 1) result *= square;
            square *= square;
            rest >>= 1;
        }
        return result;
    }
}
