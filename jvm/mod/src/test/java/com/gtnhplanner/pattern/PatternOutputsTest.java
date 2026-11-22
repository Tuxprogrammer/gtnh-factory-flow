package com.gtnhplanner.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.nei.BookmarkPlan;

/**
 * Which products go in a pattern, without the game: the first product alone unless the player picks more, never none,
 * the box only when there is a choice, and "Pattern the plan" taking each card's remembered choice.
 */
class PatternOutputsTest {

    private static final String SODIUM = "item:gregtech:gt.metaitem.01:2017", EMPTY_CELL = "item:IC2:itemCellEmpty:0",
        CHLORINE = "fluid:chlorine", HYDROGEN = "fluid:hydrogen";

    @BeforeAll
    static void defaultProfile() {
        com.gtnhplanner.harness.GtnhFlowLoader.ensureDefaultMachineProfile();
    }

    @Test
    void theFirstProductAloneByDefault() {
        final List<String> products = List.of(SODIUM, EMPTY_CELL, CHLORINE);
        assertEquals(List.of(SODIUM), PatternOutputs.chosen(products, null), "never chosen");
        assertEquals(
            List.of(0),
            PatternOutputs.keep(List.of(SODIUM, EMPTY_CELL, CHLORINE), PatternOutputs.chosen(products, null)),
            "only the first output slot stays");
    }

    @Test
    void productsGoInTheCardsOrder() {
        // Packed with the fluid first; the card shows items before fluids.
        final List<String> packed = List.of(CHLORINE, SODIUM, EMPTY_CELL);
        final List<String> products = PatternOutputs.products(packed, List.of(SODIUM, EMPTY_CELL, CHLORINE));
        assertEquals(List.of(SODIUM, EMPTY_CELL, CHLORINE), products);
        assertEquals(
            List.of(1),
            PatternOutputs.keep(packed, PatternOutputs.chosen(products, null)),
            "the card's first product, wherever it was packed: its slot stays where it is");
        assertEquals(
            List.of(SODIUM, CHLORINE),
            PatternOutputs.products(List.of(SODIUM, "", SODIUM, CHLORINE), List.of()),
            "no card: packed order, each product once, unreadable slots left out");
        assertEquals(
            List.of(SODIUM, CHLORINE),
            PatternOutputs.products(List.of(CHLORINE, SODIUM), List.of(SODIUM, "fluid:steam")),
            "a card product that is not in the pattern is not offered; one the card lacks comes last");
    }

    @Test
    void fluidOnlyRecipesStartWithTheFirstFluid() {
        final List<String> products = PatternOutputs.products(List.of(HYDROGEN, CHLORINE), List.of(HYDROGEN, CHLORINE));
        assertEquals(List.of(HYDROGEN), PatternOutputs.chosen(products, null));
        assertTrue(PatternOutputs.asks(products));
    }

    @Test
    void aSingleProductRecipeIsNotAsked() {
        assertFalse(PatternOutputs.asks(List.of(SODIUM)));
        assertFalse(
            PatternOutputs.asks(PatternOutputs.products(List.of(SODIUM, SODIUM), List.of(SODIUM))),
            "two slots of one product are one product");
        assertEquals(List.of(SODIUM), PatternOutputs.chosen(List.of(SODIUM), null));
        assertEquals(List.of(0, 1), PatternOutputs.keep(List.of(SODIUM, SODIUM), List.of(SODIUM)), "both slots stay");
    }

