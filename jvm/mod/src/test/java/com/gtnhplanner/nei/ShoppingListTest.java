package com.gtnhplanner.nei;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/** {@code /gtnhplanner bookmark}: a plan's inputs and outputs as bookmarks, each once, and the line it says. */
class ShoppingListTest {

    @Test
    void inputsThenOutputsEachOnceAndNoGameItemCounted() {
        final ShoppingList.Pick<String> pick = ShoppingList.pick(
            Arrays.asList("ore", null, "water", "ore"),
            Arrays.asList("ingot", "water", null),
            Function.identity());
        assertEquals(List.of("ore", "water"), pick.inputs());
        assertEquals(List.of("ingot"), pick.outputs());
        assertEquals(2, pick.skipped());
    }

    @Test
    void theLineSaysWhatWasAddedAndWhatWasThere() {
        assertEquals(
            "Bookmarked 3 inputs and 2 outputs of Titanium line.",
            ShoppingList.added("Titanium line", 3, 2, 0, 0));
        assertEquals(
            "Bookmarked 1 input of Titanium line. 1 was already bookmarked.",
            ShoppingList.added("Titanium line", 1, 0, 1, 0));
        assertEquals(
            "Bookmarked 2 outputs of Titanium line. 3 were already bookmarked. Skipped 1 with no game item.",
            ShoppingList.added("Titanium line", 0, 2, 3, 1));
        assertEquals(
            "Everything Titanium line takes and makes is already in your bookmarks.",
            ShoppingList.added("Titanium line", 0, 0, 4, 0));
        assertEquals("Nothing in this plan to bookmark.", ShoppingList.added("", 0, 0, 0, 0));
    }
}
