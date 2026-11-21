package com.gtnhplanner.core.base;

/**
 * A solved number with the LP engine's rounding taken off. The engine's answers carry about 14 correct significant
 * digits: a machine that runs exactly 2/3 of the time comes back as 0.66666666666666496, and a figure made from it that
 * sits exactly on a display half (480 EU/t x 2/3 = 0.625 A at HV) then prints one step down. Read once where a solve's
 * answer leaves the solver ({@code SolutionView}), so every reader, the website and the mod, sees the nearest double to
 * the ratio the plan really asks for.
 *
 * <p>
 * The rule: the value becomes the nearest double to the first continued-fraction convergent p/q of it with q at most
 * {@link #MAX_DENOMINATOR} that lies within {@link #TOLERANCE} of it, relative, and within {@link #CHANCE} / q^2 of it,
 * absolute; a value with no such convergent is left as it is. The second bound is how near a value that has no reason
 * to be a ratio lands to one by chance (about 14 x CHANCE of all values, whatever their size), so a long sum or a
 * typed decimal is not read as a fraction it merely resembles. A move is never larger than 1e-12 relative, far below
 * anything a display shows and far above the engine's noise. Only floor, subtraction, multiplication and division are
 * used, each exact or correctly rounded in IEEE arithmetic, so the JVM and both web targets give the same double.
 */
public final class SolvedNumber {

    /** How far, relative, a value may sit from the ratio it is read as: room for the engine's 14 digits, no more. */
    public static final double TOLERANCE = 1e-12;
    /**
     * The largest denominator a value is read with. Recipe ratios have small ones (2/3, 20/17, a few thousand along a
     * long chain); a bound keeps a value that is no small ratio from being read as one.
     */
    public static final double MAX_DENOMINATOR = 100_000;
    /** How close, times q^2, a value must sit to p/q to be read as it: 2/3 with the engine's noise sits at 1e-14. */
    public static final double CHANCE = 1e-4;
    /** From 2^53 up a double no longer holds every integer, so p and q stop being exact. */
    private static final double EXACT_INTEGERS = 9007199254740992.0;

    private SolvedNumber() {}

    /** {@code x} read as the small ratio it is within noise of, or {@code x} itself (zero, NaN and infinity too). */
    public static double clean(final double x) {
        if (x == 0 || Double.isNaN(x) || Double.isInfinite(x)) return x;
        final double a = Math.abs(x);
        if (a >= EXACT_INTEGERS) return x;
        // Convergents h/k of a's continued fraction, from a0/1.
        double hBefore = 1, h = Math.floor(a), kBefore = 0, k = 1;
        double rest = a - h;
        while (true) {
            final double ratio = h / k;
            final double off = Math.abs(ratio - a);
            if (off <= TOLERANCE * a && off * k * k <= CHANCE) return x < 0 ? -ratio : ratio;
            if (rest == 0) return x;
            final double inverse = 1 / rest;
            final double term = Math.floor(inverse);
            rest = inverse - term;
            final double hNext = term * h + hBefore, kNext = term * k + kBefore;
            if (!(kNext <= MAX_DENOMINATOR) || !(hNext < EXACT_INTEGERS)) return x;
            hBefore = h;
            h = hNext;
            kBefore = k;
            k = kNext;
        }
    }
}
