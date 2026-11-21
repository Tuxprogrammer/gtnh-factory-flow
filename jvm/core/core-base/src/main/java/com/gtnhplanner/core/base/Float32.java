package com.gtnhplanner.core.base;

/**
 * Single-precision rounding that gives the same number on the JVM, TeaVM's WebAssembly GC target and TeaVM's JavaScript
 * target, as a double: the website's {@code Math.fround}. The JavaScript target keeps the result of a cast to float, of
 * float arithmetic and of an int or long converted to float as a double, unrounded (float literals, float[] stores and
 * the bit conversions do narrow), so code that must see single precision rounds through {@link Float32Ops}, the one
 * place a float is narrowed. This is its double-valued form, exported to JavaScript as {@code float32Round}.
 */
public final class Float32 {

    private Float32() {}

    /** The nearest float to {@code value}, as a double: {@code Math.fround}. NaN and the infinities stay as they are. */
    public static double round(final double value) {
        return Float32Ops.of(value);
    }
}
