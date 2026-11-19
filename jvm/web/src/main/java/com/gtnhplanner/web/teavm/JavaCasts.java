package com.gtnhplanner.web.teavm;

/**
 * Java's conversions of a double or float to int and long, for the JavaScript target: NaN is 0 and a value past the
 * range is the nearest end. {@link ExactMathPlugin} sends every such conversion here when compiling to JavaScript (not
 * this class's own, which are the raw ones it guards: inside the range they are exact on every target).
 */
public final class JavaCasts {

    private JavaCasts() {}

    private static final double INT_TOP = 2147483647.0, INT_BOTTOM = -2147483648.0, LONG_EDGE = 9.223372036854775808E18;

    public static int d2i(final double x) {
        if (x != x) return 0;
        if (x >= INT_TOP) return Integer.MAX_VALUE;
        if (x <= INT_BOTTOM) return Integer.MIN_VALUE;
        return (int) x;
    }

    public static long d2l(final double x) {
        if (x != x) return 0L;
        if (x >= LONG_EDGE) return Long.MAX_VALUE;
        if (x <= -LONG_EDGE) return Long.MIN_VALUE;
        return (long) x;
    }

    public static int f2i(final float x) {
        return d2i(x);
    }

    public static long f2l(final float x) {
        return d2l(x);
    }
}
