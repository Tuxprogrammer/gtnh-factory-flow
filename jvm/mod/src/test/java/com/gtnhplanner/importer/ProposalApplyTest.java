package com.gtnhplanner.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.UndoHistories;
import com.gtnhplanner.data.flowchart.UndoHistory;

/**
 * A proposal from the site's assistant added to the open plan: the biodiesel demo split in two, its first four cards
 * the plan, the other three and their wires (three of them to the plan's own cards) the proposal.
 */
class ProposalApplyTest {

    private static final Set<String> BASE = Set
        .of("node-fish-catcher", "node-fish-oil", "node-methane-source", "node-electrolyzer");

    private record Board(Graph graph, Map<String, String> ids, FakeGame game) {}

    private static JsonObject demo() {
        return new Gson().fromJson(FakeGame.fixture("biodiesel-demo.json"), JsonObject.class);
    }

    /** The plan: the demo's first four cards and the wires between them. */
    private static Board base() {
        final JsonObject plan = demo();
        final JsonArray nodes = new JsonArray(), edges = new JsonArray();
        for (final JsonElement n : plan.getAsJsonArray("nodes")) if (BASE.contains(id(n))) nodes.add(n);
        for (final JsonElement e : plan.getAsJsonArray("edges"))
            if (BASE.contains(str(e, "source")) && BASE.contains(str(e, "target"))) edges.add(e);
        plan.add("nodes", nodes);
        plan.add("edges", edges);
        plan.add("storages", new JsonArray());
        plan.remove("targetRate");
        final FakeGame game = new FakeGame();
        final FfConverter.Result r = FfImport.importFromText(plan.toString(), game, game);
        return new Board(
            r.graph(),
            r.placed()
                .cards(),
            game);
    }

    /**
     * The proposal: the other three cards at {@code x, y} and to its right, their wires, the plan's own ends by their
     * board ids, and {@code targets}.
     */
    private static JsonObject proposal(final Board b, final int x, final int y, final JsonArray targets) {
        final JsonObject demo = demo(), out = new JsonObject();
        out.addProperty("version", 1);
        out.addProperty(
            "basePlanId",
            b.graph()
                .planId());
        out.add("recipes", demo.get("recipes"));
        final JsonArray cards = new JsonArray(), wires = new JsonArray();
        int n = 0;
        for (final JsonElement c : demo.getAsJsonArray("nodes")) {
            if (BASE.contains(id(c))) continue;
            final JsonObject at = new JsonObject();
            at.addProperty("x", x + 400 * n++);
            at.addProperty("y", y);
            c.getAsJsonObject()
                .add("position", at);
            cards.add(c);
        }
        for (final JsonElement e : demo.getAsJsonArray("edges")) {
            final JsonObject w = e.getAsJsonObject();
            if (BASE.contains(str(w, "source")) && BASE.contains(str(w, "target"))
                || "demo-carbon-drawer".equals(str(w, "target"))) continue;
            for (final String end : List.of("source", "target")) if (BASE.contains(str(w, end))) w.addProperty(
                end,
                b.ids()
                    .get(str(w, end)));
            wires.add(w);
        }
        out.add("cards", cards);
        out.add("drawers", new JsonArray());
        out.add("wires", wires);
        out.add("targets", targets);
        return out;
    }

    private static ProposalApply.Prepared prepared(final Board b, final JsonObject proposal) {
        return ProposalApply.prepare(proposal, text -> FfImport.importFromText(text, b.game(), b.game()));
    }

    private static ProposalApply.Outcome add(final Board b, final JsonObject proposal) {
        return prepared(b, proposal).addTo(b.graph(), new FfCards.Ports(b.game()));
    }

