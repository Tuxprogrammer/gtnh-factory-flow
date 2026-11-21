package com.gtnhplanner.nei;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.Recipe;
import codechicken.nei.recipe.RecipeHandlerRef;

/**
 * A recipe whose only output NEI does not count as a result (GregTech's bender making an empty bucket from an iron
 * plate) has an id with no result. NEI finds recipes again by result only, so the card had no machines and "Add another
 * recipe" refused it on its own machine. It is found on its handler's page by its ingredients.
 */
class RecipeRefsTest {

    private static final String BENDER = "gregtech.nei.GTNEIDefaultHandler@gt.recipe.metalbender";

    /** A handler page whose recipe i has ingredient lists of these sizes (the size is all NEI compares first). */
    private static IRecipeHandler page(final int... sizes) {
        return (IRecipeHandler) Proxy.newProxyInstance(
            IRecipeHandler.class.getClassLoader(),
            new Class<?>[] { IRecipeHandler.class },
            (proxy, method, args) -> switch (method.getName()) {
            case "numRecipes" -> sizes.length;
            case "getIngredientStacks" -> Collections.nCopies(sizes[(Integer) args[0]], null);
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> null;
            });
    }

    @Test
    void aRecipeWithNoResultIsFoundOnItsPageByItsIngredients() {
        final Recipe.RecipeId id = Recipe.RecipeId.of(null, BENDER, List.of());
        assertNull(id.getResult(), "an empty bucket is no result for NEI");
        assertNull(RecipeHandlerRef.of(id), "NEI's own lookup needs a result");
        final IRecipeHandler bender = page(2, 2, 0, 0);
        final RecipeHandlerRef ref = RecipeRefs.onItsPage(id, List.of(page(1), bender));
        assertNotNull(ref, "found on its own page");
        assertSame(bender, ref.handler);
        assertEquals(2, ref.recipeIndex, "the first recipe with its ingredients");
    }

    @Test
    void aRecipeOnNoPageStaysUnfound() {
        final Recipe.RecipeId id = Recipe.RecipeId.of(null, BENDER, List.of());
        assertNull(RecipeRefs.onItsPage(id, List.of(page(1, 2))));
        assertNull(RecipeRefs.onItsPage(id, List.of()));
    }
}
