package com.gtnhplanner.cloud.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.core.codec.Canon;
import com.gtnhplanner.core.codec.PlanJson;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Group;
import com.gtnhplanner.data.flowchart.MachineGroup;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.PinRule;
import com.gtnhplanner.harness.TestIngredients;
import com.gtnhplanner.importer.FfImport;
import com.gtnhplanner.library.PlanExport;
import com.gtnhplanner.ui.CardRecipes;

/**
 * A card's recipe rows on the board model: "Add another recipe" puts the pick on the card as one more section, and the
 * plan goes out as the website writes a card it added the recipe to; "Replace the recipe" puts the pick in the card's
 * place, its wires following their resources.
 */
class CardRecipesTest {

    /** A website plan with two shared machines: a centrifuge of two recipes and a chemical reactor of four. */
    private static final String PLAN = "alumina-line-shared-reactor";
    /** A website plan of cards of their own, wired. */
    private static final String LINE = "biodiesel-demo";

    private record Board(Graph graph, TestGame game) {}

    private static Board imported() {
        return imported(PLAN);
    }

    private static Board imported(final String plan) {
        final TestGame game = new TestGame();
        final Graph g = FfImport.importFromText(
            Boards.website(plan)
                .toString(),
            game.fake,
            game.fake)
            .graph();
        game.knowing(g);
        return new Board(g, game);
    }

    /** The plan as it goes out, less its own id (a new one each time). */
    private static Map<String, Object> export(final Board b) {
        final Map<String, Object> plan = PlanJson.parse(
            PlanExport.project(b.graph(), PLAN, b.game())
                .toString());
        plan.remove("id");
        return plan;
    }

    private static List<MachineGroup> machines(final Graph g) {
        final List<MachineGroup> out = new ArrayList<>();
        for (final Group grp : g.getGroups()) if (grp instanceof final MachineGroup m && m.isShared()) out.add(m);
        out.sort((a, b) -> Integer.compare(a.getSections().size(), b.getSections().size()));
        return out;
    }

    /**
     * Each shared machine's last recipe taken off and added back: the plan that goes out is the website's own with that
     * recipe on the card and none of its wires (an added recipe has none yet). The two-recipe centrifuge starts as a
     * card of its own, so the add makes the shared machine; the reactor's joins the one it has.
     */
    @Test
    void aRecipeAddedToACardGoesOutAsTheWebsitesSharedMachine() {
        final int count = machines(imported().graph()).size();
        assertEquals(2, count, "the plan's shared machines");
        for (int which = 0; which < count; which++) {
            final Board b = imported();
            final Graph g = b.graph();
            final MachineGroup machine = machines(g).get(which);
            final int sections = machine.getSections()
                .size();
            final Uuid hostId = machine.getSections()
                .getFirst(),
                last = machine.getSections()
                    .getLast();
            final Node section = g.nodes.get(last);
            // What the website writes for the card with that recipe on it and none of its wires.
            g.removeNode(last);
            g.addNode(section);
            final String want = Canon.of(export(b));

            // The board before the add: the card without that recipe.
            g.removeNode(last);
            machine.removeSection(last);
            final Node host = g.nodes.get(hostId);
            if (!machine.isShared()) {
                PinRule.set(host, PinRule.of(machine), machine.machineCount());
                g.removeGroup(machine.getId());
                assertNull(CardRecipes.machineOf(g, hostId), "a card of its own before the add");
            }

            final MachineGroup after = CardRecipes.join(g, host, section, null);
            assertEquals(
                sections,
                after.getSections()
                    .size(),
                "every recipe on the card again");
            assertEquals(
                last,
                after.getSections()
                    .getLast(),
                "the pick is the last section");
            assertEquals(want, Canon.of(export(b)), "shared machine " + which + " goes out whole");
        }
    }

    /** The newcomer takes the card's machine and machine settings and keeps its own recipe's. */
    @Test
    void aJoinedRecipeRunsOnTheCardsMachine() {
        final Board b = imported();
        final MachineGroup m = machines(b.graph()).getFirst();
        final Node host = b.graph().nodes.get(
            m.getSections()
                .getFirst());
        final Node section = b.graph().nodes.get(
            m.getSections()
                .getLast());
        b.graph()
            .removeNode(section.id);
        m.removeSection(section.id);
        b.graph()
            .removeGroup(m.getId());
        section.machineName = "somewhere:else";
        section.machineConfig.settings.put("voltage", "ZPM");
        section.machineConfig.settings.put("duration_ticks", 1234);
        section.x = host.x + 900;
        CardRecipes.join(b.graph(), host, section, "a:machine");
        assertEquals("a:machine", host.machineName, "the card switches to the machine that runs both");
        assertEquals(host.machineName, section.machineName);
        assertEquals(host.machineConfig.settings.get("voltage"), section.machineConfig.settings.get("voltage"));
        assertEquals(1234, section.machineConfig.settings.get("duration_ticks"), "its own recipe's time stays");
        assertEquals(host.x, section.x, "it sits on the card");
        assertFalse(section.isMachineCountFixed(), "the machine's count is the card's");
    }

    private static List<Edge> wiresOn(final Graph g, final Uuid id) {
        final List<Edge> out = new ArrayList<>();
        for (final Edge e : g.getEdges()) if (e.sourceNodeId.equals(id) || e.targetNodeId.equals(id)) out.add(e);
        return out;
    }

