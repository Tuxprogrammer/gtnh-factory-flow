package com.gtnhplanner.core.view.rail;

import java.util.Locale;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.JsMath;
import com.gtnhplanner.core.base.NumberText;
import com.gtnhplanner.machines.web.Tiers;

/**
 * The overview rail's arithmetic: what a rate box accepts, how a spare's shares move, the wheel's step, the machine
 * count a card is billed at its peak, and the power dial. Pure and plain: the website's {@code rail-math.ts} and the
 * dial of {@code rate-unit.ts}, with the vectors in {@code conformance/view/rail/math.json} run by both.
 */
public final class RailMath {

    /** A share below this reads as none. */
    public static final double SHARE_SHOWN = 0.004;

    private RailMath() {}

    // region Rate box

    /**
     * What a rate box accepts: {@code 2.5k}, {@code 1M}, {@code 1/3}, {@code 3,200}, {@code .5}. k, m, g are thousand,
     * million, billion in either case. A fraction is a plain division of two such numbers. Anything else is not a
     * number (NaN): the caller keeps what it had instead of guessing.
     */
    public static double parseRateInput(final String text) {
        String cleaned = text.trim()
            .toLowerCase(Locale.ROOT)
            .replace(",", "");
        if (cleaned.startsWith("−")) cleaned = "-" + cleaned.substring(1);
        if (cleaned.isEmpty()) return Double.NaN;
        final int slash = cleaned.indexOf('/');
        if (slash >= 0) {
            final double top = parsePlain(cleaned.substring(0, slash));
            final double bottom = parsePlain(cleaned.substring(slash + 1));
            if (Double.isNaN(top) || Double.isNaN(bottom) || bottom == 0) return Double.NaN;
            final double value = top / bottom;
            return Double.isFinite(value) ? value : Double.NaN;
        }
        return parsePlain(cleaned);
    }

    /** {@code ^([+-]?[0-9]*\.?[0-9]+)\s*([kmg]?)$} on the trimmed text. */
    private static double parsePlain(final String raw) {
        final String s = raw.trim();
        int i = 0;
        final int n = s.length();
        if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
        final int whole = i;
        while (i < n && isDigit(s.charAt(i))) i++;
        final int wholeDigits = i - whole;
        if (i < n && s.charAt(i) == '.') {
            i++;
            final int frac = i;
            while (i < n && isDigit(s.charAt(i))) i++;
            if (i == frac) return Double.NaN;
        } else if (wholeDigits == 0) {
            return Double.NaN;
        }
        final String number = s.substring(0, i);
        while (i < n && isSpace(s.charAt(i))) i++;
        double multiplier = 1;
        if (i < n) {
            final char c = s.charAt(i);
            if (c == 'k') multiplier = 1e3;
            else if (c == 'm') multiplier = 1e6;
            else if (c == 'g') multiplier = 1e9;
            else return Double.NaN;
            i++;
            if (i != n) return Double.NaN;
        }
        final double value = NumberText.parse(number) * multiplier;
        return Double.isFinite(value) ? value : Double.NaN;
    }

    private static boolean isDigit(final char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isSpace(final char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\u000b' || c == ' ';
    }

    // endregion

    // region Spare shares

    /** {@code shares} with place {@code index} given {@code step} more of the leftover, the others in proportion. */
    public static double[] nudged(final double[] shares, final int index, final double step) {
        final double[] out = shares.clone();
        final double target = Math.max(0, Math.min(1, out[index] + step));
        final double rest = 1 - out[index];
        final double newRest = 1 - target;
        for (int j = 0; j < out.length; j++) {
            if (j == index) continue;
            out[j] = rest > 1e-9 ? out[j] * newRest / rest : newRest / (out.length - 1);
        }
        out[index] = target;
        return out;
    }

    /** Every place an equal share. */
    public static double[] evenShares(final int count) {
        final double[] out = new double[count];
        for (int i = 0; i < count; i++) out[i] = 1.0 / count;
        return out;
    }

    /** All of it at place {@code index}. */
    public static double[] allAt(final int count, final int index) {
        final double[] out = new double[count];
        if (index >= 0 && index < count) out[index] = 1;
        return out;
    }

    /** A bar press at {@code x} along a track of {@code width} starting at {@code left}: the share, in fiftieths. */
    public static double shareAtX(final double x, final double left, final double width) {
        if (width <= 0) return 0;
        return Math.max(0, Math.min(1, JsMath.round((x - left) / width * 50) / 50));
    }

    public static boolean sameShares(final double[] a, final double[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (!(Math.abs(a[i] - b[i]) < 1e-9)) return false;
        return true;
    }

    /** The wheel's step on a rate in display units: 1, Ctrl 10, Shift 100. */
    public static int wheelStep(final boolean ctrl, final boolean shift) {
        return shift ? 100 : ctrl ? 10 : 1;
    }

    /** One wheel notch on a displayed rate: rounded to a whole, then stepped, never below zero. */
    public static double steppedRate(final double shown, final int direction, final boolean ctrl,
        final boolean shift) {
        return Math.max(0, JsMath.round(shown) + direction * wheelStep(ctrl, shift));
    }

    // endregion

    // region Machine count at the peak

    /**
     * The machines a card is billed for at its peak power. A card with a count set is billed that count, or what the
     * solve needs when that is more. A card with none is billed whole machines, since a fractional machine cannot be
     * built. A shared machine's pool is billed the same way, as one card. A crop card's count ({@code keepSolved}) keeps
     * the solved figure: its pin is seeds, not machines. The average draw stays the solved fraction.
     */
    public static double peakCount(final double solved, @Nullable final Double set, final boolean keepSolved) {
        if (set != null && set > 0) return Math.max(set, solved);
        return keepSolved ? solved : Math.max(0, Math.ceil(solved - 1e-9));
    }

    // endregion

    // region Power dial

    /**
     * What a power figure is divided by to read in the dial's unit: nothing for EU/t; in amps, one amp of the card's
     * own tier (nothing when it has none, so the figure stays EU/t); in a tier, one amp of that tier, for everything.
     * {@code null} is "no division".
     */
    @Nullable
    public static Double divisor(final String unit, @Nullable final String ownTier) {
        if (unit.equals("eu")) return null;
        if (unit.equals("amps")) {
            return ownTier != null && !ownTier.equals("DEMO") && Tiers.isName(ownTier) ? Tiers.maxEuT(ownTier) : null;
        }
        return Tiers.maxEuT(unit);
    }

    /** A card's EU/t as the dial reads it (a total passes no tier). */
    public static double dialValue(final double euT, final String unit, @Nullable final String ownTier) {
        final Double d = divisor(unit, ownTier);
        return d == null ? euT : euT / d;
    }

    /** The unit label the figure wears: {@code EU/t} or {@code A <tier>}. */
    public static String dialSuffix(final String unit, @Nullable final String ownTier) {
        if (unit.equals("eu")) return "EU/t";
        if (unit.equals("amps")) return divisor(unit, ownTier) == null ? "EU/t" : "A " + ownTier;
        return "A " + unit;
    }

    // endregion
}
