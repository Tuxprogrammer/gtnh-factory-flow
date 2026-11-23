package com.gtnhplanner.client.dave;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;

/**
 * A recipe Dave names, as a card: the site keeps its recipes by id ({@code GET <site>/api/datasets/<version>/recipes/
 * <id>}), and a plan of one card on that recipe goes through the same website-plan import every plan link uses, which
 * finds the game's own recipe by what it takes and makes. Pure: the read and the board are the caller's.
 */
public final class DaveRecipes {

    private DaveRecipes() {}

    /** A plan of one card on the site's recipe {@code recipeJson}; null when the text is not one recipe. */
    @Nullable
    public static String plan(final String recipeId, final String recipeJson) {
        final JsonElement e;
        try {
            e = new JsonParser().parse(recipeJson);
        } catch (final RuntimeException bad) {
            return null;
        }
        if (!(e instanceof final JsonObject recipe) || !recipe.has("id")) return null;
        // The card names the recipe as the site does; the site's answer may carry it under its own id spelling.
        final String id = recipe.get("id")
            .getAsString();
        final JsonObject card = new JsonObject();
        card.addProperty("id", "dave-card");
        card.addProperty("recipeId", id.isEmpty() ? recipeId : id);
        final JsonObject at = new JsonObject();
        at.addProperty("x", 0);
        at.addProperty("y", 0);
        card.add("position", at);
        final JsonArray recipes = new JsonArray(), nodes = new JsonArray();
        recipes.add(recipe);
        nodes.add(card);
        final JsonObject plan = new JsonObject();
        plan.addProperty("schemaVersion", 1);
        plan.addProperty("name", "Dave");
        plan.add("recipes", recipes);
        plan.add("nodes", nodes);
        plan.add("edges", new JsonArray());
        plan.add("storages", new JsonArray());
        return plan.toString();
    }

    /** The card the import made that this game can run; null when it found no recipe of its own like it. */
    @Nullable
    public static Node card(final Graph imported) {
        for (final Node n : imported.getNodes()) if (n.shown == null) return n;
        return null;
    }
}
