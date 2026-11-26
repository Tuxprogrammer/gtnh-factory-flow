package com.gtnhplanner.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.MachineGroup;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.importer.FakeGame;
import com.gtnhplanner.importer.FfImport;
import com.gtnhplanner.ui.group.GroupEdits;

/**
 * Copy, cut and paste of a shared machine ({@link BoardClip}): the plan is written out as its cards (each card's
 * recipes in order, its count and rule), wires, drawers and what the board keeps from the website per card and
 * drawer, by content and never by id, before a cut and after the paste, and the two must match.
 */
class BoardClipTest {

    private static final FakeGame GAME = new FakeGame();

    /** 7 cards: a reactor running 4 recipes, a centrifuge running 2, and 5 cards of their own. */
    private static Graph plan() {
        return FfImport.importFromText(FakeGame.fixture("alumina-line-shared-reactor.json"), GAME, GAME)
            .graph();
    }

    private static List<MachineGroup> shared(final Graph g) {
        final List<MachineGroup> out = new ArrayList<>();
        for (final com.gtnhplanner.data.flowchart.Group x : g.getGroups())
            if (x instanceof final MachineGroup m && m.isShared() && g.nodes.containsKey(
                m.getSections()
                    .get(0))) out.add(m);
        return out;
    }

    /** The recipes on a node's card, top first, as the board's {@code sectionsOf} gives them. */
    private static List<Uuid> sectionsOf(final Graph g, final Uuid id) {
        for (final MachineGroup m : shared(g)) if (m.getSections()
            .contains(id)) return List.copyOf(m.getSections());
        return List.of(id);
    }

    private static String recipe(final Node n) {
        final List<String> ins = new ArrayList<>(), outs = new ArrayList<>();
        for (final Port<?> p : n.inputs) ins.add(
            GAME.describe(p)
                .key());
        for (final Port<?> p : n.outputs) outs.add(
            GAME.describe(p)
                .key());
        return n.machineName + " " + ins + " -> " + outs;
    }

    /** A node as its card and place on it: "card's first recipe / section index". */
    private static String end(final Graph g, final Uuid id) {
        final List<Uuid> sections = sectionsOf(g, id);
        return recipe(g.nodes.get(sections.get(0))) + " #" + sections.indexOf(id);
    }

    private static Object site(final Graph g, final String kind, final Uuid id) {
        return g.site.get(kind) instanceof final Map<?, ?> all ? all.get(id.toString()) : null;
    }

    /** The plan by content: cards, wires and drawers, each sorted, ids left out. */
    private static Map<String, List<String>> encode(final Graph g) {
        final List<String> cards = new ArrayList<>(), wires = new ArrayList<>(), drawers = new ArrayList<>();
        for (final Node n : g.getNodes()) {
            final List<Uuid> sections = sectionsOf(g, n.id);
            if (!sections.get(0)
                .equals(n.id)) continue;
            final MachineGroup m = shared(g).stream()
                .filter(
                    x -> x.getSections()
                        .contains(n.id))
                .findFirst()
                .orElse(null);
            cards.add(
                sections.stream()
                    .map(s -> recipe(g.nodes.get(s)) + " " + new TreeMap<>(g.nodes.get(s).machineConfig.settings))
                    .collect(Collectors.joining(" | "))
                    + (m == null ? " own " + n.machineConfig.getMachineCount() + " fixed " + n.isMachineCountFixed()
                        : " shared " + m.machineCount() + " pinned " + m.isPinned() + " exact " + m.isExact())
                    + " site "
                    + site(g, "cards", n.id));
        }
        for (final Edge e : g.getEdges()) wires.add(
            end(g, e.sourceNodeId) + " out"
                + e.sourceOutputIndex
                + " => "
                + end(g, e.targetNodeId)
                + " in"
                + e.targetInputIndex);
        for (final Drawer d : g.getDrawers()) {
            final List<String> links = new ArrayList<>();
            for (final Drawer.Link l : d.getLinks()) links.add(end(g, l.nodeId()) + " port" + l.portIndex());
            links.sort(null);
            drawers.add(
                d.getKind() + " "
                    + d.getResourceKey()
                    + " "
                    + d.getRule()
                    + " "
                    + d.getRate()
                    + " "
                    + links
                    + " site "
                    + site(g, "drawers", d.getId()));
        }
        cards.sort(null);
        wires.sort(null);
        drawers.sort(null);
        final Map<String, List<String>> out = new TreeMap<>();
        out.put("cards", cards);
        out.put("wires", wires);
        out.put("drawers", drawers);
        return out;
    }

