package com.gtnhplanner.cloud.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.gtnhplanner.conformance.Conformance;
import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.importer.FakeGame;
import com.gtnhplanner.importer.FfImport;

/**
 * Cards the board only shows ({@link Node#shown}) through the cloud: a board opened from a plan shows them as the file
 * import does, a move reaches the plan, and a delete takes the card with its recipe and wires.
 */
class CloudShownCardsTest {

    private static Graph imported(final TestGame game) throws IOException, ReflectiveOperationException {
        final Field f = FakeGame.class.getDeclaredField("missing");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        final Set<String> missing = (Set<String>) f.get(game.fake);
        missing.add("r-gone");
        final Graph g = FfImport
            .importFromText(Conformance.text("codec/round-trip/display-cards.json"), game.fake, game.fake)
            .graph();
        game.knowing(g);
        return g;
    }

    private static Map<String, JsonObject> byId(final JsonObject plan, final String c) {
        final Map<String, JsonObject> out = new HashMap<>();
        for (final JsonObject o : PlanRead.objects(plan, c)) out.put(PlanRead.str(o, "id"), o);
        return out;
    }

    /** What a shown card is to the player, by the plan's id for it. */
    private static Map<String, String> shownOf(final Graph g) {
        final Map<String, String> out = new TreeMap<>();
        for (final Node n : g.getNodes())
            if (n.isShown()) out.put(n.shown.planId(), n.shown.reason() + "/" + n.shown.solved());
        return out;
    }

    private static List<String> touching(final JsonObject plan, final String card) {
        final List<String> out = new ArrayList<>();
        for (final JsonObject e : PlanRead.objects(plan, "edges"))
            if (card.equals(PlanRead.str(e, "source")) || card.equals(PlanRead.str(e, "target")))
                out.add(PlanRead.str(e, "id"));
        return out;
    }

    /** The wires and drawer links a card has on the board. */
    private static long wiresOn(final Graph g, final Node n) {
        long count = g.getEdges()
            .stream()
            .filter(e -> e.sourceNodeId.equals(n.id) || e.targetNodeId.equals(n.id))
            .count();
        for (final Drawer d : g.getDrawers()) count += d.getLinks()
            .stream()
            .filter(
                l -> l.nodeId()
                    .equals(n.id))
            .count();
        return count;
    }

    private static Node shown(final Graph g, final String planId) {
        for (final Node n : g.getNodes()) if (n.isShown() && planId.equals(n.shown.planId())) return n;
        throw new AssertionError("no shown card " + planId);
    }

    @Test
    void aBoardOpenedFromTheCloudShowsTheCardsTheFileImportShows() throws IOException, ReflectiveOperationException {
        final TestGame game = PlanRoundTripTest.routed(new TestGame(), true);
        final Graph file = imported(game);
        final CloudPlans.Written up = CloudPlans.write(file, game, null, null);
        final Graph cloud = CloudPlans.read(up.plan(), game)
            .graph();
        final Map<String, String> inFile = shownOf(file);
        assertFalse(inFile.isEmpty(), "the corpus holds cards the board shows");
        assertEquals(inFile, shownOf(cloud), "the same cards, for the same reasons");
        // A wire to a shown card is a wire on the board.
        final Node off = shown(cloud, "n-off");
        assertEquals(wiresOn(file, shown(file, "n-off")), wiresOn(cloud, off), "n-off keeps its wires");
        assertTrue(wiresOn(cloud, off) > 0);
    }

    @Test
    void aMoveAndADeleteOfShownCardsReachThePlan() throws IOException, ReflectiveOperationException {
        final TestGame game = PlanRoundTripTest.routed(new TestGame(), true);
        final Graph file = imported(game);
        final JsonObject first = CloudPlans.write(file, game, null, null)
            .plan();
        final CloudPlans.Read r = CloudPlans.read(first, game);
        final Graph g = r.graph();
        assertTrue(byId(first, "recipes").containsKey("r-gone"));
        final List<String> goneWires = touching(first, "n-gone");
        assertFalse(goneWires.isEmpty(), "the card has wires to lose");

        final Node off = shown(g, "n-off");
        off.x += 40;
        off.y += 20;
        final Node gone = shown(g, "n-gone");
        final Uuid goneId = gone.id;
        g.removeNode(goneId);

        final CloudPlans.Written saved = CloudPlans.write(g, game, first, r.basis());
        final JsonObject plan = saved.plan();
        final Map<String, JsonObject> nodes = byId(plan, "nodes");
        final JsonObject was = byId(first, "nodes").get("n-off"), now = nodes.get("n-off");
        assertNotNull(now);
        assertEquals(
            was.getAsJsonObject("position")
                .get("x")
                .getAsInt() + 40,
            now.getAsJsonObject("position")
                .get("x")
                .getAsInt(),
            "the move");
        assertEquals(
            was.getAsJsonObject("position")
                .get("y")
                .getAsInt() + 20,
            now.getAsJsonObject("position")
                .get("y")
                .getAsInt());
        assertFalse(nodes.containsKey("n-gone"), "the card is gone");
        assertFalse(byId(plan, "recipes").containsKey("r-gone"), "and its recipe");
        assertEquals(List.of(), touching(plan, "n-gone"), "and its wires");
        assertFalse(touching(plan, "n-off").isEmpty(), "the other cards' wires stay");
        for (final String id : new String[] { "n-vein", "n-infusion", "n-norecipe" })
            assertEquals(byId(first, "nodes").get(id), nodes.get(id), id + " untouched");
        // No wire names a card the plan lacks.
        for (final JsonObject e : byId(plan, "edges").values())
            for (final String end : new String[] { "source", "target" }) {
                final String ref = PlanRead.str(e, end);
                assertTrue(
                    nodes.containsKey(ref) || byId(plan, "storages").containsKey(ref),
                    "wire " + PlanRead.str(e, "id") + " names " + ref);
            }
        // Written again from the new basis nothing changes.
        final CloudPlans.Written again = CloudPlans.write(g, game, plan, saved.basis());
        assertEquals(List.of(), CloudPlans.diff(plan, again.plan()));
    }
}
