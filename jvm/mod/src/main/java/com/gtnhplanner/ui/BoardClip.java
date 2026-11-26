package com.gtnhplanner.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.MachineGroup;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Note;
import com.gtnhplanner.data.flowchart.Waypoints;

/**
 * The board's copy and paste, on the plan alone: what a copy holds and what a paste adds. A shared machine is copied
 * whole (every recipe on its card, in order, with its count and rule) and pastes as one shared machine again, a new
 * one beside the original when it is still there. What the board keeps for a card or drawer from the website
 * ({@code Graph.site}: a byproduct drawer's label, a colour tag) goes with the copy.
 *
 * <p>
 * A card is made again from its recipe by the game at paste ({@link Maker}), so a plan pastes into one whose game no
 * longer has a recipe without it; everything else is plain plan data.
 */
final class BoardClip {

    private BoardClip() {}

    /** A copied card: its recipe and settings, and where it sat from the copy's top-left. */
    record ClipNode(Object recipe, Map<String, Object> settings, String machineName, boolean fixed, boolean exact,
        int dx, int dy, String powerSource, Map<String, String> powerSettings, Map<Integer, String> picks,
        @Nullable Map<String, Object> site) {}

    /**
     * A copied drawer, the copied cards' ports it was linked to as {card index in the copy, port}, and each link's dots
     * from the copy's top-left.
     */
    record ClipDrawer(Drawer.Kind kind, String key, String label, Drawer.Rule rule, double rate, int dx, int dy,
        List<int[]> links, List<List<int[]>> linkWaypoints, @Nullable Map<String, Object> site) {}

    /** A wire between two copied cards, by their index in the copy, and its dots from the copy's top-left. */
    record ClipEdge(int from, int output, int to, int input, List<int[]> waypoints) {}

    /** A copied sticky note, and where it sat from the copy's top-left. */
    record ClipNote(Note note, int dx, int dy) {}

    /** A copied shared machine: its recipes by card index in the copy, top first, and its count and rule. */
    record ClipShared(List<Integer> sections, String header, int color, double count, boolean pinned, boolean exact) {}

    record Clip(List<ClipNode> nodes, List<ClipDrawer> drawers, List<ClipEdge> edges, List<ClipNote> notes,
        List<ClipShared> shared) {}

    /** Makes a recipe card again from what {@code recipeOf} gave for it at copy; null when the game lacks it. */
    interface Maker {

        @Nullable
        Node make(Object recipe, int x, int y);
    }

