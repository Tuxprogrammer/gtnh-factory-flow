package com.gtnhplanner.conformance;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Arguments for core-base's ExactMath and the JVM's answer for each: the inputs of ExactMathTest, of the committed
 * vectors {@code core-base/exact-math/exact-math-vectors.json} and of the web build's random cases. Each function gets
 * its special values first (zeros of both signs, the smallest subnormal, the largest double, infinities, NaN, and the
 * places where its algorithm changes branch, with their neighbours), then a deterministic random mix: any 64 bits, any
 * magnitude from 2^-1074 to 2^1023, the ranges where the function is not flat, exact powers, whole numbers and halves,
 * perfect cubes, multiples of pi/2 far out. The reference answer is {@link StrictMath} (fdlibm by specification), and
 * for round, rint and ulp the JVM's {@code Math} (exact by specification).
 */
public final class MathCases {

    private MathCases() {}

    /** Every function ExactMath has, with its argument count. */
    public static final List<String> UNARY = List.of("sin", "cos", "tan", "asin", "acos", "atan", "exp", "log", "log10",
        "cbrt", "sinh", "cosh", "tanh", "expm1", "log1p", "rint", "round", "toRadians", "toDegrees", "ulp");
    public static final List<String> BINARY = List.of("pow", "atan2", "hypot");

    public static List<String> functions() {
        final List<String> all = new ArrayList<>(UNARY);
        all.addAll(BINARY);
        return all;
    }

    public static int arity(final String fn) {
        return BINARY.contains(fn) ? 2 : 1;
    }

    /**
     * The JVM's answer as a 64-bit pattern: {@code doubleToLongBits} (one NaN), or the long itself for round.
     */
    public static long reference(final String fn, final double x, final double y) {
        if (fn.equals("round")) return Math.round(x);
        return Double.doubleToLongBits(referenceDouble(fn, x, y));
    }

    private static double referenceDouble(final String fn, final double x, final double y) {
        switch (fn) {
            case "sin": return StrictMath.sin(x);
            case "cos": return StrictMath.cos(x);
            case "tan": return StrictMath.tan(x);
            case "asin": return StrictMath.asin(x);
            case "acos": return StrictMath.acos(x);
            case "atan": return StrictMath.atan(x);
            case "exp": return StrictMath.exp(x);
            case "log": return StrictMath.log(x);
            case "log10": return StrictMath.log10(x);
            case "cbrt": return StrictMath.cbrt(x);
            case "sinh": return StrictMath.sinh(x);
            case "cosh": return StrictMath.cosh(x);
            case "tanh": return StrictMath.tanh(x);
            case "expm1": return StrictMath.expm1(x);
            case "log1p": return StrictMath.log1p(x);
            case "rint": return StrictMath.rint(x);
            case "toRadians": return StrictMath.toRadians(x);
            case "toDegrees": return StrictMath.toDegrees(x);
            case "ulp": return Math.ulp(x);
            case "pow": return StrictMath.pow(x, y);
            case "atan2": return StrictMath.atan2(x, y);
            case "hypot": return StrictMath.hypot(x, y);
            default: throw new IllegalArgumentException(fn);
        }
    }

    /** The arguments for fn: specials first, then {@code random} drawn ones; each is {x} or {x, y}. */
    public static List<double[]> inputs(final String fn, final long seed, final int random) {
        final List<double[]> out = new ArrayList<>();
        final boolean binary = arity(fn) == 2;
        final List<Double> special = specials(fn);
        if (binary) {
            for (final double a : special) for (final double b : special) out.add(new double[] {a, b});
        } else {
            for (final double a : special) out.add(new double[] {a});
        }
        final Random r = new Random(seed * 31 + fn.hashCode());
        for (int i = 0; i < random; i++) {
            out.add(binary ? pair(fn, r) : new double[] {one(fn, r)});
        }
        return out;
    }

    // ---- specials

