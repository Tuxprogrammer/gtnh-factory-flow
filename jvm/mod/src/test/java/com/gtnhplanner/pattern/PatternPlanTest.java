package com.gtnhplanner.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.nei.BookmarkPlan;

/**
 * "Pattern the plan" without the game: one pattern per distinct recipe, which cards are left out and counted, and the
 * lines the player reads.
 */
class PatternPlanTest {

    @BeforeAll
    static void defaultProfile() {
        com.gtnhplanner.harness.GtnhFlowLoader.ensureDefaultMachineProfile();
    }

    /** A card whose machine name stands in for its recipe; "-" for one with no game recipe. */
    private static Node card(final String recipe, final int x, final int y) {
        final Node n = new Node(Uuid.randomUUID(), x, y);
        n.machineName = recipe;
        return n;
    }

    private static final Function<Node, String> RECIPE = n -> "-".equals(n.machineName) ? null : n.machineName;

    @Test
    void oneRecipeOnceAndTheRestCounted() {
        final Node rate = card("custom", 0, 300);
        rate.powerSource = Node.CUSTOM_RATE;
        final Node generator = card("boiler", 100, 300);
        generator.powerSource = "generator";
        final BookmarkPlan.Pick<String> pick = PatternPlan.pick(
            List.of(
                card("macerate", 0, 0),
                card("bend", 100, 0),
                card("macerate", 200, 0),
                card("electrolyse", 300, 0),
                card("-", 400, 0),
                rate,
                generator,
                card("craft", 500, 0)),
            RECIPE,
            r -> r);
        assertEquals(List.of("macerate", "bend", "electrolyse", "craft"), pick.recipes(), "the duplicate made once");
        assertEquals(3, pick.skipped(), "no game recipe, a custom rate and a generator");
    }

    @Test
    void theLineAtTheEnd() {
        PatternRules.Tally t = PatternRules.NONE;
        for (int i = 0; i < 46; i++) t = t.plus(PatternRules.MADE, 0);
        assertEquals("Made 46 patterns. Skipped 3 cards with no game recipe.", PatternPlan.summary(t, 3));
        assertEquals("Made 46 patterns.", PatternPlan.summary(t, 0));
        assertEquals(
            "Made a pattern. Skipped 1 card with no game recipe.",
            PatternPlan.summary(PatternRules.NONE.plus(PatternRules.MADE, 0), 1));
        assertEquals(
            "Made 46 patterns. 1 recipe cannot be patterned. Skipped 2 cards with no game recipe.",
            PatternPlan.summary(t.plus(PatternRules.CANNOT, 0), 2));
        assertEquals(
            "This plan has no recipes to pattern. Skipped 2 cards with no game recipe.",
            PatternPlan.summary(PatternRules.NONE, 2));
    }

    @Test
    void tooFewBlanksMakesNothing() {
        assertEquals(
            "This plan needs 46 blank patterns and you have 12. Nothing was made.",
            PatternPlan.tooFew(46, 12));
        assertEquals("This plan needs 1 blank pattern and you have 0. Nothing was made.", PatternPlan.tooFew(1, 0));
    }
}
