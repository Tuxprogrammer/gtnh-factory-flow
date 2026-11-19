package com.gtnhplanner.bench;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.gtnhplanner.core.layout.BoxRouter;
import com.gtnhplanner.core.layout.GridRoutedEdge;
import com.gtnhplanner.core.layout.IncrementalStats;
import com.gtnhplanner.layout.RouteMetrics;

/**
 * Routes and arranges a {@link BenchBoard} the way the board does (WireLayer, BoardCanvas.arrange), and measures it.
 */
public final class BenchRun {

    private BenchRun() {}

    /** A routed board: the wires' corners by link id, which fell back, how long it took, and what it measures. */
    public record Routed(BenchBoard board, Map<UUID, List<int[]>> routes, List<UUID> fellBack, double millis,
        RouteMetrics.Result metrics) {}

    public static Routed route(final BenchBoard board) {
        return routeNew(board);
    }

    /** The board's boxes as the router takes them: drawers met square. */
    static List<BoxRouter.Box> boxes(final BenchBoard board) {
        final List<BoxRouter.Box> boxes = new ArrayList<>();
        for (final BenchBoard.Item i : board.items()) boxes.add(
            new BoxRouter.Box(
                i.id()
                    .toString(),
                i.x(),
                i.y(),
                i.w(),
                i.h(),
                i.drawer()));
        return boxes;
    }

    /** The board's wires box to box, a loop from a card back to itself right side to left side, all 4 px. */
    static List<BoxRouter.Wire> wires(final BenchBoard board) {
        final Map<UUID, Integer> index = new HashMap<>();
        for (int i = 0; i < board.items()
            .size(); i++)
            index.put(
                board.items()
                    .get(i)
                    .id(),
                i);
        final List<BoxRouter.Wire> wires = new ArrayList<>();
        for (final BenchBoard.Link l : board.links()) {
            final int a = index.get(l.from()), b = index.get(l.to());
            final String key = l.id()
                .toString();
            wires.add(
                a == b ? new BoxRouter.Wire(key, a, b, BoxRouter.RIGHT, BoxRouter.LEFT, 4, List.of())
                    : new BoxRouter.Wire(key, a, b, BoxRouter.ANY, BoxRouter.ANY, 4, List.of()));
        }
        return wires;
    }

    /** The board's router (core.layout, the website's), warmed once, then timed on a fresh router. */
    public static Routed routeNew(final BenchBoard board) {
        final List<BoxRouter.Box> boxes = boxes(board);
        final List<BoxRouter.Wire> wires = wires(board);
        new BoxRouter().route(boxes, wires, true, null);
        final IncrementalStats stats = new IncrementalStats();
        final long t0 = System.nanoTime();
        final Map<String, GridRoutedEdge> solved = new BoxRouter().route(boxes, wires, true, stats);
        final double ms = (System.nanoTime() - t0) / 1e6;
        final Map<UUID, List<int[]>> routes = new HashMap<>();
        final List<UUID> fellBack = new ArrayList<>();
        for (final GridRoutedEdge r : solved.values()) {
            final UUID key = UUID.fromString(r.edgeId());
            routes.put(key, BoxRouter.pixels(r));
            if (r.vertices() == null) fellBack.add(key);
        }
        System.out.printf(
            java.util.Locale.ROOT,
            "[router] %-40s %7.1f ms  wires %4d  searches %,7.0f  rerouted %4d  fell back %3d  pops %,11.0f  priced %,11.0f%n",
            board.name()
                .length() > 40 ? board.name()
                    .substring(0, 40) : board.name(),
            ms,
            wires.size(),
            stats.searches,
            stats.rerouted,
            fellBack.size(),
            stats.pops,
            stats.priced);
        return new Routed(board, routes, fellBack, ms, measure(board, routes));
    }