    private static List<Double> specials(final String fn) {
        final List<Double> s = new ArrayList<>();
        for (final double v : new double[] {0.0, Double.MIN_VALUE, Double.MIN_NORMAL, Double.MAX_VALUE,
            Double.POSITIVE_INFINITY, 1.0, 0.5, 2.0, 3.0, 10.0, Math.PI, Math.E, 1e-300, 1e300}) {
            s.add(v);
            s.add(-v);
        }
        s.add(Double.NaN);
        s.add(Math.nextDown(Double.MIN_NORMAL));
        s.add(Math.nextUp(1.0));
        s.add(Math.nextDown(1.0));
        s.add(-Math.nextUp(1.0));
        s.add(-Math.nextDown(1.0));
        if (arity(fn) == 2) {
            s.add(0.25);
            s.add(-0.25);
            s.add(1.0 / 3);
            return s;
        }
        // The high words at which fdlibm's functions change branch, as doubles, with both neighbours.
        final int[] edges = edges(fn);
        for (final int hw : edges) {
            final double v = Double.longBitsToDouble((long) hw << 32);
            for (final double w : new double[] {v, Math.nextUp(v), Math.nextDown(v)}) {
                s.add(w);
                s.add(-w);
            }
        }
        return s;
    }

    private static int[] edges(final String fn) {
        switch (fn) {
            case "sin":
            case "cos":
            case "tan":
                return new int[] {0x3e300000, 0x3e400000, 0x3FD33333, 0x3fe90000, 0x3FE59428, 0x3fe921fb, 0x3ff921fb,
                    0x4002d97c, 0x400921fb, 0x413921fb, 0x41500000, 0x7fefffff};
            case "asin":
            case "acos":
                return new int[] {0x3c600000, 0x3e400000, 0x3fe00000, 0x3FEF3333, 0x3ff00000};
            case "atan":
                return new int[] {0x3e200000, 0x3fdc0000, 0x3fe60000, 0x3ff30000, 0x40038000, 0x44100000};
            case "exp":
            case "expm1":
            case "sinh":
            case "cosh":
            case "tanh":
                return new int[] {0x3c800000, 0x3c900000, 0x3e300000, 0x3fd62e42, 0x3fd62e43, 0x3ff00000, 0x3FF0A2B2,
                    0x40360000, 0x4043687A, 0x40862E42, 0x408633CE, 0x40874910};
            case "log":
            case "log10":
            case "log1p":
                return new int[] {0x00100000, 0x3c900000, 0x3e200000, 0x3fd2bec3, 0x3FDA827A, 0x3fe6a09e, 0x3ff6a09e,
                    0x43400000};
            default:
                return new int[] {0x00100000, 0x3ff00000, 0x43300000, 0x43400000, 0x43e00000};
        }
    }

    // ---- random

    /** Any magnitude from the smallest subnormal to the largest double, log-uniform, random sign. */
    private static double anyMagnitude(final Random r) {
        final double v = Math.scalb(1.0 + r.nextDouble(), r.nextInt(2098) - 1075);
        return r.nextBoolean() ? v : -v;
    }

    /** Log-uniform magnitude between 2^lo and 2^hi, random sign. */
    private static double between(final Random r, final int lo, final int hi) {
        final double v = Math.scalb(1.0 + r.nextDouble(), lo + r.nextInt(hi - lo + 1));
        return r.nextBoolean() ? v : -v;
    }

    private static double uniform(final Random r, final double half) {
        return (2 * r.nextDouble() - 1) * half;
    }

    private static double near(final Random r, final double v) {
        double w = v;
        final int steps = r.nextInt(5);
        for (int i = 0; i < steps; i++) w = r.nextBoolean() ? Math.nextUp(w) : Math.nextDown(w);
        return w;
    }

