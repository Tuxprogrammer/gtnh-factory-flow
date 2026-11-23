package com.gtnhplanner.nei;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import javax.annotation.Nullable;

/**
 * {@code /gtnhplanner bookmark <id>}: what a plan takes in from outside and what it gives out, as NEI item bookmarks
 * (the shopping list), and the line that says what happened. Pure: the stacks and NEI's panel are the caller's
 * ({@link PlanBookmarks#addShoppingList}), so the rules are tested without the game.
 */
public final class ShoppingList {

    /** What to bookmark, inputs first, each once, and how many resources have no game item to bookmark. */
    public record Pick<S> (List<S> inputs, List<S> outputs, int skipped) {}

    private ShoppingList() {}

    /**
     * The plan's inputs then its outputs, in the order given, each resource once by {@code keyOf}. A null stack is a
     * resource the game has no item for (a card shown only, power): left out and counted.
     */
    public static <S> Pick<S> pick(final List<? extends S> inputs, final List<? extends S> outputs,
        final Function<S, ?> keyOf) {
        final Set<Object> seen = new HashSet<>();
        final int[] skipped = { 0 };
        final List<S> in = take(inputs, keyOf, seen, skipped), out = take(outputs, keyOf, seen, skipped);
        return new Pick<>(in, out, skipped[0]);
    }

    private static <S> List<S> take(final List<? extends S> stacks, final Function<S, ?> keyOf, final Set<Object> seen,
        final int[] skipped) {
        final List<S> kept = new ArrayList<>();
        for (final S s : stacks) {
            if (s == null) skipped[0]++;
            else if (seen.add(keyOf.apply(s))) kept.add(s);
        }
        return kept;
    }

    /**
     * "Bookmarked 3 inputs and 2 outputs of Titanium line. 1 was already bookmarked." Bookmarks already there are
     * counted, never added twice.
     */
    public static String added(@Nullable final String plan, final int inputs, final int outputs, final int already,
        final int skipped) {
        final String name = plan == null || plan.isBlank() ? "this plan" : plan;
        final StringBuilder s = new StringBuilder();
        if (inputs + outputs == 0 && already == 0) s.append("Nothing in ")
            .append(name)
            .append(" to bookmark.");
        else if (inputs + outputs == 0) s.append("Everything ")
            .append(name)
            .append(" takes and makes is already in your bookmarks.");
        else {
            s.append("Bookmarked ");
            if (inputs > 0) s.append(count(inputs, "input", "inputs"));
            if (inputs > 0 && outputs > 0) s.append(" and ");
            if (outputs > 0) s.append(count(outputs, "output", "outputs"));
            s.append(" of ")
                .append(name)
                .append('.');
            if (already > 0) s.append(' ')
                .append(already == 1 ? "1 was" : already + " were")
                .append(" already bookmarked.");
        }
        if (skipped > 0) s.append(" Skipped ")
            .append(skipped)
            .append(" with no game item.");
        return s.toString();
    }

    private static String count(final int n, final String one, final String many) {
        return n + " " + (n == 1 ? one : many);
    }
}
