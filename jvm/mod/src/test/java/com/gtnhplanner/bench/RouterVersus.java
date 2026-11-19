package com.gtnhplanner.bench;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhplanner.core.layout.BoxRouter;
import com.gtnhplanner.core.layout.GridPoint;
import com.gtnhplanner.core.layout.GridRoutedEdge;
import com.gtnhplanner.core.layout.IncrementalStats;
import com.gtnhplanner.core.layout.RouterTuning;
import com.gtnhplanner.core.layout.WireMetrics;
import com.gtnhplanner.layout.RouteMetrics;
import com.gtnhplanner.layout.WireRouter;
import com.gtnhplanner.layout.arrange.Arrange;
import com.gtnhplanner.layout.arrange.ArrangeCard;
import com.gtnhplanner.layout.arrange.ArrangeWire;

/**
 * The two routers on the same boards: the mod's own (layout/WireRouter, 10 px cells) and the website's written in
 * Java (core.layout.GridEdgeRouter, 20 px grid), each board routed afresh by both and a card dragged across it, both
 * measured by the website's metric (WireMetrics: crossings, length, bends, points at the website's prices) and by the
 * mod's (RouteMetrics). Boards: every placed plan of the layout benchmark's corpus, and the gtnh-flow charts laid out
 * by Arrange's plain column pass (no router involved). Writes build/bench/versus/summary.md and each board's router
 * input as JSON (build/bench/versus/boards/) for the TypeScript router to replay. {@code -Pbench=versus --tests
 * '*RouterVersus*'}.
 */
@Tag("bench")
class RouterVersus {

    private record Side(String router, WireMetrics.Measure web, double webPoints, RouteMetrics.Result mod,
        double routeMs, double dragAvgMs, double dragMaxMs, double dropMs, int fellBack) {}