    @Test
    void neverEmpty() {
        final List<String> products = List.of(SODIUM, EMPTY_CELL, CHLORINE);
        List<String> on = PatternOutputs.chosen(products, null);
        assertTrue(PatternOutputs.canMake(on));
        on = PatternOutputs.toggle(products, on, SODIUM);
        assertEquals(List.of(), on, "the switch may turn the last one off");
        assertFalse(PatternOutputs.canMake(on), "but Make pattern is off then");
        on = PatternOutputs.toggle(products, on, CHLORINE);
        on = PatternOutputs.toggle(products, on, SODIUM);
        assertEquals(List.of(SODIUM, CHLORINE), on, "kept in product order whatever the order switched");
        assertTrue(PatternOutputs.canMake(on));

        assertEquals(List.of(SODIUM), PatternOutputs.chosen(products, List.of()), "an empty memory is the default");
        assertEquals(
            List.of(SODIUM),
            PatternOutputs.chosen(products, List.of("item:minecraft:dirt:0")),
            "a memory naming nothing of the recipe any more is the default");
        assertEquals(
            List.of(EMPTY_CELL),
            PatternOutputs.chosen(products, List.of("item:minecraft:dirt:0", EMPTY_CELL)),
            "what is left of a memory stays");
        assertEquals(
            List.of(0),
            PatternOutputs.keep(List.of(SODIUM, CHLORINE), List.of("item:minecraft:dirt:0")),
            "nothing to keep keeps the first slot: never a pattern with no output");

        final PatternOutputs.Choices c = new PatternOutputs.Choices();
        c.put("plan", "card", List.of());
        assertNull(c.get(List.of("plan"), "card"), "an empty choice is never remembered");
    }

    @Test
    void choicesAreKeptPerPlanAndCard() {
        final PatternOutputs.Choices c = new PatternOutputs.Choices();
        c.put("plan-a", "card-1", List.of(SODIUM, CHLORINE));
        c.put("plan-b", "card-1", List.of(EMPTY_CELL));
        assertEquals(List.of(SODIUM, CHLORINE), c.get(List.of("plan-a"), "card-1"));
        assertEquals(List.of(EMPTY_CELL), c.get(List.of("plan-b"), "card-1"), "the same card id in another plan");
        assertNull(c.get(List.of("plan-a"), "card-2"));
        assertEquals(
            List.of(EMPTY_CELL),
            c.get(List.of("plan-c", "plan-b"), "card-1"),
            "a plan found by an id it had before (local, then on the account)");
        c.put("plan-a", "card-1", List.of(CHLORINE));
        assertEquals(List.of(CHLORINE), c.get(List.of("plan-a"), "card-1"), "the last choice wins");
    }

    /** A card whose machine name stands in for its recipe. */
    private static Node card(final String recipe, final int x) {
        final Node n = new Node(Uuid.randomUUID(), x, 0);
        n.machineName = recipe;
        return n;
    }

    @Test
    void patternThePlanTakesEachCardsRememberedChoice() {
        final Map<String, List<String>> packed = Map.of(
            "electrolyse",
            List.of(SODIUM, EMPTY_CELL, CHLORINE),
            "split water",
            List.of(HYDROGEN, "fluid:oxygen"),
            "macerate",
            List.of("item:gregtech:gt.metaitem.01:2032"));
        final Node electrolyse = card("electrolyse", 0), water = card("split water", 100),
            macerate = card("macerate", 200), again = card("electrolyse", 300);
        final PatternOutputs.Choices memory = new PatternOutputs.Choices();
        memory.put("plan", String.valueOf(electrolyse.id), List.of(SODIUM, CHLORINE));
        memory.put("other plan", String.valueOf(water.id), List.of("fluid:oxygen"));

        // As PatternThis.plan picks: one job per recipe, with the card's remembered products (null when none).
        record Job(String recipe, Node card, List<String> remembered) {}
        final BookmarkPlan.Pick<Job> pick = PatternPlan.pick(
            List.of(again, macerate, water, electrolyse),
            n -> new Job(n.machineName, n, memory.get(List.of("plan"), String.valueOf(n.id))),
            Job::recipe);
        final List<List<Integer>> slots = new ArrayList<>();
        for (final Job job : pick.recipes()) {
            final List<String> keys = packed.get(job.recipe());
            slots.add(
                PatternOutputs
                    .keep(keys, PatternOutputs.chosen(PatternOutputs.products(keys, keys), job.remembered())));
        }
        assertEquals(
            List.of(List.of(0, 2), List.of(0), List.of(0)),
            slots,
            "the electrolysis keeps its two remembered products; the others, never chosen in this plan, the first");
    }
}
