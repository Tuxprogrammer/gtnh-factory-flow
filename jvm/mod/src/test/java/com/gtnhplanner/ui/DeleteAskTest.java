package com.gtnhplanner.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.flowchart.BoardGroup;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Groups;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Note;
import com.gtnhplanner.harness.GtnhFlowLoader;

/** A delete of more than one card asks first; the count is cards as the board draws them. */
class DeleteAskTest {

    private static final Function<Uuid, List<Uuid>> ALONE = List::of;
    private static final Function<Uuid, Uuid> OWN_CARD = id -> id;

    private static Graph chart() {
        return GtnhFlowLoader.load("jet_fuel")
            .graph();
    }

    private static List<Uuid> nodes(final Graph g) {
        final List<Uuid> out = new ArrayList<>();
        for (final Node n : g.getNodes()) out.add(n.id);
        return out;
    }

    private static int cards(final Graph g, final List<Uuid> selection) {
        return DeleteAsk.cards(g, DeleteAsk.takes(g, selection, ALONE), OWN_CARD);
    }

    @Test
    void oneCardGoesAtOnceTwoAsk() {
        final Graph g = chart();
        final List<Uuid> ids = nodes(g);
        assertTrue(ids.size() >= 3, "the fixture has several cards");
        assertEquals(1, cards(g, List.of(ids.get(0))));
        assertFalse(DeleteAsk.asks(1));
        assertEquals(2, cards(g, List.of(ids.get(0), ids.get(1))));
        assertTrue(DeleteAsk.asks(2));
        assertEquals(ids.size(), cards(g, ids));
        assertEquals("Delete 2 cards?", DeleteAsk.question(2));
    }

    @Test
    void notesAndDrawersNeverAskButCountNothing() {
        final Graph g = chart();
        final Note a = new Note(), b = new Note();
        g.notes.put(a.getId(), a);
        g.notes.put(b.getId(), b);
        final Drawer d = new Drawer(Drawer.Kind.PRODUCT, "fluid:a");
        g.addDrawer(d);
        assertEquals(0, cards(g, List.of(a.getId(), b.getId(), d.getId())));
        // Mixed with one card it still goes at once; with two it asks.
        final List<Uuid> ids = nodes(g);
        assertEquals(1, cards(g, List.of(a.getId(), d.getId(), ids.get(0))));
        assertEquals(2, cards(g, List.of(a.getId(), d.getId(), ids.get(0), ids.get(1))));
    }

    @Test
    void aSharedMachineIsOneCard() {
        final Graph g = chart();
        final List<Uuid> ids = nodes(g);
        final Uuid host = ids.get(0), second = ids.get(1);
        // Two recipes time-sharing one machine: one card, drawn at its first section.
        final Function<Uuid, List<Uuid>> sections = id -> id.equals(host) || id.equals(second) ? List.of(host, second)
            : List.of(id);
        final Function<Uuid, Uuid> hostOf = id -> id.equals(second) ? host : id;
        final List<Uuid> takes = DeleteAsk.takes(g, List.of(second), sections);
        assertEquals(List.of(host, second), takes, "the machine goes whole");
        assertEquals(1, DeleteAsk.cards(g, takes, hostOf), "and is one card, so it goes at once");
        assertEquals(2, DeleteAsk.cards(g, DeleteAsk.takes(g, List.of(host, ids.get(2)), sections), hostOf));
    }

    @Test
    void aGroupCountsTheCardsInIt() {
        final Graph g = chart();
        final List<Uuid> ids = nodes(g);
        final BoardGroup group = new BoardGroup();
        g.boardGroups.put(group.getId(), group);
        assertEquals(0, cards(g, List.of(group.getId())), "an empty group goes at once");
        Groups.setGroupOf(g, ids.get(0), group.getId());
        assertEquals(1, cards(g, List.of(group.getId())));
        Groups.setGroupOf(g, ids.get(1), group.getId());
        assertEquals(2, cards(g, List.of(group.getId())), "a group of two cards asks");
    }
}