    @Test
    void versus() throws IOException {
        final File out = new File("build/bench/versus");
        final File inputs = new File(out, "boards");
        inputs.mkdirs();
        final List<BenchBoard> boards = new ArrayList<>();
        for (final BenchBoard b : LayoutBenchmark.corpus()) {
            final boolean placed = b.items()
                .stream()
                .anyMatch(i -> i.x() != 0 || i.y() != 0);
            boards.add(placed ? b : columnPass(b));
        }
        final StringBuilder table = new StringBuilder(
            "| board | boxes | wires | router | crossings | length | bends45 | bends90 | sharp | points (web) | points (mod) | overlap | boxHits | route ms | drag beat avg ms | drag beat max ms | drop ms | fell back |\n"
                + "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        final double[][] totals = new double[2][14];
        for (final BenchBoard board : boards) {
            if (board.links()
                .isEmpty()) continue;
            final Side old = old(board);
            final Side grid = grid(board, inputs);
            int k = 0;
            for (final Side s : List.of(old, grid)) {
                table.append(
                    String.format(
                        Locale.ROOT,
                        "| %s | %d | %d | %s | %d | %.0f | %d | %d | %d | %.0f | %.0f | %.0f | %.0f | %.1f | %.2f | %.2f | %.1f | %d |%n",
                        board.name(),
                        board.items()
                            .size(),
                        board.links()
                            .size(),
                        s.router,
                        s.web.crossings(),
                        s.web.length(),
                        s.web.bends45(),
                        s.web.bends90(),
                        s.web.bendsSharp(),
                        s.webPoints,
                        s.mod.points(),
                        s.mod.overlap(),
                        s.mod.boxHits(),
                        s.routeMs,
                        s.dragAvgMs,
                        s.dragMaxMs,
                        s.dropMs,
                        s.fellBack));
                final double[] t = totals[k++];
                final double[] v = { s.web.crossings(), s.web.length(), s.web.bends45(), s.web.bends90(),
                    s.web.bendsSharp(), s.webPoints, s.mod.points(), s.mod.overlap(), s.mod.boxHits(), s.routeMs,
                    s.dragAvgMs, s.dragMaxMs, s.dropMs, s.fellBack };
                for (int i = 0; i < v.length; i++) t[i] += v[i];
            }
        }
        final String[] names = { "mod WireRouter", "web GridEdgeRouter" };
        for (int k = 0; k < 2; k++) {
            final double[] t = totals[k];
            table.append(
                String.format(
                    Locale.ROOT,
                    "| **total** | | | %s | %.0f | %.0f | %.0f | %.0f | %.0f | %.0f | %.0f | %.0f | %.0f | %.1f | %.2f | %.2f | %.1f | %.0f |%n",
                    names[k],
                    t[0],
                    t[1],
                    t[2],
                    t[3],
                    t[4],
                    t[5],
                    t[6],
                    t[7],
                    t[8],
                    t[9],
                    t[10],
                    t[11],
                    t[12],
                    t[13]));
        }
        Files.writeString(new File(out, "summary.md").toPath(), table.toString(), StandardCharsets.UTF_8);
        System.out.println(table);
    }

    /** A gtnh-flow chart (no positions) laid out by Arrange's plain column pass, which asks no router. */
    private static BenchBoard columnPass(final BenchBoard board) {
        final List<ArrangeCard> cards = new ArrayList<>();
        for (final BenchBoard.Item i : board.items()) cards.add(
            new ArrangeCard(
                i.id()
                    .toString(),
                i.x(),
                i.y(),
                i.w(),
                i.h(),
                i.drawer()));
        final List<ArrangeWire> wires = new ArrayList<>();
        for (final BenchBoard.Link l : board.links()) wires.add(
            new ArrangeWire(
                l.id()
                    .toString(),
                l.from()
                    .toString(),
                l.to()
                    .toString(),
                null,
                null,
                null,
                null));
        final Arrange.Result r = Arrange
            .arrange(new Arrange.Input(cards, wires, null, "compact", null, null, null, null, null));
        final Map<UUID, int[]> at = new HashMap<>();
        for (final Map.Entry<String, com.gtnhplanner.layout.arrange.Point> e : r.positions()
            .entrySet())
            at.put(
                UUID.fromString(e.getKey()),
                new int[] { (int) Math.round(
                    e.getValue()
                        .x()),
                    (int) Math.round(
                        e.getValue()
                            .y()) });
        return board.placed(at);
    }

    private static BenchBoard.Item busiest(final BenchBoard board) {
        final Map<UUID, Integer> count = new HashMap<>();
        for (final BenchBoard.Link l : board.links()) {
            count.merge(l.from(), 1, Integer::sum);
            count.merge(l.to(), 1, Integer::sum);
        }
        BenchBoard.Item best = board.items()
            .get(0);
        for (final BenchBoard.Item i : board.items())
            if (count.getOrDefault(i.id(), 0) > count.getOrDefault(best.id(), 0)) best = i;
        return best;
    }

    private static final int STEPS = 20, DX = 20, DY = 0;

    // region The mod's router

    private static List<WireRouter.Box> oldBoxes(final BenchBoard board, final UUID moving, final int dx,
        final int dy) {
        final List<WireRouter.Box> out = new ArrayList<>();
        for (final BenchBoard.Item i : board.items()) {
            final boolean m = i.id()
                .equals(moving);
            out.add(new WireRouter.Box(i.x() + (m ? dx : 0), i.y() + (m ? dy : 0), i.w(), i.h()));
        }
        return out;
    }

    private static Map<UUID, Integer> index(final BenchBoard board) {
        final Map<UUID, Integer> index = new HashMap<>();
        for (int i = 0; i < board.items()
            .size(); i++)
            index.put(
                board.items()
                    .get(i)
                    .id(),
                i);
        return index;
    }

    private static Side old(final BenchBoard board) {
        final Map<UUID, Integer> index = index(board);
        final List<WireRouter.Wire> wires = new ArrayList<>();
        for (final BenchBoard.Link l : board.links()) {
            final int a = index.get(l.from()), b = index.get(l.to());
            wires.add(
                a == b ? new WireRouter.Wire(l.id(), a, b, WireRouter.RIGHT, WireRouter.LEFT, 4)
                    : new WireRouter.Wire(l.id(), a, b, 4));
        }
        final UUID none = new UUID(0, 0);
        new WireRouter().route(oldBoxes(board, none, 0, 0), wires, null, true);
        final WireRouter router = new WireRouter();
        final List<UUID> fellBack = new ArrayList<>();
        long t0 = System.nanoTime();
        final Map<UUID, List<int[]>> routes = router.route(oldBoxes(board, none, 0, 0), wires, fellBack, true);
        final double routeMs = (System.nanoTime() - t0) / 1e6;
        final List<WireMetrics.Measured> measured = new ArrayList<>();
        for (final BenchBoard.Link l : board.links()) {
            final List<int[]> p = routes.get(l.id());
            if (p == null) continue;
            final List<GridPoint> pts = new ArrayList<>();
            for (final int[] xy : p) pts.add(new GridPoint(xy[0], xy[1]));
            measured.add(
                new WireMetrics.Measured(
                    l.id()
                        .toString(),
                    pts,
                    4.0));
        }
        final WireMetrics.Measure web = WireMetrics.measure(measured);
        final RouteMetrics.Result mod = BenchRun.measure(board, routes);
        final UUID moving = busiest(board).id();
        double total = 0, worst = 0;
        for (int k = 1; k <= STEPS; k++) {
            t0 = System.nanoTime();
            router.reroute(oldBoxes(board, moving, k * DX, k * DY), wires, null, false);
            final double ms = (System.nanoTime() - t0) / 1e6;
            total += ms;
            worst = Math.max(worst, ms);
        }
        t0 = System.nanoTime();
        router.reroute(oldBoxes(board, moving, STEPS * DX, STEPS * DY), wires, null, true);
        final double dropMs = (System.nanoTime() - t0) / 1e6;
        return new Side(
            "mod WireRouter",
            web,
            WireMetrics.points(web, RouterTuning.DEFAULT),
            mod,
            routeMs,
            total / STEPS,
            worst,
            dropMs,
            fellBack.size());
    }

    // endregion

    // region The website's router, in Java

    private static List<BoxRouter.Box> gridBoxes(final BenchBoard board, final UUID moving, final int dx,
        final int dy) {
        final List<BoxRouter.Box> out = new ArrayList<>();
        for (final BenchBoard.Item i : board.items()) {
            final boolean m = i.id()
                .equals(moving);
            out.add(
                new BoxRouter.Box(
                    i.id()
                        .toString(),
                    i.x() + (m ? dx : 0),
                    i.y() + (m ? dy : 0),
                    i.w(),
                    i.h(),
                    i.drawer()));
        }
        return out;
    }

    private static List<BoxRouter.Wire> gridWires(final BenchBoard board) {
        final Map<UUID, Integer> index = index(board);
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

    private static Side grid(final BenchBoard board, final File inputs) throws IOException {
        final UUID none = new UUID(0, 0);
        final List<BoxRouter.Wire> wires = gridWires(board);
        final List<BoxRouter.Box> boxes = gridBoxes(board, none, 0, 0);
        writeInput(board, boxes, wires, inputs);
        new BoxRouter().route(boxes, wires, true, null);
        final BoxRouter router = new BoxRouter();
        long t0 = System.nanoTime();
        final Map<String, GridRoutedEdge> routes = router.route(boxes, wires, true, null);
        final double routeMs = (System.nanoTime() - t0) / 1e6;
        final WireMetrics.Measure web = WireMetrics.measureRoutes(routes.values());
        final Map<UUID, List<int[]>> pixels = new HashMap<>();
        int fellBack = 0;
        for (final GridRoutedEdge r : routes.values()) {
            pixels.put(UUID.fromString(r.edgeId()), BoxRouter.pixels(r));
            if (r.vertices() == null) fellBack++;
        }
        final RouteMetrics.Result mod = BenchRun.measure(board, pixels);
        final UUID moving = busiest(board).id();
        double total = 0, worst = 0;
        for (int k = 1; k <= STEPS; k++) {
            t0 = System.nanoTime();
            router.route(gridBoxes(board, moving, k * DX, k * DY), wires, false, new IncrementalStats());
            final double ms = (System.nanoTime() - t0) / 1e6;
            total += ms;
            worst = Math.max(worst, ms);
        }
        t0 = System.nanoTime();
        router.route(gridBoxes(board, moving, STEPS * DX, STEPS * DY), wires, true, null);
        final double dropMs = (System.nanoTime() - t0) / 1e6;
        return new Side(
            "web GridEdgeRouter",
            web,
            WireMetrics.points(web, RouterTuning.DEFAULT),
            mod,
            routeMs,
            total / STEPS,
            worst,
            dropMs,
            fellBack);
    }

    /**
     * The board as boxes and wires (the router's requests are built from them by cardRimDocks and the sides, on each
     * side by its own code), for the TypeScript router to replay (tools/conformance/layout).
     */
    private static void writeInput(final BenchBoard board, final List<BoxRouter.Box> boxes,
        final List<BoxRouter.Wire> wires, final File dir) throws IOException {
        final JsonObject o = new JsonObject();
        o.addProperty("name", board.name());
        final JsonArray list = new JsonArray();
        for (final BoxRouter.Box b : boxes) {
            final JsonObject r = new JsonObject();
            r.addProperty("key", b.key());
            r.addProperty("x", (int) b.x());
            r.addProperty("y", (int) b.y());
            r.addProperty("w", (int) b.w());
            r.addProperty("h", (int) b.h());
            if (b.square()) r.addProperty("square", true);
            list.add(r);
        }
        o.add("boxes", list);
        final JsonArray ws = new JsonArray();
        for (final BoxRouter.Wire w : wires) {
            final JsonObject r = new JsonObject();
            r.addProperty("key", w.key());
            r.addProperty("from", w.from());
            r.addProperty("to", w.to());
            r.addProperty("fromSides", w.fromSides());
            r.addProperty("toSides", w.toSides());
            r.addProperty("width", (int) w.width());
            ws.add(r);
        }
        o.add("wires", ws);
        Files.writeString(
            new File(
                dir,
                board.name()
                    .replaceAll("[^A-Za-z0-9._-]+", "_") + ".json").toPath(),
            o.toString(),
            StandardCharsets.UTF_8);
    }

    // endregion
}