    public static RouteMetrics.Result measure(final BenchBoard board, final Map<UUID, List<int[]>> routes) {
        final List<RouteMetrics.Box> boxes = new ArrayList<>();
        final Map<UUID, Integer> index = new HashMap<>();
        for (final BenchBoard.Item i : board.items()) {
            index.put(i.id(), boxes.size());
            boxes.add(new RouteMetrics.Box(i.x(), i.y(), i.w(), i.h()));
        }
        final List<RouteMetrics.Wire> wires = new ArrayList<>();
        for (final BenchBoard.Link l : board.links()) {
            final List<int[]> p = routes.get(l.id());
            if (p == null) continue;
            wires.add(new RouteMetrics.Wire(index.get(l.from()), index.get(l.to()), p));
        }
        return RouteMetrics.measure(boxes, wires);
    }

    private static final int[] STAGE = { -1 };
    private static final String[] STAGE_NAME = { "" };

    /**
     * The board as Arrange leaves it (core.layout.arrange, judged by the board's router), how long that took, and which
     * candidate won. -Pstages prints when each stage starts.
     */
    public static BenchBoard arrangeNew(final BenchBoard board, final double[] millis, final String[] chosen) {
        final List<com.gtnhplanner.core.layout.arrange.ArrangeCard> cards = new ArrayList<>();
        final Map<UUID, BenchBoard.Item> byId = board.byId();
        for (final BenchBoard.Item i : board.items()) cards.add(
            new com.gtnhplanner.core.layout.arrange.ArrangeCard(
                i.id()
                    .toString(),
                i.x(),
                i.y(),
                i.w(),
                i.h(),
                i.drawer()));
        final List<com.gtnhplanner.core.layout.arrange.ArrangeWire> wires = new ArrayList<>();
        for (final BenchBoard.Link l : board.links()) {
            final BenchBoard.Item a = byId.get(l.from()), b = byId.get(l.to());
            wires.add(
                new com.gtnhplanner.core.layout.arrange.ArrangeWire(
                    l.id()
                        .toString(),
                    l.from()
                        .toString(),
                    l.to()
                        .toString(),
                    a.drawer() ? null
                        : (double) (BenchBoard.RAILS_Y + l.fromPort() * BenchBoard.ROW + BenchBoard.ROW / 2),
                    b.drawer() ? null
                        : (double) (BenchBoard.RAILS_Y + l.toPort() * BenchBoard.ROW + BenchBoard.ROW / 2),
                    null,
                    null));
        }
        final boolean stages = Boolean.getBoolean("gtnhplanner.stages");
        final long t0 = System.nanoTime();
        final com.gtnhplanner.core.layout.arrange.ArrangeResult result = com.gtnhplanner.core.layout.arrange.Arrange
            .arrangeBoard(
                new com.gtnhplanner.core.layout.arrange.Arrange.Input(
                    cards,
                    wires,
                    null,
                    null,
                    System.getProperty("gtnhplanner.spacing", "compact"),
                    new com.gtnhplanner.core.layout.arrange.BoxJudge(
                        cards,
                        wires,
                        com.gtnhplanner.core.layout.RouterTuning.DEFAULT),
                    null,
                    null,
                    p -> {
                        if (stages && (p.step() != STAGE[0] || !p.stage()
                            .equals(STAGE_NAME[0]))) {
                            STAGE[0] = p.step();
                            STAGE_NAME[0] = p.stage();
                            System.out.printf(
                                java.util.Locale.ROOT,
                                "[stage] %-40s step %d (%s) at %8.1f ms%n",
                                board.name(),
                                p.step(),
                                p.stage(),
                                (System.nanoTime() - t0) / 1e6);
                        }
                    },
                    null,
                    null));
        STAGE[0] = -1;
        millis[0] = (System.nanoTime() - t0) / 1e6;
        // The website's arrange does not name the candidate it kept.
        chosen[0] = "-";
        final Map<UUID, int[]> at = new HashMap<>();
        for (final com.gtnhplanner.core.layout.arrange.ArrangeResult.Move m : result.moves()) at.put(
            UUID.fromString(m.id()),
            new int[] { (int) Math.round(
                m.position()
                    .x()),
                (int) Math.round(
                    m.position()
                        .y()) });
        return board.placed(at);
    }
}
