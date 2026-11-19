package com.gtnhplanner.web.exports;

import java.util.Locale;

import org.teavm.jso.JSExport;

import com.gtnhplanner.core.base.Format;
import com.gtnhplanner.core.base.RateUnit;

/**
 * The number text a player reads, for JavaScript: core-base's {@link Format}. The website keeps its own TypeScript
 * formatters; the conformance run (tools/web-core/format.mjs) holds this class to the website's answers for the value
 * list in conformance/format, so the two spell every figure the same. The host reaches them as
 * {@code FormatExports.formatCompact(x)}.
 */
public final class FormatExports {

    private FormatExports() {}

    @JSExport
    public static String formatCompact(final double value) {
        return Format.compact(value);
    }

    @JSExport
    public static String formatCompactStable(final double value) {
        return Format.compactStable(value);
    }

    @JSExport
    public static String formatPowerValue(final double value, final boolean stable) {
        return Format.powerValue(value, stable);
    }

    @JSExport
    public static String formatRate(final double value, final int digits) {
        return Format.rate(value, digits);
    }

    /** The website's {@code formatNumberWithThousands} of a text. */
    @JSExport
    public static String formatThousands(final String text) {
        return Format.thousands(text);
    }

    /** The same of a number, which reads as JavaScript's {@code String(n)} first. */
    @JSExport
    public static String formatThousandsOfNumber(final double value) {
        return Format.thousands(value);
    }

    @JSExport
    public static String formatTrimZeros(final String text) {
        return Format.trimZeros(text);
    }

    @JSExport
    public static String formatMachineListCount(final double count) {
        return Format.machineListCount(count);
    }

    @JSExport
    public static String formatRatioShare(final double share) {
        return Format.ratioShare(share);
    }

    @JSExport
    public static String formatSolvedMachines(final double count) {
        return Format.solvedMachines(count);
    }

    /** {@code unit} is the website's rate unit name ("tick", "second", "minute", "hour", "eu"), {@code kind} its kind. */
    @JSExport
    public static String formatSlotRate(final double perSecond, final String unit, final String kind) {
        return Format.slotRate(perSecond, RateUnit.valueOf(unit.toUpperCase(Locale.ROOT)), kind);
    }

    /** NaN where the website's {@code energyPerUnit} is undefined. */
    @JSExport
    public static double formatEnergyPerUnit(final double euPerTick, final double perSecond) {
        return Format.energyPerUnit(euPerTick, perSecond);
    }

    @JSExport
    public static String formatEnergyPerUnitSuffix(final String kind) {
        return Format.energyPerUnitSuffix(kind);
    }
}
