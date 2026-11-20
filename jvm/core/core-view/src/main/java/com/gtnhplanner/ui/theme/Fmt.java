package com.gtnhplanner.ui.theme;

import com.gtnhplanner.core.base.Format;

/**
 * Number and rate formatting for the board. The rules are core-base's {@link Format}, one formatter for the mod and the
 * website, held to the website's answers by conformance/format; this class keeps the names the mod's views call and
 * the mod's own width-fitting {@link #brief}.
 */
public final class Fmt {

    /**
     * The board's rate unit, a clock (the key's fifth setting, EU, is BoardSession.energyView). Rates are stored per
     * second; this converts and labels them.
     */
    public enum RateUnit {

        TICK(com.gtnhplanner.core.base.RateUnit.TICK),
        SECOND(com.gtnhplanner.core.base.RateUnit.SECOND),
        MINUTE(com.gtnhplanner.core.base.RateUnit.MINUTE),
        HOUR(com.gtnhplanner.core.base.RateUnit.HOUR);

        public final String suffix;
        public final double perSecond;
        private final com.gtnhplanner.core.base.RateUnit core;

        RateUnit(final com.gtnhplanner.core.base.RateUnit core) {
            this.core = core;
            this.suffix = "/" + core.per;
            this.perSecond = core.perSecond;
        }

        /** The core's unit this is. */
        public com.gtnhplanner.core.base.RateUnit core() {
            return core;
        }
    }

    private Fmt() {}

    /**
     * Compact number: exact 0 prints "0"; below 1 keeps 2 significant digits (down to twelve places, so a trickle
     * never reads as nothing); otherwise 2 decimals (1 from 100) with trailing zeros dropped, then k, M, G... to E, e.g.
     * 6930 -> "6.93k". A number that is not finite reads "unbounded".
     */
    public static String compact(final double value) {
        return Format.compact(value);
    }

    /**
     * At most three significant figures, then k, M, G...: for numbers that must fit a small space whole, like a
     * zoomed-out drawer ("167", "33.3", "33.3k"). Below 1 it keeps two, as {@link #compact} does.
     */
    public static String brief(final double value) {
        return Format.brief(value);
    }

    /** A per-second rate in the given unit, e.g. "900/hr"; fluids get " L" before the unit ("180k L/hr"). */
    public static String rate(final double perSecond, final RateUnit unit, final boolean fluid) {
        return Format.slotRate(perSecond, unit.core, fluid ? "fluid" : "item");
    }

    /**
     * Solved machine count, Factory Flow style: "0"; "<0.001"; 3 decimals under 1, 2 under 10, 1 under 100, trailing
     * zeros dropped; 100 or more rounds up to a whole number (you cannot build part of a machine at that scale).
     */
    public static String machines(final double count) {
        return Format.solvedMachines(count);
    }

    /** EU/t as the power display prints it: "<0.01" for a trickle, else the compact rules, e.g. "6.93k". */
    public static String power(final double euPerTick) {
        return Format.powerValue(euPerTick);
    }

    /**
     * The rate key's EU reading (the website's rate-unit.ts energyPerUnit): the EU spent on each unit a flow carries,
     * {@code euPerTick} of draw over {@code perSecond} of it. Scale-free (machine count and parallels cancel) but not
     * tier-free: a regular overclock doubles it per step, a perfect one leaves it flat. NaN when nothing flows, so the
     * row keeps its rate instead of reading infinity.
     */
    public static double euEach(final double euPerTick, final double perSecond) {
        return Format.energyPerUnit(euPerTick, perSecond);
    }

    /** The unit the EU reading wears, as the website's: per item, or per litre for a fluid. */
    public static String euEachSuffix(final boolean fluid) {
        return Format.energyPerUnitSuffix(fluid ? "fluid" : "item");
    }
}
