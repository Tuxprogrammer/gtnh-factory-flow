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

    /**
     * The sentence a page of the plan ends with ({@link PatternPages}): the page made recipes {@code from} up to
     * {@code resume} (0-based, {@code resume} the first not made) of the run's {@code total}, and why it stopped. A
     * plan
     * made whole in one go reads as {@link #summary}.
     */
    public static String page(final PatternRules.Tally t, final int from, final int resume, final int total,
        final boolean restarted, final int skipped) {
        final StringBuilder s = new StringBuilder(
            restarted ? "The plan's recipes changed, so this started again from the first. " : "");
        if (from == 0 && resume >= total) return s.append(summary(t, skipped))
            .toString();
        if (resume > from) s.append(
            resume - from == 1 ? "Made pattern " + resume + " of " + total + ". "
                : "Made patterns " + (from + 1) + " to " + resume + " of " + total + ". ");
        if (resume >= total) s.append("The plan is done.");
        else if (t.noServer() > 0) s.append("This server cannot make patterns.");
        else if (t.noBlank() > 0) s.append(
            resume > from ? "Out of blank patterns for " + (total - resume) + " more."
                : "No blank patterns in your inventory.");
        else s.append(
            resume > from ? "Free up slots and run it again for the next."
                : "No free inventory slots. Nothing was made.");
        if (t.cannot() > 0) s.append(' ')
            .append(t.cannot())
            .append(t.cannot() == 1 ? " recipe cannot" : " recipes cannot")
            .append(" be patterned.");
        if (t.dropped() > 0) s.append(' ')
            .append(t.dropped())
            .append(
                t.dropped() == 1 ? " stack did not fit the terminal and was left out."
                    : " stacks did not fit the terminal and were left out.");
        if (skipped > 0) s.append(" Skipped ")
            .append(skipped)
            .append(skipped == 1 ? " card" : " cards")
            .append(" with no game recipe.");
        return s.toString();
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
