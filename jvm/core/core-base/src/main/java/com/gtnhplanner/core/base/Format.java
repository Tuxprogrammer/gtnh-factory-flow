package com.gtnhplanner.core.base;

/**
 * The numbers a player reads, spelled once for the mod and the website. Each method is a translation of one of the
 * website's formatters (src/lib/format/numbers.ts, rate-unit.ts, components/flow/clean/clean-model.ts) and returns the
 * same characters; conformance/format holds the website's answers for a long value list and every host is held to them.
 * <p>
 * Everything goes through {@link NumberText}, so the digits do not depend on the host's floating-point formatter: a
 * half rounds as JavaScript's {@code toFixed} rounds it, from the number's exact value (1.005 shows as "1", not
 * "1.01"). Non-finite numbers print what the website prints ("unbounded", "NaN", "Infinity"), they do not throw.
 * <p>
 * Where the website's own code is odd (a stable figure of -0.4 reads "-0", 999.6 reads "1000") this copies it: the
 * test is the website's output, and the odd cases are in the value list so a change to either side shows.
 */
public final class Format {

    private Format() {}

    /** What the board shows for a number that is not finite. */
    public static final String UNBOUNDED = "unbounded";

    /** The SI ladder GregTech uses for EU. Past exa nothing in the game reaches. */
    private static final String[] SUFFIXES = { "", "k", "M", "G", "T", "P", "E" };

    /** 0.1 down to 1e-10, as doubles: a value below the k-th of them has k leading zeros after the point. */
    private static final double[] TENTHS = { 1e-1, 1e-2, 1e-3, 1e-4, 1e-5, 1e-6, 1e-7, 1e-8, 1e-9, 1e-10 };

    private static boolean finite(final double v) {
        return v == v && v != Double.POSITIVE_INFINITY && v != Double.NEGATIVE_INFINITY;
    }

    // --- the compact ladder ---

    /**
     * Late-game numbers at a width a card cell can hold: 2,147,483,648 is "2.15G", 1,440,000 is "1.44M". Exact zero
     * is "0"; a small real rate never reads as zero (0.004 stays "0.004"), so values under 1 keep two significant
     * digits down to twelve places; from 1 two decimals (one from 100) before the suffix, trailing zeros dropped.
     * The website's {@code formatCompact}.
     */
    public static String compact(final double value) {
        if (!finite(value)) return UNBOUNDED;
        if (value == 0) return "0";
        final String sign = value < 0 ? "-" : "";
        double abs = Math.abs(value);
        if (abs < 1) {
            int leadingZeros = 0;
            while (leadingZeros < TENTHS.length && abs < TENTHS[leadingZeros]) leadingZeros++;
            // The website counts ceil(-log10) - 1 and caps the places at 12; below 1e-10 that cap is 12.
            return sign + trimZeros(NumberText.fixed(abs, Math.min(12, leadingZeros + 2)));
        }
        int tier = 0;
        while (abs >= 1000 && tier < SUFFIXES.length - 1) {
            abs /= 1000;
            tier++;
        }
        int decimals = abs >= 100 ? 1 : 2;
        String text = NumberText.fixed(abs, decimals);
        // 999.96 rounds to "1000.0": carry it to the next suffix instead.
        if (wholeDigits(text) >= 4 && tier < SUFFIXES.length - 1) {
            abs /= 1000;
            tier++;
            decimals = 2;
            text = NumberText.fixed(abs, decimals);
        }
        return sign + trimZeros(text) + SUFFIXES[tier];
    }

    /** How many digits are before the point of a plain decimal text. */
    private static int wholeDigits(final String text) {
        final int dot = text.indexOf('.');
        return dot < 0 ? text.length() : dot;
    }