    /**
     * A card replaced by another plan card's recipe: it keeps its id, place and count rule, and goes out with the new
     * recipe; a wire whose resource the new recipe has stays on its port there, every other wire goes.
     */
    @Test
    void aReplacedCardKeepsItsPlaceAndTheWiresThatStillFit() {
        final Board b = imported(LINE);
        final Graph g = b.graph();
        Node old = null, other = null;
        for (final Node n : g.getNodes()) {
            if (CardRecipes.machineOf(g, n.id) != null || wiresOn(g, n.id).isEmpty()) continue;
            if (old == null) old = n;
            else if (other == null) other = n;
        }
        assertNotNull(other, "two cards of their own with wires");
        // The pick, as NEI's + makes it: a card of the other recipe that is not on the board.
        final Node fresh = same(imported(LINE).graph(), other);
        fresh.id = Uuid.randomUUID();
        PinRule.set(old, PinRule.EXACT, 3);
        final Map<String, String> recipesBefore = recipes(export(b));
        final Uuid id = old.id;
        final int x = old.x, y = old.y;
        final List<String> ins = TestGame.names(old.inputs), outs = TestGame.names(old.outputs);
        final List<String> nowIns = TestGame.names(fresh.inputs), nowOuts = TestGame.names(fresh.outputs);
        int keep = 0;
        for (final Edge e : wiresOn(g, id)) {
            if (e.sourceNodeId.equals(id) && nowOuts.contains(outs.get(e.sourceOutputIndex))) keep++;
            if (e.targetNodeId.equals(id) && nowIns.contains(ins.get(e.targetInputIndex))) keep++;
        }
        assertEquals(keep > 0, CardRecipes.fits(g, old, fresh, TestIngredients::nameOf), "fits when a wire survives");
        CardRecipes.replace(g, old, fresh, TestIngredients::nameOf);
        // NEI reads the card's recipe from the card it now is.
        b.game()
            .knowing(g);
        final Node now = g.nodes.get(id);
        assertTrue(now == fresh, "the pick is the card");
        assertEquals(x, now.x);
        assertEquals(y, now.y);
        assertEquals(PinRule.EXACT, PinRule.of(now), "its count rule stays");
        assertEquals(3.0, now.machineConfig.machineCount(), 1e-9);
        assertEquals(keep, wiresOn(g, id).size(), "the wires that still fit");
        for (final Edge e : wiresOn(g, id)) {
            if (e.sourceNodeId.equals(id)) assertTrue(e.sourceOutputIndex < now.outputs.size());
            if (e.targetNodeId.equals(id)) assertTrue(e.targetInputIndex < now.inputs.size());
        }
        // It goes out with the other card's recipe; nothing else on the plan changes its recipe.
        final Map<String, String> after = recipes(export(b));
        final List<String> changed = new ArrayList<>();
        for (final Map.Entry<String, String> e : recipesBefore.entrySet()) if (!e.getValue()
            .equals(after.get(e.getKey()))) changed.add(e.getKey());
        assertEquals(List.of("gtnhplanner:" + id), changed, "one card took another recipe");
        assertEquals(after.get("gtnhplanner:" + other.id), after.get("gtnhplanner:" + id), "the other card's recipe");
        final Map<String, Object> card = cards(export(b)).get(id.toString());
        assertNotNull(card, "the card goes out under its id");
        assertEquals(3.0, ((Number) card.get("solvePin")).doubleValue(), 1e-9);
        assertEquals(Boolean.TRUE, card.get("solvePinExact"));
    }

    /** The plan's recipes by their id, each less its id, as canonical text. */
    @SuppressWarnings("unchecked")
    private static Map<String, String> recipes(final Map<String, Object> plan) {
        final Map<String, String> out = new java.util.LinkedHashMap<>();
        for (final Object o : (List<?>) plan.get("recipes")) {
            final Map<String, Object> r = new java.util.LinkedHashMap<>((Map<String, Object>) o);
            final String id = String.valueOf(r.remove("id"));
            out.put(id, Canon.of(r));
        }
        return out;
    }

    /** The card of another import of the same plan that stands where {@code card} does (ids differ each time). */
    private static Node same(final Graph other, final Node card) {
        for (final Node n : other.getNodes()) if (n.x == card.x && n.y == card.y) return n;
        throw new AssertionError("no card at " + card.x + "," + card.y);
    }

    /** The plan's cards by their id. */
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> cards(final Map<String, Object> plan) {
        final Map<String, Map<String, Object>> out = new java.util.LinkedHashMap<>();
        for (final Object o : (List<?>) plan.get("nodes")) {
            final Map<String, Object> n = (Map<String, Object>) o;
            out.put(String.valueOf(n.get("id")), n);
        }
        return out;
    }

    /** A replacement that keeps none of the card's wires does not take its place. */
    @Test
    void aReplacementThatKeepsNoWireDoesNotFit() {
        final Board b = imported(LINE);
        final Graph g = b.graph();
        Node old = null;
        for (final Node n : g.getNodes())
            if (old == null && CardRecipes.machineOf(g, n.id) == null && !wiresOn(g, n.id).isEmpty()) old = n;
        assertNotNull(old);
        final Node fresh = same(imported(LINE).graph(), old);
        fresh.id = Uuid.randomUUID();
        assertTrue(CardRecipes.fits(g, old, fresh, TestIngredients::nameOf), "the same recipe keeps them all");
        final Node was = old;
        assertFalse(
            CardRecipes.fits(
                g,
                old,
                fresh,
                p -> (was.inputs.stream()
                    .anyMatch(q -> q == p)
                    || was.outputs.stream()
                        .anyMatch(q -> q == p) ? "old:" : "new:")
                    + TestIngredients.nameOf(p)),
            "no wire fits");
        final Node lone = same(imported(LINE).graph(), old);
        for (final Edge e : wiresOn(g, old.id)) g.removeEdge(e.id);
        assertTrue(CardRecipes.fits(g, old, lone, p -> "nothing"), "a card with no wires is replaced in place");
    }
}
