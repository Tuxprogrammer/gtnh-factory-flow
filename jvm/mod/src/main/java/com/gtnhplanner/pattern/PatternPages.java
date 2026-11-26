package com.gtnhplanner.pattern;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.function.IntPredicate;

import javax.annotation.Nullable;

/**
 * "Pattern the plan" a page at a time, without the game. A plan whose patterns do not all fit the inventory is made
 * as many as fit now, in a fixed order; running it again goes on from where it stopped, until the plan is done. The
 * order is the one the first page was made in, kept for the whole run, so moving cards never skips or repeats a
 * recipe; a change to the plan's set of recipes starts the run again from the first.
 */
public final class PatternPages {

    private PatternPages() {}

    /** Where a plan's run got to: the recipes in the order the run makes them, and the first one not made yet. */
    public record Position<K> (List<K> order, int next) {}

    /** Where a page starts: the order it goes by, the first recipe it makes, and whether the run started again. */
    public record Start<K> (List<K> order, int from, boolean restarted) {}

    /**
     * The page to make now for a plan whose recipes are {@code now} (in board order): from the remembered position
     * when the plan has the same set of recipes, else from the first (and {@code restarted} when a remembered position
     * was given up).
     */
    public static <K> Start<K> start(@Nullable final Position<K> at, final List<K> now) {
        if (at == null) return new Start<>(List.copyOf(now), 0, false);
        if (at.order()
            .size() == now.size() && new HashSet<>(at.order()).equals(new HashSet<>(now)))
            return new Start<>(at.order(), Math.min(at.next(), now.size()), false);
        return new Start<>(List.copyOf(now), 0, true);
    }

    /**
     * Where a page ends (exclusive): from {@code from}, the next {@code room} recipes that make a pattern, taking in
     * the ones between that cannot be patterned ({@code cannot}), which need no slot.
     */
    public static int end(final int from, final int total, final int room, final IntPredicate cannot) {
        int at = from, made = 0;
        while (at < total) {
            if (!cannot.test(at)) {
                if (made == room) break;
                made++;
            }
            at++;
        }
        return at;
    }

    /**
     * The first recipe of the page not made: the page's end, or the first that came back unmade for a reason that
     * can pass (no room, no blank, no server), as running the plan again should make it.
     */
    public static int resume(final int end, final Collection<Integer> notMade) {
        int at = end;
        for (final int i : notMade) at = Math.min(at, i);
        return at;
    }

    /**
     * How many of {@code places} come before {@code resume}: a page that stopped early tells only of the recipes it
     * passed, as the rest are tried again on the next page.
     */
    public static int before(final Collection<Integer> places, final int resume) {
        int n = 0;
        for (final int i : places) if (i < resume) n++;
        return n;
    }

    /** The position after a page that stopped at {@code resume}; null when the plan is done or nothing was made yet. */
    @Nullable
    public static <K> Position<K> after(final List<K> order, final int resume) {
        if (resume <= 0 || resume >= order.size()) return null;
        return new Position<>(order, resume);
    }

    /** The Share menu's row for a plan part made: "Pattern the plan (28 to 55 of 55)". */
    public static String row(final Position<?> at) {
        final int total = at.order()
            .size();
        return "Pattern the plan (" + (at.next() + 1) + " to " + total + " of " + total + ")";
    }

    /** The row that drops the position and makes the plan from its first recipe. */
    public static final String FROM_START = "Pattern the plan from the start";
}
