package com.gtnhplanner.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.importer.FfPlan.FfRecipe;
import com.gtnhplanner.importer.RecipeIndex.GameRecipe;

/**
 * A plan saved before the game moved IC2's plantball to a GregTech id, read in a game that has the new id only: its
 * cards match through the game's renames and its wires and drawers follow, under the website's rule.
 */
class PlanRenamesTest {

    private static final String OLD = "ic2:itemfuelplantball", NEW = "gregtech:gt.metaitem.02@32020";
    private static final Map<String, String> TABLE = Map.of("ic2:itemfuelplantball@*", NEW);

    private static final String PLAN = """
        {"name": "Plantballs",
         "recipes": [
           {"id": "mac", "name": "Macerator: Plantball", "machineType": "Macerator", "durationTicks": 100, "eut": 2,
            "inputs": [{"kind": "item", "id": "a:sapling", "amount": 4}],
            "outputs": [{"kind": "item", "id": "ic2:itemfuelplantball", "amount": 1, "displayName": "Plantball"}]},
           {"id": "comp", "name": "Compressor: Coal", "machineType": "Compressor", "durationTicks": 300, "eut": 2,
            "inputs": [{"kind": "item", "id": "ic2:itemfuelplantball", "amount": 2, "displayName": "Plantball"}],
            "outputs": [{"kind": "item", "id": "a:coal", "amount": 1}]}%s],
         "nodes": [
           {"id": "m", "recipeId": "mac", "machineCount": 1, "parallel": 1, "overclockTier": "LV", "enabled": true,
            "position": {"x": 0, "y": 0}},
           {"id": "c", "recipeId": "comp", "machineCount": 1, "parallel": 1, "overclockTier": "LV", "enabled": true,
            "position": {"x": 400, "y": 0}}%s],
         "edges": [
           {"id": "w1", "source": "m", "target": "c", "sourceHandle": "output:item:ic2%%3Aitemfuelplantball",
            "targetHandle": "input:item:ic2%%3Aitemfuelplantball", "resourceKind": "item",
            "resourceId": "ic2:itemfuelplantball"},
           {"id": "w2", "source": "m", "target": "pb", "sourceHandle": "output:item:ic2%%3Aitemfuelplantball",
            "resourceKind": "item", "resourceId": "ic2:itemfuelplantball"}],
         "storages": [
           {"id": "pb", "kind": "item", "resourceId": "ic2:itemfuelplantball", "displayName": "Plantball",
            "position": {"x": 400, "y": 300}}],
         "gtnhplanner": {"kept": "ic2:itemfuelplantball"}}
        """;

    /** A card the game still runs on the old id: it keeps that id everywhere. */
    private static final String OLD_RECIPE = """
        ,{"id": "old", "name": "Extractor: Dirt", "machineType": "Extractor", "durationTicks": 20, "eut": 2,
          "inputs": [{"kind": "item", "id": "ic2:itemfuelplantball", "amount": 1}],
          "outputs": [{"kind": "item", "id": "a:dirt", "amount": 1}]}""";
    private static final String OLD_NODE = """
        ,{"id": "o", "recipeId": "old", "machineCount": 1, "parallel": 1, "overclockTier": "LV", "enabled": true,
          "position": {"x": 400, "y": 600}}""";

    /** The game: the plan's macerator and compressor on the new id, the extractor still on the old one. */
    private static final class Game implements RecipeIndex {

        final List<GameRecipe> recipes = new ArrayList<>();
        final Map<String, String> renames;

        Game(final Map<String, String> renames) {
            this.renames = renames;
            final JsonObject plan = new JsonParser().parse(PLAN.formatted(OLD_RECIPE, ""))
                .getAsJsonObject();
            for (final var e : plan.getAsJsonArray("recipes")) {
                final String json = e.toString();
                final FfRecipe r = FfPlanParser.recipe(
                    new JsonParser().parse(json.contains("\"old\"") ? json : json.replace(OLD, NEW))
                        .getAsJsonObject());
                recipes.add(FakeGame.twin(r));
            }
        }

        @Override
        public Lookup find(final FfRecipe recipe) {
            return Lookup.of(recipes);
        }

        @Override
        public Map<String, String> renames() {
            return renames;
        }
    }

    private static JsonObject plan(final boolean withOld) {
        return new JsonParser().parse(PLAN.formatted(withOld ? OLD_RECIPE : "", withOld ? OLD_NODE : ""))
            .getAsJsonObject();
    }

    @Test
    void movesRecipesWiresAndDrawersToTheNewId() {
        final JsonObject before = plan(false);
        final JsonObject after = PlanRenames.apply(before, new Game(TABLE));
        final String text = after.toString();
        assertFalse(
            text.replace("\"kept\":\"" + OLD + "\"", "")
                .contains("itemfuelplantball"),
            "every mention outside the game's own data moved: " + text);
        final JsonObject w1 = after.getAsJsonArray("edges")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            "output:item:gregtech%3Agt.metaitem.02%4032020",
            w1.get("sourceHandle")
                .getAsString());
        assertEquals(
            NEW,
            w1.get("resourceId")
                .getAsString());
        final JsonObject drawer = after.getAsJsonArray("storages")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            NEW,
            drawer.get("resourceId")
                .getAsString());
        assertFalse(drawer.has("displayName"), "no recipe of the plan names the new item: the game names it");
        assertEquals(
            OLD,
            after.getAsJsonObject("gtnhplanner")
                .get("kept")
                .getAsString());
        assertSame(before.get("nodes"), after.get("nodes"), "what did not move keeps its identity");
    }

    @Test
    void anOldIdACardTheGameRunsStillNamesIsKept() {
        final JsonObject before = plan(true);
        assertSame(before, PlanRenames.apply(before, new Game(TABLE)));
    }

    @Test
    void withoutRenamesThePlanIsUntouched() {
        final JsonObject before = plan(false);
        assertSame(before, PlanRenames.apply(before, new Game(Map.of())));
    }

    @Test
    void theCardsRunInsteadOfShowing() {
        final FakeGame maker = new FakeGame();
        final FfConverter.Result stale = FfImport.importFromText(plan(false).toString(), new Game(Map.of()), maker);
        assertEquals(
            2,
            stale.graph()
                .getNodes()
                .stream()
                .filter(n -> n.shown != null)
                .count(),
            "without the renames both cards are view only");

        final FfConverter.Result read = FfImport.importFromText(plan(false).toString(), new Game(TABLE), maker);
        final Graph g = read.graph();
        assertEquals(2, g.nodes.size());
        assertTrue(
            g.getNodes()
                .stream()
                .allMatch(n -> n.shown == null),
            "both cards run");
        assertTrue(
            read.report()
                .entries(ImportReport.Kind.UNMATCHED)
                .isEmpty());
        assertEquals(1, g.edges.size(), "the macerator still feeds the compressor");
        assertEquals(1, g.drawers.size());
        assertNotNull(
            g.drawers.values()
                .iterator()
                .next());
    }
}
