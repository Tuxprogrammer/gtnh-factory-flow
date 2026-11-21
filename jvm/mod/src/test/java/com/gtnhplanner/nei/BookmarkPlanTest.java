package com.gtnhplanner.nei;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.flowchart.Node;

/**
 * The Share menu's NEI bookmark rows without the game: which cards give a recipe, each recipe once in board order, when
 * a chain is already bookmarked, and the line the player reads.
 */
class BookmarkPlanTest {

    @BeforeAll
    static void defaultProfile() {
        com.gtnhplanner.harness.GtnhFlowLoader.ensureDefaultMachineProfile();
    }

    /** A card whose machine name stands in for its recipe; "-" for one NEI cannot find. */
    private static Node card(final String recipe, final int x, final int y) {
        final Node n = new Node(Uuid.randomUUID(), x, y);
        n.machineName = recipe;
        return n;
    }

    private static final Function<Node, String> RECIPE = n -> "-".equals(n.machineName) ? null : n.machineName;

    @Test
    void skipsCardsWithNoGameRecipeAndPowerCards() {
        final Node rate = card("custom", 0, 0);
        rate.powerSource = Node.CUSTOM_RATE;
        final Node tank = card("tank", 0, 40);
        tank.powerSource = Node.FLUID_TANK;
        final BookmarkPlan.Pick<String> pick = BookmarkPlan
            .pick(List.of(card("ebf", 100, 0), rate, tank, card("-", 50, 0), card("mixer", 0, 0)), RECIPE, r -> r);
        assertEquals(List.of("mixer", "ebf"), pick.recipes());
        assertEquals(3, pick.skipped());
    }

    @Test
    void eachRecipeOnceInBoardOrder() {
        // Two cards on one recipe give it once; a shared machine's recipes are cards of their own, each given.
        final BookmarkPlan.Pick<String> pick = BookmarkPlan.pick(
            List.of(card("b", 200, 0), card("a", 0, 80), card("a", 300, 0), card("c", 0, 10), card("d", 200, 40)),
            RECIPE,
            r -> r);
        assertEquals(List.of("c", "a", "b", "d"), pick.recipes());
        assertEquals(0, pick.skipped());
    }

    @Test
    void duplicatesFoundByKey() {
        final BookmarkPlan.Pick<String> pick = BookmarkPlan.pick(
            List.of(card("Mixer#1", 0, 0), card("mixer#2", 10, 0)),
            RECIPE,
            r -> r.toLowerCase()
                .charAt(0));
        assertEquals(List.of("Mixer#1"), pick.recipes());
    }

    @Test
    void chainThereOnlyWhenOneChainHoldsEveryRecipe() {
        final List<String> plan = List.of("a", "b", "c");
        assertTrue(BookmarkPlan.chainThere(plan, List.of(Set.of("x"), Set.of("a", "b", "c", "d"))));
        assertFalse(BookmarkPlan.chainThere(plan, List.of(Set.of("a", "b"), Set.of("c"))));
        assertFalse(BookmarkPlan.chainThere(plan, List.of()));
        assertFalse(BookmarkPlan.chainThere(List.of(), List.of(Set.of("a"))));
    }

    @Test
    void recipeLines() {
        assertEquals("Added 46 recipe bookmarks. Skipped 3 cards with no game recipe.", BookmarkPlan.added(46, 0, 3));
        assertEquals("Added 1 recipe bookmark.", BookmarkPlan.added(1, 0, 0));
        assertEquals("All 46 recipes are already in your bookmarks.", BookmarkPlan.added(0, 46, 0));
        assertEquals(
            "This recipe is already in your bookmarks. Skipped 1 card with no game recipe.",
            BookmarkPlan.added(0, 1, 1));
        assertEquals(
            "Added 2 recipe bookmarks. 1 was already bookmarked. Skipped 1 card with no game recipe.",
            BookmarkPlan.added(2, 1, 1));
        assertEquals(
            "This plan has no recipes to bookmark. Skipped 2 cards with no game recipe.",
            BookmarkPlan.added(0, 0, 2));
    }

    @Test
    void chainLines() {
        assertEquals(
            "Added a chain of 12 recipes to your bookmarks. Skipped 3 cards with no game recipe.",
            BookmarkPlan.chained(12, false, 3));
        assertEquals("Added a chain of 1 recipe to your bookmarks.", BookmarkPlan.chained(1, false, 0));
        assertEquals("This chain is already in your bookmarks.", BookmarkPlan.chained(12, true, 0));
        assertEquals("This plan has no recipes to bookmark.", BookmarkPlan.chained(0, false, 0));
    }
}
