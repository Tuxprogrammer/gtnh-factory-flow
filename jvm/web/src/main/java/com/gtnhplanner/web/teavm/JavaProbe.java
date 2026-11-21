package com.gtnhplanner.web.teavm;

/**
 * Java's arithmetic as one compilation runs it, for the conformance run: java.lang.Math and StrictMath, negation,
 * conversions to int and long, the order of double operations, BigDecimal.valueOf and BigDecimal rounding. Each load
 * module is its own TeaVM compilation, so each exports these through its own exports class ({@code BaseExports} in
 * core, {@code SolverJavaExports} in solver), and tools/web-core/exact-math.mjs holds every module to the JVM: that is
 * the proof that {@link ExactMathPlugin} and the BigDecimal override were applied to that module's code.
 */
public final class JavaProbe {

    private JavaProbe() {}

    /** {@code fn} on java.lang.Math ({@code strict} false) or StrictMath; {@code y} is read by pow, atan2, hypot. */
    public static double math(final boolean strict, final String fn, final double x, final double y) {
        switch (fn) {
            case "sin": return strict ? StrictMath.sin(x) : Math.sin(x);
            case "cos": return strict ? StrictMath.cos(x) : Math.cos(x);
            case "tan": return strict ? StrictMath.tan(x) : Math.tan(x);
            case "asin": return strict ? StrictMath.asin(x) : Math.asin(x);
            case "acos": return strict ? StrictMath.acos(x) : Math.acos(x);
            case "atan": return strict ? StrictMath.atan(x) : Math.atan(x);
            case "exp": return strict ? StrictMath.exp(x) : Math.exp(x);
            case "log": return strict ? StrictMath.log(x) : Math.log(x);
            case "log10": return strict ? StrictMath.log10(x) : Math.log10(x);
            case "cbrt": return strict ? StrictMath.cbrt(x) : Math.cbrt(x);
            case "sinh": return strict ? StrictMath.sinh(x) : Math.sinh(x);
            case "cosh": return strict ? StrictMath.cosh(x) : Math.cosh(x);
            case "tanh": return strict ? StrictMath.tanh(x) : Math.tanh(x);
            case "expm1": return strict ? StrictMath.expm1(x) : Math.expm1(x);
            case "log1p": return strict ? StrictMath.log1p(x) : Math.log1p(x);
            case "rint": return strict ? StrictMath.rint(x) : Math.rint(x);
            case "toRadians": return strict ? StrictMath.toRadians(x) : Math.toRadians(x);
            case "toDegrees": return strict ? StrictMath.toDegrees(x) : Math.toDegrees(x);
            case "ulp": return strict ? StrictMath.ulp(x) : Math.ulp(x);
            case "pow": return strict ? StrictMath.pow(x, y) : Math.pow(x, y);
            case "atan2": return strict ? StrictMath.atan2(x, y) : Math.atan2(x, y);
            case "hypot": return strict ? StrictMath.hypot(x, y) : Math.hypot(x, y);
            default: throw new IllegalArgumentException("no Math." + fn);
        }
    }

    public static String roundHex(final boolean strict, final double x) {
        return Long.toHexString(strict ? StrictMath.round(x) : Math.round(x));
    }

    public static double negate(final double x) {
        return -x;
    }

    public static int toInt(final double x) {
        return (int) x;
    }

    public static String toLongHex(final double x) {
        return Long.toHexString((long) x);
    }

    /**
     * Form 0 {@code a + (b + c)}, 1 {@code a + (b - c)}, 2 {@code a * (b * c)}, 3 {@code a * (b / c)}, 4
     * {@code (a - b) + (b * c + a)}, 5 {@code a += b + c + a} (the shape of a running total, as core-layout's
     * Optimize.total has it).
     */
    public static double order(final int form, final double a, final double b, final double c) {
        switch (form) {
            case 0: return a + (b + c);
            case 1: return a + (b - c);
            case 2: return a * (b * c);
            case 3: return a * (b / c);
            case 4: return (a - b) + (b * c + a);
            case 5: {
                double sum = a;
                sum += b + c + a;
                return sum;
            }
            default: throw new IllegalArgumentException("no form " + form);
        }
    }

    public static String bigDecimalOf(final double x) {
        return java.math.BigDecimal.valueOf(x).toString();
    }

    /**
     * Op 0 multiply, 1 add, 2 divide to a MathContext, 3 round, 4 setScale(digits, mode), 5 multiply to a MathContext,
     * 6 new BigDecimal(a, MathContext); a MathContext is (digits, RoundingMode.values()[mode]). The answer is
     * toString(), or the exception's simple class name.
     */
    public static String bigDecimalOp(final int op, final String a, final String b, final int digits, final int mode) {
        try {
            final java.math.RoundingMode rounding = java.math.RoundingMode.values()[mode];
            final java.math.MathContext mc = new java.math.MathContext(Math.max(0, digits), rounding);
            final java.math.BigDecimal x = new java.math.BigDecimal(a), y = new java.math.BigDecimal(b);
            switch (op) {
                case 0: return x.multiply(y).toString();
                case 1: return x.add(y).toString();
                case 2: return x.divide(y, mc).toString();
                case 3: return x.round(mc).toString();
                case 4: return x.setScale(digits, rounding).toString();
                case 5: return x.multiply(y, mc).toString();
                default: return new java.math.BigDecimal(a, mc).toString();
            }
        } catch (final ArithmeticException e) {
            return "ArithmeticException";
        }
    }
}
