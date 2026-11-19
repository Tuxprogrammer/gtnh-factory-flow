package com.gtnhplanner.core.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.conformance.Conformance;
import com.gtnhplanner.conformance.MathCases;

/**
 * ExactMath against the JVM's StrictMath (fdlibm), bit for bit, over every special value and 400,000 drawn arguments
 * per function (MathCases), and the committed vectors the web runner replays on both targets.
 */
class ExactMathTest {

    private static final int MANY = 400_000;
    static final String VECTORS = "core-base/exact-math/exact-math-vectors.json";
    /** The committed sample: every special value, then this many drawn arguments per function. */
    private static final int SAMPLE = 400;

    static long exact(final String fn, final double x, final double y) {
        if (fn.equals("round")) return ExactMath.round(x);
        final double r;
        switch (fn) {
            case "sin": r = ExactMath.sin(x); break;
            case "cos": r = ExactMath.cos(x); break;
            case "tan": r = ExactMath.tan(x); break;
            case "asin": r = ExactMath.asin(x); break;
            case "acos": r = ExactMath.acos(x); break;
            case "atan": r = ExactMath.atan(x); break;
            case "exp": r = ExactMath.exp(x); break;
            case "log": r = ExactMath.log(x); break;
            case "log10": r = ExactMath.log10(x); break;
            case "cbrt": r = ExactMath.cbrt(x); break;
            case "sinh": r = ExactMath.sinh(x); break;
            case "cosh": r = ExactMath.cosh(x); break;
            case "tanh": r = ExactMath.tanh(x); break;
            case "expm1": r = ExactMath.expm1(x); break;
            case "log1p": r = ExactMath.log1p(x); break;
            case "rint": r = ExactMath.rint(x); break;
            case "toRadians": r = ExactMath.toRadians(x); break;
            case "toDegrees": r = ExactMath.toDegrees(x); break;
            case "ulp": r = ExactMath.ulp(x); break;
            case "pow": r = ExactMath.pow(x, y); break;
            case "atan2": r = ExactMath.atan2(x, y); break;
            case "hypot": r = ExactMath.hypot(x, y); break;
            default: throw new IllegalArgumentException(fn);
        }
        return Double.doubleToLongBits(r);
    }

    private static String hex(final long bits) {
        final String h = Long.toHexString(bits);
        return "0000000000000000".substring(h.length()) + h;
    }

    @Test
    void everyFunctionGivesStrictMathsBitsOnEveryArgument() {
        final List<String> failures = new ArrayList<>();
        long checked = 0;
        for (final String fn : MathCases.functions()) {
            int wrong = 0;
            for (final double[] a : MathCases.inputs(fn, 1, MANY)) {
                final double x = a[0], y = a.length > 1 ? a[1] : 0;
                final long want = MathCases.reference(fn, x, y), got = exact(fn, x, y);
                checked++;
                if (want != got && wrong++ < 5) {
                    failures.add(fn + "(" + hex(Double.doubleToRawLongBits(x))
                        + (a.length > 1 ? ", " + hex(Double.doubleToRawLongBits(y)) : "") + "): StrictMath "
                        + hex(want) + ", ExactMath " + hex(got));
                }
            }
            if (wrong > 5) failures.add(fn + ": " + wrong + " differences in all");
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
        assertTrue(checked > 9_000_000, "checked " + checked);
    }

    @Test
    void signsOfZeroAndJavasRoundingRules() {
        assertEquals(-0.0, ExactMath.rint(-0.4));
        assertEquals(2.0, ExactMath.rint(2.5));
        assertEquals(-2L, ExactMath.round(-2.5));
        assertEquals(0L, ExactMath.round(0.49999999999999994));
        assertEquals(0L, ExactMath.round(Double.NaN));
        assertEquals(Long.MAX_VALUE, ExactMath.round(1e300));
        assertEquals(Long.MIN_VALUE, ExactMath.round(Double.NEGATIVE_INFINITY));
        assertEquals(64.0, ExactMath.cbrt(262144));
        assertEquals(3.0, ExactMath.log10(1000));
        assertEquals(1.0, ExactMath.tanh(800));
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(ExactMath.sin(-0.0)));
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(ExactMath.atan2(-0.0, 1)));
    }

    /** The committed vectors: specials and a sample of the drawn arguments, with StrictMath's answers. */
    static String vectors() {
        final StringBuilder b = new StringBuilder();
        b.append("{\"about\": \"core-base ExactMath: [x, (y,) answer] as 64-bit patterns (doubleToLongBits; round's")
            .append(" answer is the long), answers from the JVM's StrictMath (Math for round, rint, ulp). Generator:")
            .append(" ExactMathTest.writeVectors, inputs from MathCases (seed 2).\",\n\"functions\": {");
        boolean firstFn = true;
        for (final String fn : MathCases.functions()) {
            b.append(firstFn ? "\n" : ",\n").append('"').append(fn).append("\": [");
            firstFn = false;
            boolean first = true;
            for (final double[] a : MathCases.inputs(fn, 2, SAMPLE)) {
                b.append(first ? "\n" : ",\n").append("[\"").append(hex(Double.doubleToRawLongBits(a[0])));
                if (a.length > 1) b.append("\",\"").append(hex(Double.doubleToRawLongBits(a[1])));
                b.append("\",\"").append(hex(MathCases.reference(fn, a[0], a.length > 1 ? a[1] : 0))).append("\"]");
                first = false;
            }
            b.append("\n]");
        }
        return b.append("\n}}\n").toString();
    }

    @Test
    void committedVectorsAreCurrentAndExactMathMeetsThem() throws Exception {
        final String text = new String(Files.readAllBytes(Conformance.path(VECTORS)), StandardCharsets.UTF_8);
        assertEquals(vectors(), text, VECTORS + " is out of date: run writeVectors");
        for (final String fn : MathCases.functions()) {
            for (final double[] a : MathCases.inputs(fn, 2, SAMPLE)) {
                final double y = a.length > 1 ? a[1] : 0;
                assertEquals(MathCases.reference(fn, a[0], y), exact(fn, a[0], y), fn);
            }
        }
    }

    /** Rewrites the committed vectors: run with -PwriteVectors (core-base's build.gradle.kts). */
    @Test
    void writeVectors() throws Exception {
        if (System.getProperty("gtnh.writeVectors") == null) return;
        Files.createDirectories(Conformance.path(VECTORS).getParent());
        Files.write(Conformance.path(VECTORS), vectors().getBytes(StandardCharsets.UTF_8));
    }
}
