package com.gtnhplanner.machines;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import javax.annotation.Nullable;

/**
 * How the golden tests judge a readout against a golden record: the same rules tools/web-core/machines.mjs applies to
 * the compiled core in Node, so one file judges the JVM and both browser targets alike. Objects compare the readout's
 * own keys (the goldens record more than the tests read) and a key the golden lacks reads as null; arrays compare
 * item by item; a number under a key with a tolerance passes {@code close}, any other number must be equal; text and
 * flags are equal. A number that is not finite is the text "Infinity", "-Infinity" or "NaN" on both sides.
 */
public final class GoldenCompare {

    private GoldenCompare() {}

    /** The machine goldens: counts and positions exact, every other number within a relative 1e-9. */
    public static final Set<String> MACHINE_EXACT = Set
        .of("overclockSteps", "perfectOverclockSteps", "voltageOrdinal", "minimumIndex");

    /** The crop goldens' fractional fields; every other number there is a count. */
    public static final Set<String> CROP_NEAR = Set.of(
        "ticks",
        "rounds",
        "speed",
        "eutPerCrop",
        "farmEut",
        "managerEu",
        "durationMultiplier",
        "draw",
        "outputMultipliers");

    public static boolean closeMachine(final double w, final double g) {
        if (Double.isNaN(w) || Double.isNaN(g)) return Double.isNaN(w) && Double.isNaN(g);
        if (Double.isInfinite(w) || Double.isInfinite(g)) return w == g;
        return w == g || Math.abs(w - g) <= 1e-9 * Math.max(Math.abs(w), Math.abs(g));
    }

    public static boolean closeCrop(final double w, final double g) {
        if (Double.isNaN(w) || Double.isNaN(g)) return Double.isNaN(w) && Double.isNaN(g);
        if (Double.isInfinite(w) && Double.isInfinite(g)) return true;
        return Math.abs(w - g) <= 1e-9 * Math.max(1, Math.abs(w));
    }

    /** The first difference, or null. */
    @Nullable
    public static String difference(@Nullable final Object want, @Nullable final Object got, final String where,
        final Predicate<String> near, final BiPredicate<Double, Double> close) {
        if (want == null || got == null)
            return want == null && got == null ? null : where + ": " + got + " for " + want;
        if (got instanceof List || want instanceof List) {
            if (!(got instanceof List) || !(want instanceof List)) return where + ": " + got + " for " + want;
            final List<?> w = (List<?>) want, g = (List<?>) got;
            if (w.size() != g.size()) return where + ": " + g.size() + " items for " + w.size();
            for (int i = 0; i < g.size(); i++) {
                final String d = difference(w.get(i), g.get(i), where + "[" + i + "]", near, close);
                if (d != null) return d;
            }
            return null;
        }
        if (got instanceof Map) {
            if (!(want instanceof Map)) return where + ": " + got + " for " + want;
            final Map<?, ?> w = (Map<?, ?>) want;
            for (final Map.Entry<?, ?> e : ((Map<?, ?>) got).entrySet()) {
                final String d = difference(
                    w.get(e.getKey()),
                    e.getValue(),
                    where.isEmpty() ? String.valueOf(e.getKey()) : where + "." + e.getKey(),
                    near,
                    close);
                if (d != null) return d;
            }
            return null;
        }
        final Object w = number(want), g = number(got);
        if (w instanceof Double || g instanceof Double) return numberDifference(w, g, where, near, close);
        return want.equals(got) ? null : where + ": " + got + " for " + want;
    }

    private static Object number(final Object x) {
        if (x instanceof String) switch ((String) x) {
            case "Infinity":
                return Double.POSITIVE_INFINITY;
            case "-Infinity":
                return Double.NEGATIVE_INFINITY;
            case "NaN":
                return Double.NaN;
            default:
                return x;
        }
        return x instanceof Number ? (Object) ((Number) x).doubleValue() : x;
    }

    private static String numberDifference(final Object w, final Object g, final String where,
        final Predicate<String> near, final BiPredicate<Double, Double> close) {
        if (!(w instanceof Double) || !(g instanceof Double)) return where + ": " + g + " for " + w;
        final String key = where.replaceAll("\\[\\d+\\]", "")
            .replaceAll("^.*\\.", "");
        final double wd = (Double) w, gd = (Double) g;
        final boolean ok = near.test(key) ? close.test(wd, gd) : wd == gd || Double.isNaN(wd) && Double.isNaN(gd);
        return ok ? null : where + ": " + gd + " for " + wd;
    }

    /**
     * A machine readout against the golden's "expected": the readout's keys, and the two the website records only when
     * it has them (a power report, steam), and the tier controls only when the website recorded them.
     */
    @Nullable
    public static String machine(final Map<String, Object> want, final Map<String, Object> got) {
        if (want.containsKey("powerState") && !got.containsKey("powerState"))
            return "powerState: website has a report, port none";
        if (want.containsKey("steam") != got.containsKey("steam")) return "steam: website " + want.get("steam");
        final Map<String, Object> g = new LinkedHashMap<>(got);
        if (!want.containsKey("controls")) g.remove("controls");
        return difference(want, g, "", k -> !MACHINE_EXACT.contains(k), GoldenCompare::closeMachine);
    }

    /** A crop readout against the golden's "expected"; the formulas must end on the power line. */
    @Nullable
    public static String crop(final Map<String, Object> want, final Map<String, Object> got) {
        if (!Boolean.TRUE.equals(got.get("formulasEndOnPower"))) return "formulas do not end on power";
        final Map<String, Object> numbers = new LinkedHashMap<>(got);
        numbers.remove("formulasEndOnPower");
        return difference(want, numbers, "", CROP_NEAR::contains, GoldenCompare::closeCrop);
    }

    /**
     * A bee readout against the golden record: the housing, and unless the card has no bees to count on (skip) the
     * product and specialty multipliers and the duration within a relative 1e-6 of at least 1 (floats on the game's
     * side, doubles on the website's).
     */
    @Nullable
    public static String bee(final Map<String, Object> want, final Map<String, Object> got) {
        if (!want.get("housing").equals(got.get("housing"))) return "housing " + got.get("housing");
        if (Boolean.TRUE.equals(got.get("skip"))) return null;
        for (final String k : List.of("product", "specialty", "duration")) {
            if (!(got.get(k) instanceof Number)) return k + ": model " + got.get(k) + ", website " + want.get(k);
            final double w = ((Number) want.get(k)).doubleValue(), g = ((Number) got.get(k)).doubleValue();
            if (Math.abs(w - g) > 1e-6 * Math.max(1, Math.abs(w))) return k + ": model " + g + ", website " + w;
        }
        return null;
    }
}
