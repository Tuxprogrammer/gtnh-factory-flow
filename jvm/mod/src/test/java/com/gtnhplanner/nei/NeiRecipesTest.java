package com.gtnhplanner.nei;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.core.model.RecipeRef;
import com.gtnhplanner.core.model.RecipeSource;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Serializer;

import codechicken.nei.recipe.Recipe;

/**
 * The mod's recipe source: a card saved with NEI's recipe id keeps it byte for byte (written from NEI's own id, as
 * before), and a recipe NEI cannot find (none here: no game) leaves the card's saved ports as they were read.
 */
class NeiRecipesTest {

    private static final String SAVED = "{\"id\":\"00000000-0000-4000-8000-000000000001\",\"x\":10,\"y\":20,"
        + "\"machine\":\"Centrifuge\",\"recipeId\":{\"handlerName\":\"gregtech.nei.GTNEIDefaultHandler@gt.recipe.centrifuge\","
        + "\"ingredients\":[]},\"handlerRecipeIndex\":7,\"extractorIndex\":0,\"machineCount\":1,\"inputs\":[],\"outputs\":[]}";

    @BeforeAll
    static void defaultProfile() {
        com.gtnhplanner.harness.GtnhFlowLoader.ensureDefaultMachineProfile();
    }

    @Test
    void theNeiSourceIsInstalled() {
        assertSame(NeiRecipes.INSTANCE, RecipeSource.get());
    }

    @Test
    void aSavedRecipeIdIsWrittenBackAsNeiWritesIt() {
        final JsonObject saved = new JsonParser().parse(SAVED)
            .getAsJsonObject();
        final Node node = Serializer.jsonToNode(saved);
        final Recipe.RecipeId id = NeiRecipes.id(node);
        assertNotNull(id, "the live id is kept for the mod's NEI calls");
        assertEquals(7, node.recipe.index());
        assertEquals(
            id.toJsonObject()
                .toString(),
            node.recipe.id(),
            "the core's reference is NEI's id as the save writes it");
        final JsonObject written = Serializer.nodeToJson(node);
        assertEquals(
            Serializer.GSON.toJson(
                Recipe.RecipeId.of(saved.getAsJsonObject("recipeId"))
                    .toJsonObject()),
            Serializer.GSON.toJson(written.get("recipeId")));
        assertEquals(
            7,
            written.get("handlerRecipeIndex")
                .getAsInt());
        assertEquals(
            0,
            written.get("extractorIndex")
                .getAsInt());
        assertTrue(node.inputs.isEmpty(), "no game: NEI finds nothing and the card reads nothing");
        assertNull(node.record(), "no record for a card nobody could read");
    }

    @Test
    void aCardWithoutARecipeIdSavesNone() {
        final JsonObject saved = new JsonParser().parse(SAVED)
            .getAsJsonObject();
        saved.remove("recipeId");
        final Node node = Serializer.jsonToNode(saved);
        assertNull(NeiRecipes.id(node));
        assertNull(node.recipe.id());
        assertEquals(RecipeRef.of(null, 7), node.recipe, "the index is kept even without an id, as the save had it");
        final JsonObject written = Serializer.nodeToJson(node);
        assertFalse(written.has("recipeId"));
        assertEquals(
            7,
            written.get("handlerRecipeIndex")
                .getAsInt());
    }

    @Test
    void aReferenceWithoutTheHostsHoldIsWrittenFromItsText() {
        final Node node = new Node(com.gtnhplanner.core.base.Uuid.randomUUID(), 0, 0);
        node.recipe = RecipeRef.of("{\"handlerName\":\"x\",\"ingredients\":[]}", 2);
        assertEquals(
            "{\"handlerName\":\"x\",\"ingredients\":[]}",
            NeiRecipes.json(node)
                .toString());
    }
}
