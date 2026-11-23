package com.gtnhplanner.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.gtnhplanner.client.dave.DaveRecipes;
import com.gtnhplanner.data.flowchart.Node;

/**
 * Ask Dave's recipe rows ({@link DaveRecipes}): the site's recipe made a card through the plan import, or none when the
 * game has no recipe like it. Here beside the fake game it imports against.
 */
class DaveRecipesTest {

    private static final FakeGame GAME = new FakeGame();

    private static JsonObject recipe(final int i) {
        return new Gson().fromJson(FakeGame.fixture("biodiesel-demo.json"), JsonObject.class)
            .getAsJsonArray("recipes")
            .get(i)
            .getAsJsonObject();
    }

    private static Node card(final String recipeJson) {
        final String plan = DaveRecipes.plan("site-id", recipeJson);
        assertNotNull(plan);
        return DaveRecipes.card(
            FfImport.importFromText(plan, GAME, GAME)
                .graph());
    }

    @Test
    void aRecipeTheGameHasBecomesACard() {
        final Node n = card(recipe(0).toString());
        assertNotNull(n);
        assertNull(n.shown);
        assertEquals(1, n.outputs.size());
    }

    @Test
    void aRecipeTheGameLacksMakesNone() {
        final FakeGame lacking = new FakeGame();
        lacking.missing.add("demo-fish-catcher");
        final String plan = DaveRecipes.plan("site-id", recipe(0).toString());
        assertNull(
            DaveRecipes.card(
                FfImport.importFromText(plan, lacking, lacking)
                    .graph()));
    }

    @Test
    void textThatIsNotARecipeIsRefused() {
        assertNull(DaveRecipes.plan("x", "[1,2]"));
        assertNull(DaveRecipes.plan("x", "not json {"));
        assertNull(DaveRecipes.plan("x", "{\"error\":\"Recipe not found.\"}"));
    }
}
