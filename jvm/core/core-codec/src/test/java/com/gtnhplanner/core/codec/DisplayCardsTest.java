package com.gtnhplanner.core.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.gtnhplanner.conformance.Conformance;
import com.gtnhplanner.core.base.JsonTree;
import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.core.model.ResourceKey;
import com.gtnhplanner.core.model.Shown;
import com.gtnhplanner.core.model.SiteResource;
import com.gtnhplanner.data.MachineProfile;
import com.gtnhplanner.data.MachineProfileRegistry;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;

/**
 * Display-only cards (conformance/codec/round-trip/display-cards.json): a plan card the board cannot run becomes a card
 * with the plan's name, ports and counts, solved by the website's machine maths or outside the solve; the site as the
 * board has it drops a deleted card with its recipe and wires and writes a moved one where it stands. A buffer's drain
 * setting goes back out with it.
 */
class DisplayCardsTest {

    @BeforeAll
    static void profile() {
        if (MachineProfileRegistry.get(MachineProfileRegistry.defaultId()) == null) MachineProfileRegistry
            .register(MachineProfile.builder(MachineProfileRegistry.defaultId(), "Default").build());
    }

    private static Map<String, Object> plan() throws IOException {
        return PlanJson.parse(Conformance.text("codec/round-trip/display-cards.json"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> card(final Map<String, Object> plan, final String id) {
        for (final Object o : JsonTree.array(plan.get("nodes")))
            if (id.equals(((Map<String, Object>) o).get("id"))) return (Map<String, Object>) o;
        throw new AssertionError(id);
    }

    private static Node shown(final Map<String, Object> plan, final String id, final Shown.Reason reason) {
        final Map<String, Object> c = card(plan, id);
        return DisplayCards.card(c, DisplayCards.recipeOf(c, plan.get("recipes")), reason, Uuid.randomUUID(), 40, 240);
    }

    @Test
    void anOreVeinIsACardOfThePlansPortsSolvedByTheWebsitesNumbers() throws IOException {
        final Node vein = shown(plan(), "n-vein", Shown.Reason.KIND);
        assertEquals("Ore Vein", vein.machineName);
        assertEquals(0, vein.inputs.size());
        assertEquals(3, vein.outputs.size());
        assertEquals(ResourceKey.item("gregtech:gt.blockores2@100"), vein.outputs.get(1).resource());
        assertEquals("Plutonium 239 Ore", vein.outputs.get(1).getDisplayName());
        // One ore a tick: twenty a second for one machine.
        assertEquals(20.0, vein.outputs.get(0).amount(), 1e-9);
        assertTrue(vein.shown.solved());
        assertEquals("n-vein", vein.shown.planId());
        assertEquals(2.0, vein.machineConfig.machineCount());
        assertTrue(vein.isMachineCountFixed() && vein.isMachineCountExact());
        assertEquals(40, vein.x);
        assertEquals(240, vein.y);
        assertTrue(vein.shown.unmoved(40, 240, null));
    }

    @Test
    void aSwitchedOffCardAndOneWithNoRecipeAreOutsideTheSolve() throws IOException {
        final Map<String, Object> plan = plan();
        final Node off = shown(plan, "n-off", Shown.Reason.OFF);
        assertFalse(off.shown.solved());
        // Its ports carry what a craft does, as the plan says, for the card to show.
        assertEquals(1.0, off.outputs.get(0).amount());
        assertEquals(3.0, off.machineConfig.machineCount());
        assertFalse(off.isMachineCountFixed());
        final Node none = shown(plan, "n-norecipe", Shown.Reason.ABSENT);
        assertFalse(none.shown.solved());
        assertEquals("r-absent", none.machineName);
        assertTrue(none.inputs.isEmpty() && none.outputs.isEmpty());
    }

    @Test
    void anAspectIsAPortOfThePlansName() throws IOException {
        final Node infusion = shown(plan(), "n-infusion", Shown.Reason.KIND);
        assertEquals(ResourceKey.Kind.ASPECT, infusion.inputs.get(1).resource().kind());
        assertTrue(infusion.inputs.get(1).getValue() instanceof SiteResource);
        assertEquals("Vitium", infusion.inputs.get(1).getDisplayName());
    }

    /** A graph as an import leaves it: the plan's kept cards in its site, each shown on the board. */
    private static Graph board(final Map<String, Object> plan) {
        final Graph g = new Graph("x");
        final Set<String> ids = Set.of("n-vein", "n-off", "n-gone");
        final List<Object> nodes = new java.util.ArrayList<>(), recipes = new java.util.ArrayList<>();
        for (final String id : ids) {
            final Map<String, Object> c = card(plan, id);
            nodes.add(c);
            recipes.add(DisplayCards.recipeOf(c, plan.get("recipes")));
            final Node n = shown(plan, id, "n-off".equals(id) ? Shown.Reason.OFF : Shown.Reason.KIND);
            g.addNode(n);
        }
        g.site.put("nodes", nodes);
        g.site.put("recipes", recipes);
        final Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", "e-kept");
        wire.put("source", "n-off");
        wire.put("target", "n-gone");
        g.site.put("edges", new java.util.ArrayList<>(List.of(wire)));
        return g;
    }

    @Test
    void theSiteAsTheBoardHasItDropsADeletedCardAndPlacesAMovedOne() throws IOException {
        final Map<String, Object> plan = plan();
        final Graph g = board(plan);
        assertSame(g.site, DisplayCards.site(g, (n, c) -> {}), "nothing changed: the site as it is");
        Node off = null, vein = null;
        for (final Node n : g.getNodes()) {
            if ("n-off".equals(n.shown.planId())) off = n;
            if ("n-vein".equals(n.shown.planId())) vein = n;
        }
        g.removeNode(off.id);
        vein.x += 20;
        final Map<String, Object> site = DisplayCards.site(g, (n, c) -> c.put("position", Map.of("x", (double) n.x)));
        final List<Object> nodes = JsonTree.array(site.get("nodes"));
        assertEquals(2, nodes.size());
        for (final Object o : nodes) {
            final Map<String, Object> c = JsonTree.object(o);
            if ("n-vein".equals(c.get("id"))) assertEquals(Map.of("x", 60.0), c.get("position"));
            else assertEquals(Canon.of(card(plan, "n-gone")), Canon.of(c), "untouched");
        }
        for (final Object r : JsonTree.array(site.get("recipes")))
            assertFalse("r-off".equals(JsonTree.object(r).get("id")), "the deleted card's recipe is gone");
        assertFalse(site.containsKey("edges"), "and its kept wire");
        // The graph's own site is not changed.
        assertEquals(3, JsonTree.array(g.site.get("nodes")).size());
    }

    @Test
    void aBuffersDrainSettingGoesBackOut() {
        final Map<String, Object> plan = new LinkedHashMap<>();
        final Map<String, Object> drawer = new LinkedHashMap<>();
        drawer.put("id", "s-buf");
        drawer.put("drainMode", "product");
        plan.put("storages", new java.util.ArrayList<>(List.of(drawer)));
        final Map<String, Object> site = SiteCarry.carry(
            plan,
            new SiteCarry.Placed(Map.of(), Map.of("s-buf", "b-1"), Set.of(), Set.of()));
        final Map<String, Object> written = new LinkedHashMap<>();
        final Map<String, Object> buffer = new LinkedHashMap<>();
        buffer.put("id", "b-1");
        written.put("storages", new java.util.ArrayList<>(List.of(buffer)));
        SiteCarry.writeBack(written, site);
        assertEquals("product", buffer.get("drainMode"), "a buffer the board writes no drain for gets the plan's");
        // A drain the board writes keeps its own word.
        final Map<String, Object> trash = new LinkedHashMap<>();
        trash.put("id", "b-1");
        trash.put("drainMode", "trash");
        final Map<String, Object> again = new LinkedHashMap<>();
        again.put("storages", new java.util.ArrayList<>(List.of(trash)));
        SiteCarry.writeBack(again, site);
        assertEquals("trash", trash.get("drainMode"));
    }
}
