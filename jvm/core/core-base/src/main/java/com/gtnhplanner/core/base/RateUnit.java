package com.gtnhplanner.core.base;

/**
 * The board's rate unit, the website's {@code RateUnit} (src/lib/format/rate-unit.ts). A rate is stored per second;
 * {@link #perSecond} converts it for display and {@link #per} labels it. {@link #EU} is the odd one out: energy per
 * unit made, not a clock, so it reads like a second.
 */
public enum RateUnit {

    /** A Minecraft tick is a twentieth of a second. */
    TICK(1.0 / 20.0, "t"),
    SECOND(1.0, "s"),
    MINUTE(60.0, "min"),
    HOUR(3600.0, "hr"),
    EU(1.0, "s");

    /** Multiply a per-second figure by this before display. */
    public final double perSecond;
    /** The word after the slash: "t", "s", "min", "hr". */
    public final String per;

    RateUnit(final double perSecond, final String per) {
        this.perSecond = perSecond;
        this.per = per;
    }
}
