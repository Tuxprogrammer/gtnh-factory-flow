package com.gtnhplanner.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.importer.FakeGame;
import com.gtnhplanner.importer.FfImport;

/** Add to plan (the chat command's and the Ask Dave panel's) checks a change against the open plan before it adds. */
class DeliveriesTest {

    private static final FakeGame GAME = new FakeGame();

    private static JsonObject demo() {
        return new Gson().fromJson(FakeGame.fixture("biodiesel-demo.json"), JsonObject.class);
    }

    /** The demo's first card alone, as an open plan. */
    private static Graph open() {
        final JsonObject plan = demo();
        final JsonArray one = new JsonArray();
        one.add(
            plan.getAsJsonArray("nodes")
                .get(0));
        plan.add("nodes", one);
        plan.add("edges", new JsonArray());
        plan.add("storages", new JsonArray());
        plan.remove("targetRate");
        return FfImport.importFromText(plan.toString(), GAME, GAME)
            .graph();
    }

    /** A delivery adding the demo's last card to the plan {@code base}. */
    private static JsonObject delivery(final String base) {
        final JsonObject demo = demo(), proposal = new JsonObject();
        proposal.addProperty("version", 1);
        proposal.addProperty("basePlanId", base);
        proposal.add("recipes", demo.get("recipes"));
        final JsonArray nodes = demo.getAsJsonArray("nodes"), cards = new JsonArray();
        final JsonElement last = nodes.get(nodes.size() - 1);
        cards.add(last);
        proposal.add("cards", cards);
        proposal.add("drawers", new JsonArray());
        proposal.add("wires", new JsonArray());
        proposal.add("targets", new JsonArray());
        final JsonObject body = new JsonObject();
        body.addProperty("kind", "proposal");
        body.add("proposal", proposal);
        return body;
    }

    private static Deliveries.Checked check(final JsonObject body, final Graph open) {
        return Deliveries.check(body, open, text -> FfImport.importFromText(text, GAME, GAME));
    }

    @Test
    void aChangeForTheOpenPlanIsReady() {
        final Graph plan = open();
        final Deliveries.Checked c = check(delivery(plan.planId()), plan);
        assertNull(c.refusal());
        assertNotNull(c.prepared());
    }

    @Test
    void aChangeMadeForAnotherPlanIsRefused() {
        final Graph plan = open(), other = open();
        final Deliveries.Checked c = check(delivery(other.planId()), plan);
        assertNull(c.prepared());
        assertEquals(DeliveryText.OTHER_PLAN, c.refusal());
    }

    @Test
    void withNoPlanOpenNothingIsRead() {
        assertEquals(
            DeliveryText.NO_PLAN,
            Deliveries.check(delivery("x"), null, text -> { throw new AssertionError("read with no plan open"); })
                .refusal());
    }
}
