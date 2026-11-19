package com.gtnhplanner.ui.card;

import java.util.function.IntFunction;

import javax.annotation.Nullable;

import com.gtnhplanner.core.view.card.CardTiers;

/**
 * What a multiblock card's POWER INPUT panel says (the mod's {@code PowerPanel} draws it; see
 * {@code docs/design/ff-card-spec.md}): what its amps at its tier supply, what that buys (overclocks, time and runs, EU
 * per run, draw), the next amp count that would make it faster, and what the card asks of the grid. Pure: one run at a
 * given amp count comes from the host ({@code runAt}), which works the machine's maths at that count.
 */
public final class PowerInput {

    /** The most amps the chip takes, as on the website (laser hatches go that far). */
    public static final int MAX_AMPS = 1 << 24;

    private PowerInput() {}

    /** One run at a given number of amps: ticks, EU/t for all its parallels, parallels. */
    public record Run(int duration, long eut, int parallels) {

        public double runsPerSecond() {
            return parallels * 20.0 / Math.max(1, duration);
        }
    }

    /** What the panel shows, worked out once per model. */
    public record Facts(int amps, String tier, long volt, Run now, @Nullable Integer nextAmps, @Nullable Run next,
        int overclocks, int perfect, double machines, double demand, boolean shared) {}

    private static boolean faster(final Run a, final Run b) {
        return a.runsPerSecond() > b.runsPerSecond() * 1.0001;
    }

    /**
     * For a card at {@code amps} on {@code tier}; {@code runAt} is one run at a given amp count, {@code baseEuPerTick}
     * and {@code baseDurationTicks} the recipe's own (before overclocks; null when the recipe has none), {@code demand}
     * the card's whole draw (every recipe's, on a shared machine).
     */
    public static Facts facts(final int amps, final String tier, final double machines, final IntFunction<Run> runAt,
        @Nullable final Long baseEuPerTick, @Nullable final Double baseDurationTicks, final double demand,
        final boolean shared) {
        final int t = CardTiers.tierIndex(tier);
        final long volt = t < 0 ? 0 : 8L << (2 * t);
        final Run now = runAt.apply(amps);
        // The next amp count that runs it faster (past the one-tick floor more amps only draw more): double until one
        // does, then halve the gap back.
        Integer nextAmps = null;
        Run next = null;
        int lo = amps, hi = amps;
        while (hi < MAX_AMPS) {
            hi = (int) Math.min(MAX_AMPS, hi * 2L);
            if (faster(runAt.apply(hi), now)) break;
            lo = hi;
        }
        if (lo < hi) {
            final Run top = runAt.apply(hi);
            if (faster(top, now)) {
                while (hi - lo > 1) {
                    final int mid = lo + (hi - lo) / 2;
                    if (!faster(runAt.apply(mid), now)) lo = mid;
                    else hi = mid;
                }
                nextAmps = hi;
                next = runAt.apply(hi);
            }
        }
        // How many overclocks: each one quadruples the draw; the ones that also quarter the time are perfect.
        int overclocks = 0, perfect = 0;
        if (baseEuPerTick != null && baseEuPerTick > 0 && baseDurationTicks != null) {
            final double perRun = (double) now.eut() / (baseEuPerTick.doubleValue() * now.parallels());
            overclocks = Math.max(0, (int) Math.round(Math.log(perRun) / Math.log(4)));
            final double quicker = baseDurationTicks / now.duration();
            final int halvings = Math.max(0, (int) Math.round(Math.log(quicker) / Math.log(2)));
            perfect = Math.max(0, Math.min(overclocks, halvings - overclocks));
        }
        return new Facts(amps, tier, volt, now, nextAmps, next, overclocks, perfect, machines, demand, shared);
    }

    /** The overclocks line: "none", "3", or "1 perfect, 2". */
    public static String overclockText(final Facts f) {
        if (f.overclocks() == 0) return "none";
        if (f.perfect() == 0) return Integer.toString(f.overclocks());
        return f.perfect() + " perfect, " + (f.overclocks() - f.perfect());
    }

}
