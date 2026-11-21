package com.gtnhplanner.web.exports;

import org.teavm.jso.JSExport;

import com.gtnhplanner.web.teavm.JavaProbe;

/**
 * Java's arithmetic as the solver module runs it, for the conformance run only: the same probes as BaseExports' java*
 * functions, compiled into the solver module (modules.txt), which is its own TeaVM compilation. tools/web-core
 * exact-math.mjs holds them to the JVM (groups solver/java-math/*, solver/strict-math/*, solver/java/*), which proves
 * the build plugin's Math, negation, cast and ordering rewrites and the BigDecimal override reach ojAlgo's module too.
 */
public final class SolverJavaExports {

    private SolverJavaExports() {}

    /** java.lang.Math ({@code strict} false) or StrictMath by name; the build plugin sends both to ExactMath. */
    @JSExport
    public static double javaMath(final boolean strict, final String fn, final double x, final double y) {
        return JavaProbe.math(strict, fn, x, y);
    }

    /** Math.round or StrictMath.round as hex. */
    @JSExport
    public static String javaMathRoundHex(final boolean strict, final double x) {
        return JavaProbe.roundHex(strict, x);
    }

    /** SolvedNumber.clean compiled into the solver module, which is where a solve's answer is cleaned. */
    @JSExport
    public static double solvedNumberClean(final double x) {
        return com.gtnhplanner.core.base.SolvedNumber.clean(x);
    }

    /** Java's {@code -x}. */
    @JSExport
    public static double javaNegate(final double x) {
        return JavaProbe.negate(x);
    }

    /** Java's {@code (int) x}. */
    @JSExport
    public static int javaToInt(final double x) {
        return JavaProbe.toInt(x);
    }

    /** Java's {@code (long) x} as hex. */
    @JSExport
    public static String javaToLongHex(final double x) {
        return JavaProbe.toLongHex(x);
    }

    /** Java's order of double operations, by form (JavaProbe.order). */
    @JSExport
    public static double javaOrder(final int form, final double a, final double b, final double c) {
        return JavaProbe.order(form, a, b, c);
    }

    /** {@code BigDecimal.valueOf(x).toString()}. */
    @JSExport
    public static String javaBigDecimalOf(final double x) {
        return JavaProbe.bigDecimalOf(x);
    }

    /** java.math.BigDecimal arithmetic and rounding to a MathContext, by op (JavaProbe.bigDecimalOp). */
    @JSExport
    public static String javaBigDecimalOp(final int op, final String a, final String b, final int digits,
        final int mode) {
        return JavaProbe.bigDecimalOp(op, a, b, digits, mode);
    }
}
