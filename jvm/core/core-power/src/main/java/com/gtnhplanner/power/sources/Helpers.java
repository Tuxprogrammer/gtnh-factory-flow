package com.gtnhplanner.power.sources;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

import com.gtnhplanner.core.base.NumberText;
import com.gtnhplanner.power.PowerData;
import com.gtnhplanner.power.PowerModel;
import com.gtnhplanner.power.PowerModel.Flow;
import com.gtnhplanner.power.PowerModel.Stat;
import com.gtnhplanner.power.PowerSetting;

/** The website's sources/helpers.ts: flow and stat builders and its number formats. */
public final class Helpers {

    public static Flow liters(final String name, final double perSecond) {
        return new Flow(name, perSecond, PowerModel.Unit.L);
    }

    public static Flow items(final String name, final double perSecond) {
        return new Flow(name, perSecond, PowerModel.Unit.ITEM);
    }

    public static Stat stat(final String label, final String value) {
        return new Stat(label, value);
    }

    /**
     * {@code Intl.NumberFormat("en-US", { maximumFractionDigits: 2 })}: grouping commas, at most two decimals,
     * halves away from zero. Like ICU it rounds the shortest decimal that round-trips the double, not its exact
     * binary value: 2.485 (stored as 2.48499999...) shows as "2.49", as on the website.
     */
    public static String number(final double value) {
        if (Double.isNaN(value)) return "NaN";
        if (Double.isInfinite(value)) return value > 0 ? "∞" : "-∞";
        // Intl keeps the sign of a negative zero; BigDecimal has none.
        if (value == 0) return 1 / value < 0 ? "-0" : "0";
        final String text = NumberText.of(Math.abs(value));
        final int exponentAt = text.indexOf('e');
        final String mantissa = exponentAt < 0 ? text : text.substring(0, exponentAt);
        final int exponent = exponentAt < 0 ? 0 : Integer.parseInt(text.substring(exponentAt + 1));
        final int dot = mantissa.indexOf('.');
        final String digits = dot < 0 ? mantissa : mantissa.substring(0, dot) + mantissa.substring(dot + 1);
        final int point = (dot < 0 ? mantissa.length() : dot) + exponent;
        // The decimal as whole digits and fraction digits, then half-up at the second decimal.
        final StringBuilder whole = new StringBuilder();
        final StringBuilder fraction = new StringBuilder();
        if (point <= 0) {
            whole.append('0');
            for (int i = 0; i < -point; i++) fraction.append('0');
            fraction.append(digits);
        } else if (point >= digits.length()) {
            whole.append(digits);
            for (int i = digits.length(); i < point; i++) whole.append('0');
        } else {
            whole.append(digits, 0, point);
            fraction.append(digits, point, digits.length());
        }
        final boolean roundUp = fraction.length() > 2 && fraction.charAt(2) >= '5';
        fraction.setLength(Math.min(fraction.length(), 2));
        if (roundUp) {
            // Carry through the kept digits, fraction first.
            int at = whole.length() + fraction.length() - 1;
            boolean carry = true;
            while (carry && at >= 0) {
                final boolean inFraction = at >= whole.length();
                final StringBuilder part = inFraction ? fraction : whole;
                final int index = inFraction ? at - whole.length() : at;
                if (part.charAt(index) == '9') {
                    part.setCharAt(index, '0');
                } else {
                    part.setCharAt(index, (char) (part.charAt(index) + 1));
                    carry = false;
                }
                at--;
            }
            if (carry) whole.insert(0, '1');
        }
        while (fraction.length() > 0 && fraction.charAt(fraction.length() - 1) == '0') fraction.setLength(fraction.length() - 1);
        final StringBuilder out = new StringBuilder();
        if (value < 0) out.append('-');
        for (int i = 0; i < whole.length(); i++) {
            if (i > 0 && (whole.length() - i) % 3 == 0) out.append(',');
            out.append(whole.charAt(i));
        }
        if (fraction.length() > 0) out.append('.').append(fraction);
        return out.toString();
    }

    /** {@link #number} with G and M past a billion and a million; "-" when not finite. */
    public static String formatAmount(final double value) {
        if (!Double.isFinite(value)) return "-";
        if (Math.abs(value) >= 1_000_000_000) return number(value / 1_000_000_000) + "G";
        if (Math.abs(value) >= 1_000_000) return number(value / 1_000_000) + "M";
        return number(value);
    }

    public static String percent(final double value) {
        return number(value * 100) + "%";
    }

    public record TierPower(double voltage, double ampLoss) {}

    /** Voltage and per-amp packet loss for a tier name, from the workbook's own ladder. */
    public static TierPower tierPower(final String tier) {
        for (final PowerData.SingleblockTier row : PowerData.get().singleblockTiers) {
            if (row.tier.equals(tier)) return new TierPower(row.voltage, row.ampLoss);
        }
        return new TierPower(32, 1);
    }

    /** A singleblock family's tier options and its efficiency at each. */
    public record FamilyTiers(List<PowerSetting.Option> options, ToDoubleFunction<String> efficiency) {

        public double efficiencyFor(final String tier) {
            return efficiency.applyAsDouble(tier);
        }
    }

    /** Tier options for a singleblock family: the tiers its efficiency ladder covers. */
    public static FamilyTiers familyTierOptions(final String family) {
        final PowerData data = PowerData.get();
        final Double[] ladder = data.singleblockEfficiency.getOrDefault(family, new Double[0]);
        final List<PowerSetting.Option> options = new ArrayList<>();
        for (int i = 0; i < data.singleblockTiers.size(); i++) {
            if (i < ladder.length && ladder[i] != null) {
                final String tier = data.singleblockTiers.get(i).tier;
                options.add(new PowerSetting.Option(tier, tier));
            }
        }
        return new FamilyTiers(options, tier -> {
            for (int i = 0; i < data.singleblockTiers.size(); i++) {
                if (data.singleblockTiers.get(i).tier.equals(tier)) {
                    return i < ladder.length && ladder[i] != null ? ladder[i] : 1;
                }
            }
            return 1;
        });
    }

    /** Rotor lifespan in whole hours, from seconds. */
    public static String lifespanHours(final double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0) return "-";
        final double hours = seconds / 3600;
        return hours >= 100 ? Math.round(hours) + "h" : number(hours) + "h";
    }

    private Helpers() {}
}