    private static List<Uuid> everything(final Graph g) {
        final List<Uuid> ids = new ArrayList<>(g.nodes.keySet());
        ids.addAll(g.drawers.keySet());
        ids.addAll(g.notes.keySet());
        return ids;
    }

    private static BoardClip.Clip copy(final Graph g, final Collection<Uuid> ids) {
        // The test's game makes a card again from the card it was copied from.
        final BoardClip.Clip clip = BoardClip.copy(g, ids, id -> sectionsOf(g, id), n -> n);
        assertNotNull(clip);
        return clip;
    }

    private static List<Uuid> paste(final Graph g, final BoardClip.Clip clip, final int x, final int y) {
        return BoardClip.paste(g, clip, x, y, (recipe, px, py) -> {
            final Node from = (Node) recipe;
            final Node n = new Node(Uuid.randomUUID(), px, py);
            n.machineName = from.machineName;
            n.properties.putAll(from.properties);
            n.inputs.addAll(from.inputs);
            n.outputs.addAll(from.outputs);
            return n;
        }, v -> Math.round(v / 10f) * 10);
    }

    @Test
    void aCutAndPastedSharedMachineIsTheSameSharedMachine() {
        final Graph g = plan();
        final Map<String, List<String>> before = encode(g);
        assertEquals(
            7,
            before.get("cards")
                .size());
        assertEquals(2, shared(g).size());
        final BoardClip.Clip clip = copy(g, everything(g));
        GroupEdits.removeAll(g, everything(g));
        assertEquals(0, g.nodes.size());

        final List<Uuid> added = paste(g, clip, 400, 300);

        assertEquals(before, encode(g), "the same cards, recipes, counts, wires, drawers and drawer labels");
        assertEquals(11 + 28, added.size(), "every recipe and drawer is selected");
        for (final MachineGroup m : shared(g)) for (final Uuid s : m.getSections()) {
            assertEquals(
                g.nodes.get(
                    m.getSections()
                        .get(0)).x,
                g.nodes.get(s).x,
                "every recipe sits on its card");
            assertEquals(
                g.nodes.get(
                    m.getSections()
                        .get(0)).y,
                g.nodes.get(s).y);
        }
    }

    @Test
    void aCopiedSharedMachineIsASecondMachineWithTheSameRecipes() {
        final Graph g = plan();
        final MachineGroup reactor = shared(g).stream()
            .filter(
                m -> m.getSections()
                    .size() == 4)
            .findFirst()
            .orElseThrow();
        reactor.setMachineCount(3);
        reactor.setPinned(true);
        reactor.setExact(true);
        final Map<String, List<String>> before = encode(g);
        final List<String> recipes = reactor.getSections()
            .stream()
            .map(s -> recipe(g.nodes.get(s)))
            .toList();

        // Selecting the card is selecting its first recipe: the copy takes all four.
        paste(
            g,
            copy(
                g,
                List.of(
                    reactor.getSections()
                        .get(0))),
            4000,
            300);

        assertEquals(3, shared(g).size());
        final MachineGroup again = shared(g).stream()
            .filter(
                m -> m != reactor && m.getSections()
                    .size() == 4)
            .findFirst()
            .orElseThrow();
        assertNotEquals(reactor.getId(), again.getId());
        assertEquals(
            recipes,
            again.getSections()
                .stream()
                .map(s -> recipe(g.nodes.get(s)))
                .toList(),
            "its recipes in their order");
        assertEquals(3.0, again.machineCount());
        assertEquals(true, again.isPinned());
        assertEquals(true, again.isExact());
        assertEquals(
            before.get("cards")
                .size() + 1,
            encode(g).get("cards")
                .size());
        // Its wires among its own recipes come with it; the original keeps its own.
        final long inner = g.getEdges()
            .stream()
            .filter(
                e -> reactor.getSections()
                    .contains(e.sourceNodeId)
                    && reactor.getSections()
                        .contains(e.targetNodeId))
            .count();
        assertEquals(
            inner,
            g.getEdges()
                .stream()
                .filter(
                    e -> again.getSections()
                        .contains(e.sourceNodeId)
                        && again.getSections()
                            .contains(e.targetNodeId))
                .count());
    }

    @Test
    void aPlanCopiedIntoAnotherPlanIsTheSamePlan() {
        final Graph g = plan();
        final BoardClip.Clip clip = copy(g, everything(g));
        final Graph other = new Graph("other");

        paste(other, clip, 0, 0);

        assertEquals(encode(g), encode(other));
    }
}