    /**
     * {@link #compact} for a number in motion: fixed decimals per magnitude with the zeros kept, whole numbers below a
     * thousand, so an easing figure keeps one width. The website's {@code formatCompactStable}.
     */
    public static String compactStable(final double value) {
        if (!finite(value)) return UNBOUNDED;
        final String sign = value < 0 ? "-" : "";
        double abs = Math.abs(value);
        if (abs < 1000) return sign + NumberText.of(JsMath.round(abs));
        int tier = 0;
        while (abs >= 1000 && tier < SUFFIXES.length - 1) {
            abs /= 1000;
            tier++;
        }
        return sign + NumberText.fixed(abs, abs >= 100 ? 1 : 2) + SUFFIXES[tier];
    }

    /**
     * At most three significant figures, then k, M, G...: for a number that must fit a small space whole, such as a
     * zoomed-out drawer ("167", "33.3", "33.3k"). Below 1 it is {@link #compact}. Only the mod draws this; the website
     * has no figure of the kind (its {@link #compactStable} keeps a width while a number moves, a different job).
     */
    public static String brief(final double value) {
        if (!finite(value)) return UNBOUNDED;
        if (value == 0) return "0";
        double v = Math.abs(value);
        if (v < 1) return compact(value);
        int tier = 0;
        // 999.5 and up rounds to 1000, which reads as the next suffix.
        while (v >= 999.5 && tier < SUFFIXES.length - 1) {
            v /= 1000;
            tier++;
        }
        return (value < 0 ? "-" : "") + trimZeros(NumberText.fixed(v, v < 10 ? 2 : v < 100 ? 1 : 0)) + SUFFIXES[tier];
    }

    /** Power in the displayed unit: a real trickle stays distinct from zero. The website's {@code formatPowerValue}. */
    public static String powerValue(final double value) {
        return powerValue(value, false);
    }

    /** As {@link #powerValue(double)}; {@code stable} keeps fractional amps from rounding to zero while a figure moves. */
    public static String powerValue(final double value, final boolean stable) {
        if (value != 0 && Math.abs(value) < 0.01) return value < 0 ? ">-0.01" : "<0.01";
        return stable && Math.abs(value) >= 1 ? compactStable(value) : compact(value);
    }

    /** The website's {@code formatRate}: whole from 100, one place from 10, else {@code digits}, with thousands commas. */
    public static String rate(final double value, final int digits) {
        if (!finite(value)) return UNBOUNDED;
        final double abs = Math.abs(value);
        if (abs >= 100) return thousands(NumberText.fixed(value, 0));
        if (abs >= 10) return thousands(NumberText.fixed(value, 1));
        return thousands(NumberText.fixed(value, digits));
    }

    // --- text helpers ---

    /** {@code formatNumberWithThousands} of a number: comma thousands, dot decimal, as JavaScript spells the number. */
    public static String thousands(final double value) {
        return thousands(NumberText.of(value));
    }

    /**
     * The website's {@code formatNumberWithThousands}: a leading minus kept, the part before the first point grouped
     * in threes with commas, the part after it (up to a second point) kept as it is.
     */
    public static String thousands(final String text) {
        final String sign = text.startsWith("-") ? "-" : "";
        final String unsigned = sign.isEmpty() ? text : text.substring(1);
        final int dot = unsigned.indexOf('.');
        final String integer = dot < 0 ? unsigned : unsigned.substring(0, dot);
        String fraction = null;
        if (dot >= 0) {
            final int next = unsigned.indexOf('.', dot + 1);
            fraction = next < 0 ? unsigned.substring(dot + 1) : unsigned.substring(dot + 1, next);
        }
        return sign + group(integer) + (fraction != null ? "." + fraction : "");
    }

    private static boolean isWord(final char c) {
        return c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '_';
    }

    private static boolean isDigit(final char c) {
        return c >= '0' && c <= '9';
    }

