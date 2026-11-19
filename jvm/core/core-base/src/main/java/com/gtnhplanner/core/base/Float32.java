package com.gtnhplanner.core.base;

/**
 * Single-precision rounding that gives the same number on the JVM, TeaVM's WebAssembly GC target and TeaVM's JavaScript
 * target. The JavaScript target keeps a {@code float} as a double: a cast to float changes nothing, and float
 * arithmetic and int-to-float conversions stay double. (Float literals, float array stores and
 * {@code floatToIntBits}/{@code intBitsToFloat} do narrow.) Code that must see single precision therefore rounds
 * through the bit pattern, which every target narrows exactly. The website does the same with {@code Math.fround}.
 */
public final class Float32 {

    private Float32() {}

    /** The nearest float to {@code value}, as a double: {@code Math.fround}. NaN and the infinities stay as they are. */
    public static double round(final double value) {
        return Float.intBitsToFloat(Float.floatToIntBits((float) value));
    }
}
