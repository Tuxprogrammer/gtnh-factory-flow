package com.gtnhplanner.pattern;

import java.util.Collection;
import java.util.function.Function;

import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.nei.BookmarkPlan;

/**
 * "Pattern the plan" without the game: which of a plan's recipes get a pattern, and what the player is told. Finding a
 * card's recipe in NEI is the caller's ({@link PatternThis#plan} passes NEI's lookup), so the rules are tested without
 * the game.
 */
public final class PatternPlan {

    private PatternPlan() {}

    /**
     * One pattern for each distinct recipe of the plan, in board order: the cards the Share menu's bookmark rows take
     * ({@link BookmarkPlan#pick}). Two cards on one recipe ({@code keyOf}) make one pattern; power cards (custom rate,
     * fluid tank, crop farm, generators) and cards with no game recipe ({@code recipeOf} null) are left out and
     * counted.
     * Each recipe of a shared machine is a card of its own.
     */
    public static <R> BookmarkPlan.Pick<R> pick(final Collection<Node> cards, final Function<Node, R> recipeOf,
        final Function<R, ?> keyOf) {
        return BookmarkPlan.pick(cards, recipeOf, keyOf);
    }

    /** "This plan needs 46 blank patterns and you have 12. Nothing was made." */
    public static String tooFew(final int need, final int have) {
        return "This plan needs " + need
            + (need == 1 ? " blank pattern" : " blank patterns")
            + " and you have "
            + have
            + ". Nothing was made.";
    }

    /** "Made 46 patterns. Skipped 3 cards with no game recipe." */
    public static String summary(final PatternRules.Tally t, final int skipped) {
        final StringBuilder s = new StringBuilder(
            t.total() == 0 ? "This plan has no recipes to pattern" : PatternRules.summary(t)).append('.');
        if (skipped > 0) s.append(" Skipped ")
            .append(skipped)
            .append(skipped == 1 ? " card" : " cards")
            .append(" with no game recipe.");
        return s.toString();
    }
}