    private static double one(final String fn, final Random r) {
        final int pick = r.nextInt(10);
        if (pick == 0) return Double.longBitsToDouble(r.nextLong());
        if (pick == 1) return anyMagnitude(r);
        if (pick == 2) return between(r, -60, 60);
        switch (fn) {
            case "sin":
            case "cos":
            case "tan":
                if (pick == 3) return near(r, (r.nextInt(2_000_001) - 1_000_000) * (Math.PI / 2));
                if (pick == 4) return between(r, 20, 1023);
                if (pick == 5) return uniform(r, 10);
                if (pick == 6) return near(r, r.nextInt(64) * (Math.PI / 4) * (r.nextBoolean() ? 1 : -1));
                return uniform(r, 1e6);
            case "asin":
            case "acos":
                if (pick <= 6) return uniform(r, 1);
                return near(r, r.nextBoolean() ? 1.0 : r.nextBoolean() ? -1.0 : 0.5);
            case "atan":
                return pick <= 5 ? uniform(r, 4) : between(r, -30, 70);
            case "exp":
            case "expm1":
            case "sinh":
            case "cosh":
            case "tanh":
                if (pick <= 5) return uniform(r, 760);
                if (pick == 6) return uniform(r, 2);
                if (pick == 7) return near(r, r.nextBoolean() ? 709.782712893384 : -745.1332191019411);
                return near(r, (r.nextInt(2001) - 1000) * 0.6931471805599453);
            case "log":
            case "log10":
                if (pick <= 4) return near(r, Double.parseDouble("1e" + (r.nextInt(632) - 323)));
                if (pick == 5) return near(r, 1.0) + uniform(r, 1e-6);
                if (pick == 6) return Math.abs(uniform(r, 1000));
                return Math.abs(between(r, -1074, 1023));
            case "log1p":
                if (pick <= 5) return uniform(r, 1);
                if (pick == 6) return near(r, -1.0);
                return between(r, -70, 10);
            case "cbrt": {
                final long k = r.nextInt(1 << 17) * (r.nextBoolean() ? 1L : -1L);
                if (pick <= 5) return near(r, (double) (k * k * k));
                if (pick == 6) return (double) (k * k * k) / 8;
                return anyMagnitude(r);
            }
            case "rint":
            case "round": {
                final long k = r.nextLong() >> r.nextInt(64);
                if (pick == 3) return near(r, k + 0.5);
                if (pick == 4) return (r.nextInt(2_000_001) - 1_000_000) + 0.5;
                if (pick == 5) return near(r, Math.scalb(1.0, 51 + r.nextInt(14)) * (r.nextBoolean() ? 1 : -1));
                if (pick == 6) return near(r, 0.5) * (r.nextBoolean() ? 1 : -1);
                if (pick == 7) return (double) k;
                return uniform(r, 1e4);
            }
            default:
                return pick <= 6 ? uniform(r, 1e4) : anyMagnitude(r);
        }
    }

    private static double[] pair(final String fn, final Random r) {
        final int pick = r.nextInt(12);
        if (pick == 0) return new double[] {Double.longBitsToDouble(r.nextLong()), Double.longBitsToDouble(r.nextLong())};
        if (pick == 1) return new double[] {anyMagnitude(r), anyMagnitude(r)};
        if (fn.equals("pow")) {
            switch (pick) {
                case 2:
                    return new double[] {Math.abs(between(r, -40, 40)), uniform(r, 20)};
                case 3:
                    return new double[] {between(r, -40, 40), r.nextInt(2201) - 1100};
                case 4:
                    return new double[] {r.nextBoolean() ? 10.0 : r.nextBoolean() ? 2.0 : 0.1, r.nextInt(700) - 350};
                case 5: {
                    final double x = 1.0 + Math.scalb(uniform(r, 1), -r.nextInt(53));
                    return new double[] {x, between(r, 20, 70)};
                }
                case 6:
                    return new double[] {Math.abs(uniform(r, 1e4)), uniform(r, 3)};
                case 7:
                    return new double[] {-Math.abs(between(r, -20, 20)), (double) (r.nextLong() >> r.nextInt(64))};
                case 8:
                    return new double[] {Math.abs(uniform(r, 2)), r.nextBoolean() ? 0.52 : r.nextBoolean() ? 1.0 / 3 : 12.5};
                case 9:
                    return new double[] {near(r, 1.0), uniform(r, 1e6)};
                default:
                    return new double[] {Math.abs(between(r, -1074, 1023)), uniform(r, 2)};
            }
        }
        switch (pick) {
            case 2:
            case 3:
            case 4:
                return new double[] {uniform(r, 1e3), uniform(r, 1e3)};
            case 5:
                return new double[] {between(r, -30, 30), between(r, -30, 30)};
            case 6: {
                final double a = uniform(r, 100);
                return new double[] {a, r.nextBoolean() ? a : -a};
            }
            case 7:
                return new double[] {between(r, -1074, 1023), between(r, -100, 100)};
            case 8:
                return new double[] {(double) r.nextInt(2001) - 1000, (double) r.nextInt(2001) - 1000};
            default:
                return new double[] {uniform(r, 1), uniform(r, 1)};
        }
    }
}
