package com.gtnhplanner.cloud.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.cloud.sync.LiveOp;
import com.gtnhplanner.cloud.sync.LiveOps;
import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.flowchart.Node;

/**
 * A shared plan saved before the game renamed an item (IC2's plantball, now a GregTech id): the rename is read once
 * per load and stays read. A later read of the live plan takes over the renamed cards nobody touched, and the board
 * written against it never sends the rename as an edit.
 */
public class CloudRenamesTest {

    public static final String OLD = "ic2:itemfuelplantball", NEW = "gregtech:gt.metaitem.02@32020";

    /** A macerator making plantballs into a compressor and a drawer, every card the website's. */
    public static JsonObject plan() {
        return new JsonParser().parse("""
            {"id": "6f9619ff-8b86-4d01-b42d-00cf4fc964ff", "name": "Plantballs", "solveMode": "plan",
             "recipes": [
               {"id": "mac", "name": "Macerator: Plantball", "machineType": "Macerator", "durationTicks": 100,
                "eut": 2, "inputs": [{"kind": "item", "id": "a:sapling", "amount": 4}],
                "outputs": [{"kind": "item", "id": "ic2:itemfuelplantball", "amount": 1, "displayName": "Plantball"}]},
               {"id": "comp", "name": "Compressor: Coal", "machineType": "Compressor", "durationTicks": 300,
                "eut": 2,
                "inputs": [{"kind": "item", "id": "ic2:itemfuelplantball", "amount": 2, "displayName": "Plantball"}],
                "outputs": [{"kind": "item", "id": "a:coal", "amount": 1}]}],
             "nodes": [
               {"id": "m", "recipeId": "mac", "machineCount": 1, "parallel": 1, "overclockTier": "LV",
                "enabled": true, "position": {"x": 0, "y": 0}},
               {"id": "c", "recipeId": "comp", "machineCount": 1, "parallel": 1, "overclockTier": "LV",
                "enabled": true, "position": {"x": 400, "y": 0}}],
             "edges": [
               {"id": "w1", "source": "m", "target": "c", "sourceHandle": "output:item:ic2%3Aitemfuelplantball",
                "targetHandle": "input:item:ic2%3Aitemfuelplantball", "resourceKind": "item",
                "resourceId": "ic2:itemfuelplantball"}],
             "storages": [],
             "annotations": []}
            """)
            .getAsJsonObject();
    }

    /** {@code game} renamed the plantball and no longer has the old id. */
    public static TestGame renaming(final TestGame game) {
        game.fake.renames.put(OLD + "@*", NEW);
        game.fake.gone.add(OLD);
        return game;
    }

    /** A website card's id on the board. */
    public static Uuid card(final String id) {
        return PlanIds.uuid(id);
    }

    public static JsonObject position(final double x, final double y) {
        final JsonObject at = new JsonObject(), f = new JsonObject();
        at.addProperty("x", x);
        at.addProperty("y", y);
        f.add("position", at);
        return f;
    }

    private static JsonObject recipe(final JsonObject plan, final String id) {
        for (final JsonElement r : plan.getAsJsonArray("recipes")) if (id.equals(
            r.getAsJsonObject()
                .get("id")
                .getAsString()))
            return r.getAsJsonObject();
        return null;
    }

    @Test
    void theRenamedCardsRunAndAnotherReadTakesOverTheOnesNobodyTouched() {
        final TestGame game = renaming(new TestGame());
        final JsonObject plan = plan();
        final CloudPlans.Read first = CloudPlans.read(plan, game);
        final Node mac = first.graph().nodes.get(card("m"));
        assertNotNull(mac);
        assertNull(mac.shown, "the renamed card runs");
        assertEquals(1, first.graph().edges.size(), "its wire found the renamed ports");
        assertNotSame(recipe(plan, "mac"), recipe(first.plan(), "mac"), "the recipe was renamed");

        // Someone moves the compressor: the macerator is the very object it was, and so is its renamed recipe.
        final JsonObject moved = LiveOps.apply(plan, List.of(LiveOp.patch("nodes", "c", position(500, 0), null)));
        final CloudPlans.Read next = CloudPlans.read(moved, game, first);
        assertSame(recipe(first.plan(), "mac"), recipe(next.plan(), "mac"), "renamed once, not again");
        assertSame(mac, next.graph().nodes.get(card("m")), "the untouched renamed card is not read again");
        assertEquals(1, next.graph().edges.size());

        // Written back against what it was read from, nothing goes: the rename is not an edit.
        assertEquals(
            List.of(),
            CloudPlans.diff(
                next.plan(),
                CloudPlans.write(next.graph(), game, next.plan(), next.basis())
                    .plan()));
    }
}