    /**
     * The copy of these cards, drawers and notes with the wires among them; null when none of the ids is one. A card
     * of a shared machine brings the machine's other recipes ({@code sectionsOf}: the recipes on a node's card, top
     * first). {@code recipeOf} is what the paste makes a recipe card again from.
     */
    @Nullable
    static Clip copy(final Graph graph, final java.util.Collection<Uuid> ids,
        final Function<Uuid, List<Uuid>> sectionsOf, final Function<Node, Object> recipeOf) {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE;
        final List<Node> nodes = new ArrayList<>();
        final List<Drawer> drawers = new ArrayList<>();
        final List<Note> notes = new ArrayList<>();
        final List<Uuid> taken = new ArrayList<>();
        for (final Uuid id : ids) for (final Uuid s : graph.nodes.containsKey(id) ? sectionsOf.apply(id) : List.of(id))
            if (!taken.contains(s)) taken.add(s);
        for (final Uuid id : taken) {
            final Node n = graph.nodes.get(id);
            if (n != null) {
                nodes.add(n);
                x0 = Math.min(x0, n.x);
                y0 = Math.min(y0, n.y);
            }
            final Drawer d = graph.getDrawer(id);
            if (d != null) {
                drawers.add(d);
                x0 = Math.min(x0, d.getX());
                y0 = Math.min(y0, d.getY());
            }
            final Note note = graph.notes.get(id);
            if (note != null) {
                notes.add(note);
                x0 = Math.min(x0, note.getX());
                y0 = Math.min(y0, note.getY());
            }
        }
        if (nodes.isEmpty() && drawers.isEmpty() && notes.isEmpty()) return null;
        final List<ClipNote> clipNotes = new ArrayList<>();
        for (final Note note : notes) clipNotes.add(new ClipNote(note.copy(), note.getX() - x0, note.getY() - y0));
        final Map<Uuid, Integer> index = new HashMap<>();
        final List<ClipNode> clipNodes = new ArrayList<>();
        for (final Node n : nodes) {
            index.put(n.id, clipNodes.size());
            clipNodes.add(
                new ClipNode(
                    n.isPower() ? null : recipeOf.apply(n),
                    new HashMap<>(n.machineConfig.settings),
                    n.machineName,
                    n.isMachineCountFixed(),
                    n.isMachineCountExact(),
                    n.x - x0,
                    n.y - y0,
                    n.powerSource,
                    new LinkedHashMap<>(n.powerSettings),
                    new HashMap<>(n.inputPicks),
                    siteOf(graph, "cards", n.id)));
        }
        final List<ClipEdge> edges = new ArrayList<>();
        for (final Edge e : graph.getEdges()) {
            final Integer from = index.get(e.sourceNodeId), to = index.get(e.targetNodeId);
            if (from != null && to != null) edges.add(
                new ClipEdge(
                    from,
                    e.sourceOutputIndex,
                    to,
                    e.targetInputIndex,
                    Waypoints.shifted(e.waypoints, -x0, -y0)));
        }
        final List<ClipDrawer> clipDrawers = new ArrayList<>();
        for (final Drawer d : drawers) {
            final List<int[]> links = new ArrayList<>();
            final List<List<int[]>> linkWaypoints = new ArrayList<>();
            for (final Drawer.Link l : d.getLinks()) {
                final Integer at = index.get(l.nodeId());
                if (at == null) continue;
                links.add(new int[] { at, l.portIndex() });
                linkWaypoints.add(Waypoints.shifted(l.waypoints(), -x0, -y0));
            }
            clipDrawers.add(
                new ClipDrawer(
                    d.getKind(),
                    d.getResourceKey(),
                    d.getLabel(),
                    d.getRule(),
                    d.getRate(),
                    d.getX() - x0,
                    d.getY() - y0,
                    links,
                    linkWaypoints,
                    siteOf(graph, "drawers", d.getId())));
        }
        final List<ClipShared> shared = new ArrayList<>();
        for (final com.gtnhplanner.data.flowchart.Group group : graph.getGroups()) {
            if (!(group instanceof final MachineGroup g) || !g.isShared()) continue;
            final List<Integer> sections = new ArrayList<>();
            for (final Uuid s : g.getSections()) {
                final Integer at = index.get(s);
                if (at != null) sections.add(at);
            }
            if (sections.size() >= 2) shared.add(
                new ClipShared(sections, g.getHeader(), g.getColor(), g.machineCount(), g.isPinned(), g.isExact()));
        }
        return new Clip(clipNodes, clipDrawers, edges, clipNotes, shared);
    }

    /**
     * Adds one copy of the clip to the plan with its top-left at a board point (each position through {@code snap})
     * and returns what it added: its cards, drawers and notes. The cards are made at once, before any of it goes in, so
     * a step that adds it later ({@link #pasting}) adds what this made.
     */
    static List<Uuid> paste(final Graph graph, final Clip clip, final int worldX, final int worldY, final Maker maker,
        final IntUnaryOperator snap) {
        return pasting(graph, clip, worldX, worldY, maker, snap).get();
    }