    /** {@code s.replace(/\B(?=(\d{3})+(?!\d))/g, ",")}. */
    private static String group(final String s) {
        final StringBuilder out = new StringBuilder(s.length() + s.length() / 3);
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && isWord(s.charAt(i - 1)) == isWord(s.charAt(i)) && isDigit(s.charAt(i))) {
                int run = 0;
                while (i + run < s.length() && isDigit(s.charAt(i + run))) run++;
                if (run % 3 == 0) out.append(',');
            }
            out.append(s.charAt(i));
        }
        return out.toString();
    }

    /**
     * The website's {@code trimTrailingDecimalZeros}: after the last point, trailing zeros go, and the point with them
     * when only zeros were there ("1.50" is "1.5", "2.00" is "2"); a text with no point, or no digits after it, stays.
     */
    public static String trimZeros(final String text) {
        final int dot = text.lastIndexOf('.');
        if (dot < 0 || dot == text.length() - 1) return text;
        for (int i = dot + 1; i < text.length(); i++) if (!isDigit(text.charAt(i))) return text;
        int end = text.length();
        while (end > dot + 1 && text.charAt(end - 1) == '0') end--;
        return end == dot + 1 ? text.substring(0, dot) : text.substring(0, end);
    }

    /** {@code text.replace(/\.?0+$/, "")}: trailing zeros, and the point before them, off the end. */
    private static String stripEndZeros(final String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '0') end--;
        if (end == text.length()) return text;
        if (end > 0 && text.charAt(end - 1) == '.') end--;
        return text.substring(0, end);
    }

    /**
     * A number as stored: no grouping, no trailing zeros, no float noise past ten places ("0.5", "12", never "-0").
     * Not a website formatter: the website keeps settings as numbers.
     */
    public static String plain(final double value) {
        final String t = trimZeros(NumberText.fixed(value, 10));
        return t.equals("-0") ? "0" : t;
    }

    // --- counts and shares ---

    /**
     * A solved machine count on a card: "0"; "&lt;0.001"; three decimals under 1, two under 10, one under 100, zeros
     * dropped; from 100 a whole number rounded up (a part of a machine cannot be built at that scale), with a
     * millionth of slack so 100.0000001 is 100. The website's {@code formatSolvedMachines}.
     */
    public static String solvedMachines(final double value) {
        if (value <= 0.0000005) return "0";
        if (value < 0.0005) return "<0.001";
        if (value >= 100) return NumberText.of(Math.ceil(value - 0.000001));
        final int decimals = value < 1 ? 3 : value < 10 ? 2 : 1;
        return stripEndZeros(NumberText.fixed(value, decimals));
    }

    /**
     * A count in the machine list: fractions kept to six places at any size, never rounded up to a build. The
     * website's {@code formatMachineListCount}, which is {@code toLocaleString("en-US")} at six places: the digits
     * JavaScript prints for the number, half rounded up, with commas.
     */
    public static String machineListCount(final double count) {
        if (count > 0 && count < 0.000001) return "<0.000001";
        return localeNumber(count, 6);
    }

    /** {@code toLocaleString("en-US", {maximumFractionDigits: maxFraction})}. */
    static String localeNumber(final double x, final int maxFraction) {
        if (x != x) return "NaN";
        final boolean negative = x < 0 || x == 0 && 1 / x < 0;
        final String sign = negative ? "-" : "";
        if (x == Double.POSITIVE_INFINITY || x == Double.NEGATIVE_INFINITY) return sign + "∞";
        // The shortest digits that read back as the number (ICU rounds those, not the binary value), in plain form.
        final String s = NumberText.of(Math.abs(x));
        String mantissa = s;
        int exponent = 0;
        final int e = s.indexOf('e');
        if (e >= 0) {
            mantissa = s.substring(0, e);
            exponent = Integer.parseInt(s.substring(e + 1)
                .replace("+", ""));
        }
        final int point = mantissa.indexOf('.');
        final String digits = point < 0 ? mantissa : mantissa.substring(0, point) + mantissa.substring(point + 1);
        final int at = (point < 0 ? mantissa.length() : point) + exponent;
        final StringBuilder whole = new StringBuilder();
        final StringBuilder fraction = new StringBuilder();
        if (at <= 0) {
            whole.append('0');
            for (int i = 0; i < -at; i++) fraction.append('0');
            fraction.append(digits);
        } else if (at >= digits.length()) {
            whole.append(digits);
            for (int i = digits.length(); i < at; i++) whole.append('0');
        } else {
            whole.append(digits, 0, at);
            fraction.append(digits, at, digits.length());
        }
        if (fraction.length() > maxFraction) {
            final boolean up = fraction.charAt(maxFraction) >= '5';
            fraction.setLength(maxFraction);
            if (up) {
                final char[] all = (whole.toString() + fraction).toCharArray();
                int i = all.length - 1;
                while (i >= 0 && all[i] == '9') all[i--] = '0';
                final String bumped = i < 0 ? "1" + new String(all) : bump(all, i);
                whole.setLength(0);
                whole.append(bumped, 0, bumped.length() - fraction.length());
                fraction.setLength(0);
                fraction.append(bumped, whole.length(), bumped.length());
            }
        }
        int end = fraction.length();
        while (end > 0 && fraction.charAt(end - 1) == '0') end--;
        final String grouped = group(whole.toString());
        return sign + grouped + (end > 0 ? "." + fraction.substring(0, end) : "");
    }

    private static String bump(final char[] all, final int at) {
        all[at]++;
        return new String(all);
    }

    /**
     * A ratio drawer branch's share of one as a percent: "<0.01%" and ">99.99%" at the ends, otherwise at most two
     * decimals, zeros dropped. The website's {@code formatRatioShare}.
     */
    public static String ratioShare(final double share) {
        if (share > 0 && share < 0.0001) return "<0.01%";
        if (share < 1 && share > 0.9999) return ">99.99%";
        return NumberText.of(NumberText.parse(NumberText.fixed(share * 100, 2))) + "%";
    }

    // --- rates and energy ---

    /** The unit suffix of a rate of this kind: "/s" for items, " L/s" for fluids, " EU/t" for power. */
    public static String rateSuffix(final RateUnit unit, final String kind) {
        if ("power".equals(kind)) return " EU/t";
        return "fluid".equals(kind) ? " L/" + unit.per : "/" + unit.per;
    }

    /** Multiply a per-second figure by this before display: power is always per tick, whatever the unit. */
    public static double rateMultiplier(final RateUnit unit, final String kind) {
        return "power".equals(kind) ? 1.0 / 20.0 : unit.perSecond;
    }

    /** A per-second rate's number, in the unit and kind; the website's {@code formatSlotRateBare}. */
    public static String slotRateBare(final double perSecond, final RateUnit unit, final String kind) {
        final double shown = perSecond * rateMultiplier(unit, kind);
        return "power".equals(kind) ? powerValue(shown) : compact(shown);
    }

    /** A per-second rate with its unit: "900/hr", "180k L/hr", "6.93k EU/t". The website's {@code formatSlotRate}. */
    public static String slotRate(final double perSecond, final RateUnit unit, final String kind) {
        return slotRateBare(perSecond, unit, kind) + rateSuffix(unit, kind);
    }

    /**
     * EU spent per unit made: a machine's EU/t over one output's per-second flow. Scale-free (machine count and
     * parallels cancel) but not tier-free. NaN when nothing flows, so a row keeps its rate instead of reading
     * infinity. The website's {@code energyPerUnit} (undefined there).
     */
    public static double energyPerUnit(final double euPerTick, final double perSecond) {
        if (!finite(euPerTick) || !finite(perSecond) || perSecond <= 1e-12) return Double.NaN;
        return Math.max(0, euPerTick) * 20 / perSecond;
    }

    /** The unit an energy reading wears in plain EU: " EU/L" for a fluid, " EU/Item" otherwise. */
    public static String energyPerUnitSuffix(final String kind) {
        return "fluid".equals(kind) ? " EU/L" : " EU/Item";
    }
}
