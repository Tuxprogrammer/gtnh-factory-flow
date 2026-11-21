package com.gtnhplanner.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;

/**
 * A website plan whose recipe was reordered after its cards' picks were made: one card keys its Water pick
 * ({@code recipeInputOverrides}) to the slot that now takes Oxygen, another has it there and on the Water slot too. The
 * website re-seats picks by what they hold when it carries a card onto its own dataset's recipe; the import does the
 * same, so both cards still find their in-game recipe, and the Water wires and drawer land on the Water port, not on
 * Oxygen's. The plan arrives as a plan code, the
 * way Paste plan takes one copied on the website.
 */
class OverrideSlotImportTest {

    private static final String MIX = """
        {"id":"mix","name":"Mix","kind":"gregtech_machine","category":"gregtech","machineType":"Mixing Vat",
         "durationTicks":100,"eut":30,
         "inputs":[{"kind":"item","id":"gregtech:gt.integrated_circuit@4","amount":1,"consumed":false,
           "displayName":"Programmed Circuit"},
           {"kind":"fluid","id":"oxygen","amount":1000,"displayName":"Oxygen"},
           {"kind":"fluid","id":"water","amount":2000,"displayName":"Water"}],
         "outputs":[{"kind":"fluid","id":"testmix","amount":1000,"displayName":"Test Mix"}]}""";

    private static final String PUMP = """
        {"id":"pump","name":"Pump","kind":"gregtech_machine","category":"gregtech","machineType":"Test Pump",
         "durationTicks":20,"eut":8,
         "inputs":[{"kind":"item","id":"minecraft:cobblestone","amount":1,"displayName":"Cobblestone"}],
         "outputs":[{"kind":"fluid","id":"water","amount":1000,"displayName":"Water"}]}""";

    /** The Water pick keyed to slot 1, which takes Oxygen in the recipe the plan carries. */
    private static final String STRAY_PICK = """
        "recipeInputOverrides":{"1":{"kind":"fluid","id":"water","amount":2000,"displayName":"Water"}}""";

    /** The Water pick on slot 1 and on slot 2, which takes Water. */
    private static final String TWICE_PICKED = """
        "recipeInputOverrides":{"1":{"kind":"fluid","id":"water","amount":2000,"displayName":"Water"},
          "2":{"kind":"fluid","id":"water","amount":2000,"displayName":"Water"}}""";

    private static final String PLAN = """
        {"schemaVersion":1,"id":"p","name":"Stray pick",
         "recipes":[%MIX%,%PUMP%],
         "nodes":[{"id":"n0","recipeId":"pump","machineCount":1,"enabled":true,"position":{"x":0,"y":0}},
           {"id":"n1","recipeId":"mix","machineCount":1,"enabled":true,%PICK%,"position":{"x":600,"y":0}},
           {"id":"n2","recipeId":"mix","machineCount":1,"enabled":true,%TWICE%,"position":{"x":1200,"y":0}}],
         "storages":[{"id":"s-oxygen","kind":"fluid","resourceId":"oxygen","displayName":"Oxygen",
             "position":{"x":300,"y":300}},
           {"id":"s-water","kind":"fluid","resourceId":"water","displayName":"Water","position":{"x":900,"y":300}}],
         "edges":[{"id":"e-pump","source":"n0","target":"n1","sourceHandle":"output:fluid:water",
             "targetHandle":"input:fluid:water","resourceKind":"fluid","resourceId":"water"},
           {"id":"e-ox1","source":"s-oxygen","target":"n1","sourceHandle":"output:fluid:oxygen",
             "targetHandle":"input:fluid:oxygen","resourceKind":"fluid","resourceId":"oxygen"},
           {"id":"e-ox2","source":"s-oxygen","target":"n2","sourceHandle":"output:fluid:oxygen",
             "targetHandle":"input:fluid:oxygen","resourceKind":"fluid","resourceId":"oxygen"},
           {"id":"e-water","source":"s-water","target":"n2","sourceHandle":"output:fluid:water",
             "targetHandle":"input:fluid:water","resourceKind":"fluid","resourceId":"water"}],
         "annotations":[],"pockets":[],"fuelProfiles":[]}
        """.replace("%MIX%", MIX)
        .replace("%PUMP%", PUMP)
        .replace("%PICK%", STRAY_PICK)
        .replace("%TWICE%", TWICE_PICKED);

    @Test
    void aPickKeyedToAnotherSlotMovesToTheSlotThatTakesIt() {
        final FakeGame game = new FakeGame();
        final Graph g = FfImport.importFromText(FfPlanCode.encode(PLAN), game, game)
            .graph();
        final List<Node> cards = new ArrayList<>(g.getNodes());
        cards.sort(Comparator.comparingInt(n -> n.x));
        assertEquals(3, cards.size());
        for (final Node n : cards) assertNull(n.shown, "every card runs its in-game recipe, none is view only");
        final Node pump = cards.get(0), first = cards.get(1), second = cards.get(2);
        final int water1 = game.port(first, false, "water"), oxygen1 = game.port(first, false, "oxygen");
        final int water2 = game.port(second, false, "water"), oxygen2 = game.port(second, false, "oxygen");
        assertTrue(water1 >= 0 && oxygen1 >= 0 && water2 >= 0 && oxygen2 >= 0);

        Edge pumped = null;
        for (final Edge e : g.getEdges()) if (e.sourceNodeId.equals(pump.id)) pumped = e;
        assertNotNull(pumped, "the pump's wire came over");
        assertEquals(first.id, pumped.targetNodeId);
        assertEquals(water1, pumped.targetInputIndex, "the pump's Water goes into the Water port");

        Drawer water = null, oxygen = null;
        for (final Drawer d : g.getDrawers()) {
            if ("Water".equals(d.getLabel())) water = d;
            if ("Oxygen".equals(d.getLabel())) oxygen = d;
        }
        assertNotNull(water);
        assertNotNull(oxygen);
        assertEquals(FfIds.toKey("fluid", "water"), water.getResourceKey(), "the Water drawer holds Water");
        assertTrue(water.isLinked(second.id, water2), "the Water drawer feeds the Water port");
        assertEquals(FfIds.toKey("fluid", "oxygen"), oxygen.getResourceKey());
        assertTrue(oxygen.isLinked(first.id, oxygen1) && oxygen.isLinked(second.id, oxygen2));
    }
}