    /**
     * One copy of the clip at a board point: its cards made now, the returned step adds everything to the plan and
     * gives what it added. A card whose recipe the game no longer has is left out, with its wires; a shared machine
     * left with one recipe is an ordinary card.
     */
    static java.util.function.Supplier<List<Uuid>> pasting(final Graph graph, final Clip clip, final int worldX,
        final int worldY, final Maker maker, final IntUnaryOperator snap) {
        final List<Node> made = new ArrayList<>();
        for (final ClipNode c : clip.nodes()) {
            final int x = snap.applyAsInt(worldX + c.dx()), y = snap.applyAsInt(worldY + c.dy());
            if (c.powerSource() != null) {
                final Node n = Node.power(c.powerSource(), c.powerSettings(), x, y);
                n.setMachineCountFixed(c.fixed());
                n.setMachineCountExact(c.exact());
                made.add(n);
                continue;
            }
            final Node n = maker.make(c.recipe(), x, y);
            if (n == null) {
                made.add(null);
                continue;
            }
            n.machineConfig.settings.clear();
            n.machineConfig.settings.putAll(c.settings());
            n.machineName = c.machineName();
            n.setMachineCountFixed(c.fixed());
            n.setMachineCountExact(c.exact());
            n.inputPicks.putAll(c.picks());
            n.applyPicks();
            made.add(n);
        }
        final int sx = snap.applyAsInt(worldX), sy = snap.applyAsInt(worldY);
        return () -> {
            final List<Uuid> added = new ArrayList<>();
            for (int i = 0; i < made.size(); i++) {
                final Node n = made.get(i);
                if (n == null) continue;
                graph.addNode(n);
                putSite(
                    graph,
                    "cards",
                    n.id,
                    clip.nodes()
                        .get(i)
                        .site());
                added.add(n.id);
            }
            for (final ClipShared s : clip.shared()) {
                final List<Node> sections = new ArrayList<>();
                for (final int at : s.sections()) if (made.get(at) != null) sections.add(made.get(at));
                if (sections.size() < 2) continue;
                final MachineGroup g = new MachineGroup();
                g.setHeader(s.header());
                g.setColor(s.color());
                for (final Node n : sections) {
                    g.addSection(n.id);
                    // Every recipe of a shared machine sits where its card does.
                    n.x = sections.get(0).x;
                    n.y = sections.get(0).y;
                }
                g.setMachineCount(s.count());
                g.setPinned(s.pinned());
                g.setExact(s.exact());
                graph.groups.put(g.getId(), g);
            }
            for (final ClipEdge e : clip.edges()) {
                final Node from = made.get(e.from()), to = made.get(e.to());
                if (from == null || to == null) continue;
                final Edge edge = new Edge(Uuid.randomUUID(), from.id, to.id, e.output(), e.input());
                // Its dots ride along with the copy, as on the website.
                edge.waypoints = Waypoints.shifted(e.waypoints(), sx, sy);
                graph.addEdge(edge);
            }
            for (final ClipDrawer c : clip.drawers()) {
                final Drawer d = new Drawer(c.kind(), c.key());
                d.setLabel(c.label());
                d.setX(snap.applyAsInt(worldX + c.dx()));
                d.setY(snap.applyAsInt(worldY + c.dy()));
                d.setTarget(c.rule(), c.rate());
                graph.addDrawer(d);
                putSite(graph, "drawers", d.getId(), c.site());
                for (int k = 0; k < c.links()
                    .size(); k++) {
                    final int[] l = c.links()
                        .get(k);
                    final Node n = made.get(l[0]);
                    if (n != null) graph.linkDrawer(
                        d.getId(),
                        new Drawer.Link(n.id, l[1]).withWaypoints(
                            Waypoints.shifted(
                                c.linkWaypoints()
                                    .get(k),
                                sx,
                                sy)));
                }
                added.add(d.getId());
            }
            for (final ClipNote c : clip.notes()) {
                final Note note = c.note()
                    .copy();
                note.setX(snap.applyAsInt(worldX + c.dx()));
                note.setY(snap.applyAsInt(worldY + c.dy()));
                graph.notes.put(note.getId(), note);
                added.add(note.getId());
            }
            return added;
        };
    }

    /** What the board keeps from the website for one card or drawer ({@code Graph.site}), copied; null for nothing. */
    @Nullable
    private static Map<String, Object> siteOf(final Graph graph, final String kind, final Uuid id) {
        if (!(graph.site.get(kind) instanceof final Map<?, ?> all)
            || !(all.get(id.toString()) instanceof final Map<?, ?> own)) return null;
        final Map<String, Object> out = new LinkedHashMap<>();
        for (final Map.Entry<?, ?> e : own.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void putSite(final Graph graph, final String kind, final Uuid id,
        @Nullable final Map<String, Object> own) {
        if (own == null) return;
        Map<String, Object> all = graph.site.get(kind) instanceof Map<?, ?>m ? (Map<String, Object>) m : null;
        if (all == null) {
            all = new LinkedHashMap<>();
            graph.site.put(kind, all);
        }
        all.put(id.toString(), new LinkedHashMap<>(own));
    }
}
