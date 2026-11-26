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
    void theOrderIsTheBoardsWhateverOrderTheCardsComeIn() {
        final List<Node> cards = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) cards.add(card("r" + i, (i * 7) % 5 * 100, i * 10));
        final List<String> first = PatternPlan.pick(cards, RECIPE, r -> r)
            .recipes();
        for (int k = 0; k < 5; k++) {
            java.util.Collections.shuffle(cards, new java.util.Random(k));
            assertEquals(
                first,
                PatternPlan.pick(cards, RECIPE, r -> r)
                    .recipes());
        }
    }

    private static PatternRules.Tally made(final int n) {
        PatternRules.Tally t = PatternRules.NONE;
        for (int i = 0; i < n; i++) t = t.plus(PatternRules.MADE, 0);
        return t;
    }

    private static PatternRules.Tally plus(PatternRules.Tally t, final byte outcome, final int n) {
        for (int i = 0; i < n; i++) t = t.plus(outcome, 0);
        return t;
    }

    @Test
    void aPageSaysWhichPatternsItMadeAndWhatIsNext() {
        assertEquals(
            "Made patterns 1 to 27 of 55. Free up slots and run it again for the next.",
            PatternPlan.page(plus(made(27), PatternRules.NO_ROOM, 28), 0, 27, 55, false, 0));
        assertEquals(
            "Made patterns 28 to 55 of 55. The plan is done.",
            PatternPlan.page(made(28), 27, 55, 55, false, 0));
        assertEquals(
            "No free inventory slots. Nothing was made.",
            PatternPlan.page(plus(PatternRules.NONE, PatternRules.NO_ROOM, 28), 27, 27, 55, false, 0));
        assertEquals(
            "Made pattern 55 of 55. The plan is done. Skipped 2 cards with no game recipe.",
            PatternPlan.page(made(1), 54, 55, 55, false, 2));
        assertEquals("Made 55 patterns.", PatternPlan.page(made(55), 0, 55, 55, false, 0), "one page: as before");
        assertEquals(
            "Made patterns 1 to 12 of 55. Out of blank patterns for 43 more.",
            PatternPlan.page(plus(made(12), PatternRules.NO_BLANK, 43), 0, 12, 55, false, 0));
        assertEquals(
            "No blank patterns in your inventory.",
            PatternPlan.page(plus(PatternRules.NONE, PatternRules.NO_BLANK, 43), 12, 12, 55, false, 0));
        assertEquals(
            "The plan's recipes changed, so this started again from the first. Made patterns 1 to 30 of 56. Free up slots and run it again for the next.",
            PatternPlan.page(plus(made(30), PatternRules.NO_ROOM, 26), 0, 30, 56, true, 0));
        assertEquals(
            "Made patterns 1 to 20 of 55. Free up slots and run it again for the next. 1 recipe cannot be patterned.",
            PatternPlan
                .page(plus(plus(made(19), PatternRules.CANNOT, 1), PatternRules.NO_ROOM, 35), 0, 20, 55, false, 0));
        assertEquals(
            "This server cannot make patterns.",
            PatternPlan.page(plus(PatternRules.NONE, PatternRules.NO_SERVER, 28), 27, 27, 55, false, 0));
    }
}