    @Test
    void addsTheCardsAndWiresAndChangesNothingElse() {
        final Board b = base();
        final Map<Uuid, int[]> before = new HashMap<>();
        for (final Node n : b.graph()
            .getNodes()) before.put(n.id, new int[] { n.x, n.y });
        final Set<Uuid> edges = Set.copyOf(b.graph().edges.keySet());
        assertEquals(4, before.size());
        assertEquals(2, edges.size());

        final ProposalApply.Outcome out = add(b, proposal(b, 3000, 0, new JsonArray()));

        assertEquals(3, out.cards());
        assertEquals(5, out.wires());
        assertEquals(0, out.wiresGone() + out.wiresNoPort() + out.cardsLeftOut());
        assertEquals(7, b.graph().nodes.size());
        assertEquals(7, b.graph().edges.size());
        for (final Map.Entry<Uuid, int[]> e : before.entrySet()) {
            final Node n = b.graph().nodes.get(e.getKey());
            assertEquals(e.getValue()[0], n.x, "the plan's own cards stay where they are");
            assertEquals(e.getValue()[1], n.y);
        }
        assertTrue(
            b.graph().edges.keySet()
                .containsAll(edges));
        // Three of the wires end on the plan's own cards.
        int toOwn = 0;
        for (final Edge e : b.graph()
            .getEdges())
            if (!edges.contains(e.id) && (before.containsKey(e.sourceNodeId) || before.containsKey(e.targetNodeId)))
                toOwn++;
        assertEquals(3, toOwn);
        assertEquals("Added 3 cards and 5 wires to Plan.", out.message("Plan"));
    }

    @Test
    void theFirstCardLandsWhereTheProposalPutsIt() {
        final Board b = base();
        final ProposalApply.Outcome out = add(b, proposal(b, 3000, 500, new JsonArray()));
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        for (final Uuid id : out.added()) {
            minX = Math.min(minX, b.graph().nodes.get(id).x);
            minY = Math.min(minY, b.graph().nodes.get(id).y);
        }
        assertEquals(3000, minX);
        assertEquals(500, minY);
    }

    @Test
    void cardsThatWouldLieOnThePlansAreMovedClear() {
        final Board b = base();
        final int[] box = ProposalApply.box(b.graph(), 0, 0);
        final Graph only = new Graph();
        final ProposalApply.Outcome out = add(b, proposal(b, box[0], box[1], new JsonArray()));
        for (final Uuid id : out.added()) {
            final Node n = b.graph().nodes.get(id);
            assertTrue(n.x >= box[2] + ProposalApply.GAP, "moved right past the plan's last card");
            only.addNode(n);
        }
        final Graph own = new Graph();
        for (final Node n : b.graph()
            .getNodes())
            if (!out.added()
                .contains(n.id)) own.addNode(n);
        assertFalse(ProposalApply.overlaps(only, 0, 0, own));
    }

    @Test
    void oneUndoStepPutsThePlanBackExactly() {
        final Board b = base();
        final UndoHistories histories = new UndoHistories();
        final UndoHistory history = histories.of(b.graph());
        final JsonObject proposal = proposal(b, 3000, 0, new JsonArray());
        final Map<Uuid, int[]> own = new HashMap<>();
        for (final Node n : b.graph()
            .getNodes()) own.put(n.id, new int[] { n.x, n.y });
        final Set<Uuid> wires = Set.copyOf(b.graph().edges.keySet());
        final String before = history.beginEdit(b.graph());
        final ProposalApply.Prepared p = prepared(b, proposal);
        final String start = history.beginEdit(b.graph());
        p.addTo(b.graph(), new FfCards.Ports(b.game()));
        assertTrue(history.commitEdit(start, b.graph()), "adding is an edit");
        assertEquals(before, start, "reading the proposal changes nothing");
        final Graph undone = history.undo(b.graph());
        assertFalse(history.canUndo(), "one step");
        assertTrue(p.fits(undone), "still the plan the proposal was made for");
        // The undo is the snapshot taken before the edit: the plan as it was, the same things where they were.
        assertFalse(before.isEmpty());
        assertEquals(own.keySet(), undone.nodes.keySet());
        assertEquals(wires, undone.edges.keySet());
        for (final Node n : undone.getNodes()) {
            assertEquals(own.get(n.id)[0], n.x);
            assertEquals(own.get(n.id)[1], n.y);
        }
    }

