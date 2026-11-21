package com.gtnhplanner.nei;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.gtnhplanner.data.flowchart.Node;

/**
 * What the Share menu's NEI bookmark rows put in, and the line that says what happened. Pure: finding a card's recipe
 * in NEI is the caller's ({@link PlanBookmarks} passes NEI's lookup), so the rules are tested without the game.
 */
public final class BookmarkPlan {

    /** The plan's recipes, each once, in board order (left to right, then top to bottom), and the cards left out. */
    public record Pick<R> (List<R> recipes, int skipped) {}

    private BookmarkPlan() {}

    /**
     * Every card's recipe once. A card is left out (and counted) when it is a power card (custom rate, fluid tank, crop
     * farm, generators) or {@code recipeOf} finds no game recipe for it (a view-only card, a recipe this pack lacks).
     * Two cards on one recipe give it once, by {@code keyOf}. Each recipe of a shared machine is a card of its own
     * here, so a card with joined recipes gives each of them.
     */
    public static <R> Pick<R> pick(final Collection<Node> cards, final Function<Node, R> recipeOf,
        final Function<R, ?> keyOf) {
        final List<Node> order = new ArrayList<>(cards);
        order.sort(
            Comparator.comparingInt((Node n) -> n.x)
                .thenComparingInt(n -> n.y)
                .thenComparing(n -> String.valueOf(n.id)));
        final Map<Object, R> recipes = new LinkedHashMap<>();
        int skipped = 0;
        for (final Node n : order) {
            final R r = n.isPower() ? null : recipeOf.apply(n);
            if (r == null) skipped++;
            else recipes.putIfAbsent(keyOf.apply(r), r);
        }
        return new Pick<>(new ArrayList<>(recipes.values()), skipped);
    }

    /**
     * Whether one of the player's crafting chains already holds every one of these recipes: pressing again adds
     * nothing. A chain that holds only some of them is the player's own and is left alone; a new chain is made.
     */
    public static <K> boolean chainThere(final Collection<K> recipes, final Collection<? extends Set<K>> chains) {
        if (recipes.isEmpty()) return false;
        final Set<K> wanted = new HashSet<>(recipes);
        for (final Set<K> chain : chains) if (chain.containsAll(wanted)) return true;
        return false;
    }

    /** "Added 46 recipe bookmarks. Skipped 3 cards with no game recipe." */
    public static String added(final int added, final int already, final int skipped) {
        final StringBuilder s = new StringBuilder();
        if (added == 0 && already == 0) s.append("This plan has no recipes to bookmark.");
        else if (added == 0) s.append(
            already == 1 ? "This recipe is already in your bookmarks."
                : "All " + already + " recipes are already in your bookmarks.");
        else s.append("Added ")
            .append(count(added, "recipe bookmark", "recipe bookmarks"))
            .append('.');
        if (added > 0 && already > 0) s.append(' ')
            .append(already == 1 ? "1 was" : already + " were")
            .append(" already bookmarked.");
        return skipped(s, skipped);
    }

    /** "Added a chain of 12 recipes to your bookmarks." or that it is there already, then the cards left out. */
    public static String chained(final int recipes, final boolean already, final int skipped) {
        final StringBuilder s = new StringBuilder();
        if (recipes == 0) s.append("This plan has no recipes to bookmark.");
        else if (already) s.append("This chain is already in your bookmarks.");
        else s.append("Added a chain of ")
            .append(count(recipes, "recipe", "recipes"))
            .append(" to your bookmarks.");
        return skipped(s, skipped);
    }

    private static String skipped(final StringBuilder s, final int skipped) {
        if (skipped > 0) s.append(" Skipped ")
            .append(count(skipped, "card", "cards"))
            .append(" with no game recipe.");
        return s.toString();
    }

    private static String count(final int n, final String one, final String many) {
        return n + " " + (n == 1 ? one : many);
    }
}
