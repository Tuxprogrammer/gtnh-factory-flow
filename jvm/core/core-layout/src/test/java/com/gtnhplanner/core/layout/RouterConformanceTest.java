package com.gtnhplanner.core.layout;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.conformance.Conformance;

/**
 * The Java router against the website's TypeScript router, on the data the website's own router tests and the
 * reference boards produced (conformance/layout, tools/conformance/layout/generate.mjs): the same boards in must give
 * the same routes out, point for point and bit for bit, and the same search work (pops, steps priced, searches),
 * which only a step-for-step translation gives.
 */
class RouterConformanceTest {

    private static JsonObject read(final String name) throws IOException {
        try (InputStream raw = Conformance.open("layout/" + name);
            InputStream in = name.endsWith(".gz") ? new GZIPInputStream(raw) : raw) {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            in.transferTo(bytes);
            return new JsonParser().parse(bytes.toString(StandardCharsets.UTF_8))
                .getAsJsonObject();
        }
    }

    // region Reading the website's JSON

    private static double num(final JsonObject o, final String key, final double fallback) {
        final JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? fallback : e.getAsDouble();
    }

    private static boolean bool(final JsonObject o, final String key, final boolean fallback) {
        final JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? fallback : e.getAsBoolean();
    }

    private static String str(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    static GridPoint point(final JsonElement e) {
        final JsonObject o = e.getAsJsonObject();
        return new GridPoint(num(o, "x", Double.NaN), num(o, "y", Double.NaN));
    }

    static List<GridPoint> points(final JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        final List<GridPoint> out = new ArrayList<>();
        for (final JsonElement p : e.getAsJsonArray()) out.add(point(p));
        return out;
    }

    static GridEndpoint endpoint(final JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        final JsonObject o = e.getAsJsonObject();
        return new GridEndpoint(
            num(o, "x", Double.NaN),
            num(o, "y", Double.NaN),
            GridSide.of(str(o, "side")),
            num(o, "penalty", 0),
            num(o, "stubDepth", 0));
    }

    static List<GridEndpoint> endpoints(final JsonElement e) {
        final List<GridEndpoint> out = new ArrayList<>();
        if (e == null || e.isJsonNull()) return out;
        for (final JsonElement p : e.getAsJsonArray()) out.add(endpoint(p));
        return out;
    }

    static List<String> ids(final JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        final List<String> out = new ArrayList<>();
        for (final JsonElement p : e.getAsJsonArray()) out.add(p.getAsString());
        return out;
    }

    static GridObstacle obstacle(final JsonElement e) {
        final JsonObject o = e.getAsJsonObject();
        return new GridObstacle(
            str(o, "id"),
            num(o, "left", Double.NaN),
            num(o, "top", Double.NaN),
            num(o, "right", Double.NaN),
            num(o, "bottom", Double.NaN));
    }

    static List<GridObstacle> obstacles(final JsonElement e) {
        final List<GridObstacle> out = new ArrayList<>();
        for (final JsonElement p : e.getAsJsonArray()) out.add(obstacle(p));
        return out;
    }

    static GridRouteRequest request(final JsonElement e) {
        final JsonObject o = e.getAsJsonObject();
        return new GridRouteRequest(
            str(o, "edgeId"),
            num(o, "order", 0),
            endpoints(o.get("sources")),
            endpoints(o.get("targets")),
            str(o, "sourceCardId"),
            str(o, "targetCardId"),
            bool(o, "sourceSquare", false),
            bool(o, "targetSquare", false),
            num(o, "strokeWidth", Double.NaN),
            points(o.get("waypoints")),
            ids(o.get("exemptObstacleIds")),
            ids(o.get("homeObstacleIds")));
    }

    static List<GridRouteRequest> requests(final JsonElement e) {
        final List<GridRouteRequest> out = new ArrayList<>();
        for (final JsonElement p : e.getAsJsonArray()) out.add(request(p));
        return out;
    }

    static GridRoutedEdge route(final JsonElement e) {
        final JsonObject o = e.getAsJsonObject();
        final JsonElement width = o.get("width");
        return new GridRoutedEdge(
            str(o, "edgeId"),
            points(o.get("points")),
            width == null || width.isJsonNull() ? null : width.getAsDouble(),
            points(o.get("vertices")),
            endpoint(o.get("source")),
            endpoint(o.get("target")));
    }

    static List<GridRoutedEdge> routes(final JsonElement e) {
        final List<GridRoutedEdge> out = new ArrayList<>();
        for (final JsonElement p : e.getAsJsonArray()) out.add(route(p));
        return out;
    }

    static List<PinnedRoute> pinned(final JsonElement e) {
        final List<PinnedRoute> out = new ArrayList<>();
        if (e == null || e.isJsonNull()) return out;
        for (final JsonElement p : e.getAsJsonArray()) out.add(
            new PinnedRoute(
                request(
                    p.getAsJsonObject()
                        .get("request")),
                route(
                    p.getAsJsonObject()
                        .get("route"))));
        return out;
    }

    static RouterTuning tuning(final JsonElement e) {
        final RouterTuning d = RouterTuning.DEFAULT;
        if (e == null || e.isJsonNull()) return d;
        final JsonObject o = e.getAsJsonObject();
        return new RouterTuning(
            num(o, "costEmpty", d.costEmpty()),
            num(o, "costShared", d.costShared()),
            num(o, "costOverflow", d.costOverflow()),
            num(o, "costInsideExempt", d.costInsideExempt()),
            num(o, "costOutsideHome", d.costOutsideHome()),
            num(o, "turn45", d.turn45()),
            num(o, "turn90", d.turn90()),
            num(o, "reverse", d.reverse()),
            num(o, "earlyTurn", d.earlyTurn()),
            num(o, "cleanCells", d.cleanCells()),
            num(o, "crossing", d.crossing()),
            bool(o, "diagonals", d.diagonals()),
            num(o, "diagonalDistanceCells", d.diagonalDistanceCells()),
            num(o, "dockTravelCells", d.dockTravelCells()),
            num(o, "diagonalLength", d.diagonalLength()),
            num(o, "diagonalLaneCapacity", d.diagonalLaneCapacity()),
            num(o, "dockPlanBias", d.dockPlanBias()),
            num(o, "dockPlanWindow", d.dockPlanWindow()),
            num(o, "dockShare", d.dockShare()),
            num(o, "negotiationRounds", d.negotiationRounds()),
            num(o, "negotiationBudget", d.negotiationBudget()),
            bool(o, "longestFirst", d.longestFirst()),
            num(o, "windowPad", d.windowPad()),
            num(o, "wideRungCells", d.wideRungCells()),
            num(o, "islandAir", d.islandAir()),
            num(o, "arrangeCrossing", d.arrangeCrossing()),
            num(o, "searchTrials", d.searchTrials()),
            num(o, "finalists", d.finalists()),
            num(o, "polishBudget", d.polishBudget()),
            num(o, "arrangeRowGap", d.arrangeRowGap()),
            num(o, "arrangeColumnGap", d.arrangeColumnGap()),
            num(o, "arrangeDrawerGap", d.arrangeDrawerGap()));
    }

    static IncrementalRouter.Snapshot snapshot(final JsonElement e) {
        final JsonObject o = e.getAsJsonObject();
        final List<String[]> requests = new ArrayList<>();
        for (final JsonElement r : o.getAsJsonArray("requests")) requests.add(
            new String[] { r.getAsJsonArray()
                .get(0)
                .getAsString(),
                r.getAsJsonArray()
                    .get(1)
                    .getAsString() });
        return new IncrementalRouter.Snapshot(
            str(o, "tuningKey"),
            obstacles(o.get("obstacles")),
            requests,
            routes(o.get("routes")),
            ids(o.get("rough")));
    }

    // endregion

    // region Comparing

    /** Every difference found, as text: the first few are printed, all are counted. */
    private final List<String> differences = new ArrayList<>();

    private void differ(final String where, final Object want, final Object got) {
        differences.add(where + ": website " + want + ", java " + got);
    }

    private void sameNumber(final String where, final double want, final double got) {
        if (want != got && !(Double.isNaN(want) && Double.isNaN(got))) differ(where, want, got);
    }

    private static String text(final List<GridPoint> points) {
        if (points == null) return "null";
        final StringBuilder s = new StringBuilder();
        for (final GridPoint p : points) s.append('(')
            .append(p.x())
            .append(',')
            .append(p.y())
            .append(')');
        return s.toString();
    }

    private void samePoints(final String where, final List<GridPoint> want, final List<GridPoint> got) {
        if (want == null || got == null) {
            if (want != got) differ(where, text(want), text(got));
            return;
        }
        if (want.size() != got.size()) {
            differ(where, text(want), text(got));
            return;
        }
        for (int i = 0; i < want.size(); i++) {
            if (want.get(i)
                .x()
                != got.get(i)
                    .x()
                || want.get(i)
                    .y()
                    != got.get(i)
                        .y()) {
                differ(where, text(want), text(got));
                return;
            }
        }
    }

    private void sameEndpoint(final String where, final GridEndpoint want, final GridEndpoint got) {
        if (want == null || got == null) {
            if (want != got) differ(where, want, got);
            return;
        }
        if (want.x() != got.x() || want.y() != got.y()
            || want.side() != got.side()
            || want.penalty() != got.penalty()
            || want.stubDepth() != got.stubDepth()) differ(where, want, got);
    }

    private void sameRoutes(final String where, final List<GridRoutedEdge> want,
        final Map<String, GridRoutedEdge> got) {
        final List<GridRoutedEdge> mine = new ArrayList<>(got.values());
        if (want.size() != mine.size()) {
            differ(where + " wire count", want.size(), mine.size());
            return;
        }
        for (int i = 0; i < want.size(); i++) {
            final GridRoutedEdge w = want.get(i), g = mine.get(i);
            final String at = where + " wire " + i + " " + w.edgeId();
            if (!w.edgeId()
                .equals(g.edgeId())) {
                differ(at + " id (install order)", w.edgeId(), g.edgeId());
                continue;
            }
            samePoints(at + " points", w.points(), g.points());
            samePoints(at + " vertices", w.vertices(), g.vertices());
            sameEndpoint(at + " source", w.source(), g.source());
            sameEndpoint(at + " target", w.target(), g.target());
            if (w.width() == null ? g.width() != null : !w.width()
                .equals(g.width())) differ(at + " width", w.width(), g.width());
        }
    }

    private void sameStats(final String where, final JsonObject want, final GridSolveStats got) {
        sameNumber(where + " crossings", num(want, "crossings", Double.NaN), got.crossings);
        if (want.has("rerouted")) sameNumber(where + " rerouted", num(want, "rerouted", 0), got.rerouted);
        if (want.has("fallbacks")) sameNumber(where + " fallbacks", num(want, "fallbacks", 0), got.fallbacks);
        if (want.has("pops")) sameNumber(where + " pops", num(want, "pops", 0), got.pops);
        if (want.has("priced")) sameNumber(where + " priced", num(want, "priced", 0), got.priced);
        if (want.has("searches")) sameNumber(where + " searches", num(want, "searches", 0), got.searches);
    }

    private void sameMeasure(final String where, final JsonObject want, final List<GridRoutedEdge> routes,
        final RouterTuning tuning) {
        final WireMetrics.Measure m = WireMetrics.measureRoutes(routes);
        sameNumber(where + " measure crossings", num(want, "crossings", Double.NaN), m.crossings());
        sameNumber(where + " measure length", num(want, "length", Double.NaN), m.length());
        if (want.has("bends45")) {
            sameNumber(where + " measure bends45", num(want, "bends45", Double.NaN), m.bends45());
            sameNumber(where + " measure bends90", num(want, "bends90", Double.NaN), m.bends90());
            sameNumber(where + " measure bendsSharp", num(want, "bendsSharp", Double.NaN), m.bendsSharp());
            sameNumber(where + " measure weightedLength", num(want, "weightedLength", Double.NaN), m.weightedLength());
            sameNumber(
                where + " measure weightedCrossings",
                num(want, "weightedCrossings", Double.NaN),
                m.weightedCrossings());
        }
        sameNumber(where + " points", num(want, "points", Double.NaN), WireMetrics.points(m, tuning));
    }

    private void finish(final String what, final int cases) {
        if (differences.isEmpty()) return;
        final StringBuilder s = new StringBuilder(
            what + ": " + differences.size() + " differences over " + cases + " cases; the first ones:\n");
        for (int i = 0; i < Math.min(20, differences.size()); i++) s.append("  ")
            .append(differences.get(i))
            .append('\n');
        fail(s.toString());
    }

    // endregion

    /** One router for every solve, as the website's module keeps one set of pools: they must not carry anything over. */
    private final GridEdgeRouter shared = new GridEdgeRouter();

    @TestFactory
    List<DynamicTest> solvesMatchTheWebsitesRouterTests() throws IOException {
        final JsonArray solves = read("router-tests.json").getAsJsonArray("solves");
        assertTrue(solves.size() > 30, "the website's router tests make more solves than that");
        final List<DynamicTest> tests = new ArrayList<>();
        int n = 0;
        for (final JsonElement e : solves) {
            final JsonObject c = e.getAsJsonObject();
            final String where = "solve " + n++ + " (" + str(c, "name") + ")";
            tests.add(DynamicTest.dynamicTest(where, () -> solve(where, c)));
        }
        return tests;
    }

    private void solve(final String where, final JsonObject c) {
        differences.clear();
        final GridEdgeRouter router = shared;
        final RouterTuning tuning = tuning(c.get("tuning"));
        final GridSolveStats stats = new GridSolveStats();
        final Map<String, GridRoutedEdge> got = router.solve(
            obstacles(c.get("obstacles")),
            requests(c.get("requests")),
            stats,
            tuning,
            pinned(c.get("pinned")));
        sameRoutes(where, routes(c.get("routes")), got);
        sameStats(where, c.getAsJsonObject("stats"), stats);
        sameMeasure(where, c.getAsJsonObject("measure"), new ArrayList<>(got.values()), tuning);
        finish(where, 1);
    }

    @TestFactory
    List<DynamicTest> incrementalSequencesMatch() throws IOException {
        final JsonArray sequences = read("router-tests.json").getAsJsonArray("sequences");
        assertTrue(sequences.size() >= 8, "the incremental router's tests run more routers than that");
        final List<DynamicTest> tests = new ArrayList<>();
        int n = 0;
        for (final JsonElement e : sequences) {
            final JsonObject sequence = e.getAsJsonObject();
            final int at = n++;
            tests.add(DynamicTest.dynamicTest("sequence " + at + " (" + str(sequence, "name") + ")", () -> sequence(at, sequence)));
        }
        return tests;
    }

    private void sequence(final int n, final JsonObject sequence) {
        differences.clear();
        final IncrementalRouter router = new IncrementalRouter();
        int step = 0;
        for (final JsonElement s : sequence.getAsJsonArray("steps")) {
            final JsonObject c = s.getAsJsonObject();
            final String where = "sequence " + n + " (" + str(sequence, "name") + ") step " + step++;
            if (c.has("restore")) {
                router.restore(snapshot(c.get("restore")));
                continue;
            }
            if (c.has("reset")) {
                router.reset();
                continue;
            }
            final IncrementalStats stats = new IncrementalStats();
            final Map<String, GridRoutedEdge> got = router.solve(
                obstacles(c.get("obstacles")),
                requests(c.get("requests")),
                tuning(c.get("tuning")),
                bool(c, "thorough", true),
                stats);
            sameRoutes(where, routes(c.get("routes")), got);
            final JsonObject want = c.getAsJsonObject("stats");
            if (bool(want, "fresh", false) != stats.fresh) differ(where + " fresh", want.get("fresh"), stats.fresh);
            sameNumber(where + " routed", num(want, "routed", Double.NaN), stats.routed);
            sameNumber(where + " kept", num(want, "kept", Double.NaN), stats.kept);
            sameStats(where, want, stats);
        }
        finish("sequence " + n, 1);
    }

    @Test
    void rimDocksMatch() throws IOException {
        final JsonArray cases = read("router-tests.json").getAsJsonArray("rimDocks");
        assertFalse(cases.size() == 0);
        int n = 0;
        for (final JsonElement e : cases) {
            final JsonObject c = e.getAsJsonObject();
            final JsonObject r = c.getAsJsonObject("rect");
            final List<GridEndpoint> got = GridEdgeRouter
                .cardRimDocks(num(r, "left", 0), num(r, "top", 0), num(r, "right", 0), num(r, "bottom", 0));
            final List<GridEndpoint> want = endpoints(c.get("docks"));
            final String where = "rim " + n++ + " " + r;
            if (want.size() != got.size()) differ(where + " dock count", want.size(), got.size());
            else for (int i = 0; i < want.size(); i++) sameEndpoint(where + " dock " + i, want.get(i), got.get(i));
        }
        finish("rim docks", n);
    }

    @TestFactory
    List<DynamicTest> referenceBoardsMatchFreshDraggedAndDropped() throws IOException {
        final JsonArray boards = read("boards.json.gz").getAsJsonArray("boards");
        assertTrue(boards.size() > 0);
        final List<DynamicTest> tests = new ArrayList<>();
        for (final JsonElement e : boards) {
            final JsonObject board = e.getAsJsonObject();
            tests.add(DynamicTest.dynamicTest("board " + str(board, "name"), () -> board(board)));
        }
        return tests;
    }

    private void board(final JsonObject board) {
        differences.clear();
        final List<BoxRouter.Box> boxes = new ArrayList<>();
        for (final JsonElement b : board.getAsJsonArray("boxes")) {
            final JsonObject o = b.getAsJsonObject();
            boxes.add(
                new BoxRouter.Box(
                    str(o, "key"),
                    num(o, "x", 0),
                    num(o, "y", 0),
                    num(o, "w", 0),
                    num(o, "h", 0),
                    bool(o, "square", false)));
        }
        final List<BoxRouter.Wire> wires = new ArrayList<>();
        for (final JsonElement w : board.getAsJsonArray("wires")) {
            final JsonObject o = w.getAsJsonObject();
            wires.add(
                new BoxRouter.Wire(
                    str(o, "key"),
                    (int) num(o, "from", 0),
                    (int) num(o, "to", 0),
                    (int) num(o, "fromSides", 15),
                    (int) num(o, "toSides", 15),
                    num(o, "width", 4),
                    List.of()));
        }
        final int moving = (int) num(board, "moving", 0);
        final BoxRouter router = new BoxRouter();
        for (final JsonElement s : board.getAsJsonArray("steps")) {
            final JsonObject c = s.getAsJsonObject();
            final double shift = num(c, "shift", 0);
            final List<BoxRouter.Box> at = new ArrayList<>(boxes);
            final BoxRouter.Box m = at.get(moving);
            at.set(moving, new BoxRouter.Box(m.key(), m.x() + shift, m.y(), m.w(), m.h(), m.square()));
            final IncrementalStats stats = new IncrementalStats();
            final Map<String, GridRoutedEdge> got = router.route(at, wires, bool(c, "thorough", true), stats);
            final String where = "board " + str(board, "name") + " shift " + shift;
            sameRoutes(where, routes(c.get("routes")), got);
            final JsonObject want = c.getAsJsonObject("stats");
            sameNumber(where + " routed", num(want, "routed", Double.NaN), stats.routed);
            sameNumber(where + " kept", num(want, "kept", Double.NaN), stats.kept);
            sameStats(where, want, stats);
            sameMeasure(where, c.getAsJsonObject("measure"), new ArrayList<>(got.values()), RouterTuning.DEFAULT);
        }
        finish("board " + str(board, "name"), 1);
    }
}
