package com.gtnhplanner.core.base;

/**
 * Single-precision arithmetic that rounds the same on the JVM, TeaVM's WebAssembly GC target and TeaVM's JavaScript
 * target. The JavaScript target keeps the result of a {@code (float)} cast of a double, of float {@code + - * /} and of
 * an int or long converted to float as a double, unrounded; the JVM and WebAssembly round it to a float. Every method
 * here returns a float that was narrowed through its bit pattern ({@link Float#floatToIntBits} narrows exactly on every
 * target, as float literals and float[] stores do), so it holds the same number everywhere.
 *
 * <p>
 * Add, subtract, multiply and divide go through a double: for two floats the exact result rounds to a double, and that
 * to a float, to the same float the JVM's own operation gives (a double carries more than twice a float's digits plus
 * two, so the double rounding cannot move the result). The purity gate's float-narrow rule sends every float operation
 * in the core here; this class is the one excused place.
 */
public final class Float32Ops {

    private Float32Ops() {}

    /** The nearest float to {@code value}: the JVM's {@code (float) value}. NaN and the infinities stay as they are. */
    public static float of(final double value) {
        return Float.intBitsToFloat(Float.floatToIntBits((float) value));
    }

    /** {@code (float) value}, rounded to nearest even as the JVM does (an int has up to 31 bits, a float 24). */
    public static float of(final int value) {
        return of((double) value);
    }

    /** {@code (float) value}, rounded once to nearest even (a long has up to 63 bits, a double only 53). */
    public static float of(final long value) {
        if (value >= -EXACT && value <= EXACT) return of((double) value);
        // Past 2^53 a double would round first and a float again. Drop the low 11 bits into a sticky bit instead: the
        // rest fits a double exactly, the sticky bit sits far below a float's last place, and scaling back is exact.
        final boolean negative = value < 0;
        final long magnitude = negative ? -value : value; // Long.MIN_VALUE keeps its bits: 2^63 as unsigned
        long high = magnitude >>> 11;
        if ((magnitude & 0x7FF) != 0) high |= 1;
        final double scaled = (double) high * 2048.0;
        return of(negative ? -scaled : scaled);
    }

    private static final long EXACT = 1L << 53;

    /** {@code a + b} as a float. */
    public static float add(final float a, final float b) {
        return of((double) a + b);
    }

    /** {@code a - b} as a float. */
    public static float sub(final float a, final float b) {
        return of((double) a - b);
    }

    /** {@code a * b} as a float. */
    public static float mul(final float a, final float b) {
        return of((double) a * b);
    }

    /** {@code a / b} as a float. */
    public static float div(final float a, final float b) {
        return of((double) a / b);
    }
}