    @Test
    void anotherPlanIsRefused() {
        final Board b = base();
        final ProposalApply.Prepared p = prepared(b, proposal(b, 3000, 0, new JsonArray()));
        assertTrue(p.fits(b.graph()));
        assertFalse(p.fits(base().graph()), "a plan with another id");
        assertFalse(p.fits(new Graph()));
    }

    @Test
    void aWireToACardThatIsGoneIsDroppedAndCounted() {
        final Board b = base();
        final JsonObject proposal = proposal(b, 3000, 0, new JsonArray());
        b.graph()
            .removeNode(
                Uuid.fromString(
                    b.ids()
                        .get("node-fish-oil")));
        final ProposalApply.Outcome out = add(b, proposal);
        assertEquals(3, out.cards());
        assertEquals(4, out.wires());
        assertEquals(1, out.wiresGone());
        assertEquals(
            "Added 3 cards and 4 wires to Plan. 1 wire left out: a card it joins is gone.",
            out.message("Plan"));
    }

    @Test
    void targetsAreSetOnPortsWithNoneAndKeptWhereThePlayerSetOne() {
        final Board b = base();
        final Node electrolyzer = b.graph().nodes.get(
            Uuid.fromString(
                b.ids()
                    .get("node-electrolyzer")));
        final int hydrogen = new FfCards.Ports(b.game()).find(electrolyzer, true, "fluid", List.of("hydrogen"));
        electrolyzer.targetOutputRates.put(hydrogen, 5d);
        final JsonArray targets = new JsonArray();
        targets.add(target("node-biodiesel", "output:fluid:biodiesel", 2));
        targets.add(
            target(
                b.ids()
                    .get("node-electrolyzer"),
                "output:fluid:hydrogen",
                9));
        targets.add(
            target(
                b.ids()
                    .get("node-electrolyzer"),
                "output:item:carbon_dust",
                1));
        final ProposalApply.Outcome out = add(b, proposal(b, 3000, 0, targets));
        assertEquals(2, out.targets());
        assertEquals(1, out.targetsKept());
        assertEquals(5d, electrolyzer.targetOutputRates.get(hydrogen), "the player's own rate stays");
        assertEquals(2, electrolyzer.targetOutputRates.size());
        assertEquals("Added 3 cards, 5 wires and 2 targets to Plan. 1 target kept as you set it.", out.message("Plan"));
    }

    @Test
    void aProposalThatDoesNotReadIsRefused() {
        final Board b = base();
        final JsonObject bad = proposal(b, 0, 0, new JsonArray());
        bad.remove("basePlanId");
        assertThrows(FfImportException.class, () -> prepared(b, bad));
        final JsonObject later = proposal(b, 0, 0, new JsonArray());
        later.addProperty("version", 2);
        assertThrows(FfImportException.class, () -> prepared(b, later));
    }

    @Test
    void messages() {
        assertEquals(
            "Added 1 card and 1 wire to Platline.",
            new ProposalApply.Outcome(1, 0, 1, 0, 0, 0, 0, 0, 0, List.of()).message("Platline"));
        assertEquals(
            "Added 2 cards, 1 drawer and 4 wires to Platline. 1 card could not be made in this game."
                + " 2 wires left out: no port takes it.",
            new ProposalApply.Outcome(2, 1, 4, 0, 1, 0, 2, 0, 0, List.of()).message("Platline"));
        assertEquals(
            "Nothing was added to Platline. 2 targets kept as you set them."
                + " 1 target left out: its card is gone or has no such port.",
            new ProposalApply.Outcome(0, 0, 0, 0, 0, 0, 0, 2, 1, List.of()).message("Platline"));
    }

    private static JsonObject target(final String card, final String port, final double rate) {
        final JsonObject t = new JsonObject();
        t.addProperty("card", card);
        t.addProperty("port", port);
        t.addProperty("rate", rate);
        return t;
    }

    private static String id(final JsonElement e) {
        return str(e, "id");
    }

    private static String str(final JsonElement e, final String key) {
        final JsonElement v = e.getAsJsonObject()
            .get(key);
        return v == null ? "" : v.getAsString();
    }
}
