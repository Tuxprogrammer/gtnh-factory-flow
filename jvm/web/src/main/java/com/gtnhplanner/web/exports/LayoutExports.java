package com.gtnhplanner.web.exports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.teavm.jso.JSExport;

import com.gtnhplanner.core.layout.BoxRouter;
import com.gtnhplanner.core.layout.GridEdgeRouter;
import com.gtnhplanner.core.layout.GridEndpoint;
import com.gtnhplanner.core.layout.GridObstacle;
import com.gtnhplanner.core.layout.GridPoint;
import com.gtnhplanner.core.layout.GridRouteRequest;
import com.gtnhplanner.core.layout.GridRoutedEdge;
import com.gtnhplanner.core.layout.GridSide;
import com.gtnhplanner.core.layout.GridSolveStats;
import com.gtnhplanner.core.layout.IncrementalRouter;
import com.gtnhplanner.core.layout.IncrementalStats;
import com.gtnhplanner.core.layout.PinnedRoute;
import com.gtnhplanner.core.layout.RouterTuning;
import com.gtnhplanner.core.layout.WireMetrics;
import com.gtnhplanner.web.json.Json;

/**
 * core-layout for JavaScript, as a minimal JSON-in, JSON-out entry: the whole of one routing call in one string, the
 * result in one string. The shapes are conformance/layout's own (router-tests.json and boards.json.gz): a case there
 * goes in as it is (the expected fields are ignored) and the answer has the shape of its expected fields. Missing
 * numbers read as 0 (penalty, stubDepth, order) and a missing tuning as the defaults, as the website's router reads
 * them. This is the stop-gap the web loader uses to run the conformance files in Node; the layout stream owns the
 * real entry.
 */
public final class LayoutExports {

    private LayoutExports() {}

    /**
     * One solve: {obstacles, requests, tuning?, pinned?}; returns {routes, stats, measure}. Routes are in install
     * order.
     */
    @JSExport
    public static String layoutSolve(final String caseJson) {
        final Map<String, Object> c = Json.object(Json.parse(caseJson));
        final RouterTuning tuning = tuning(c.get("tuning"));
        final GridSolveStats stats = new GridSolveStats();
        final Map<String, GridRoutedEdge> got = new GridEdgeRouter()
            .solve(obstacles(c.get("obstacles")), requests(c.get("requests")), stats, tuning, pinned(c.get("pinned")));
        return Json.write(result(got, stats, tuning, false));
    }

    /**
     * An incremental sequence on one fresh router: {steps: [step]} where a step is {restore: snapshot}, {reset: true}
     * or {obstacles, requests, tuning?, thorough?}. Returns an array with one {routes, stats, measure} per solving
     * step.
     */
    @JSExport
    public static String layoutSequence(final String sequenceJson) {
        final Map<String, Object> sequence = Json.object(Json.parse(sequenceJson));
        final IncrementalRouter router = new IncrementalRouter();
        final List<Object> out = new ArrayList<>();
        for (final Object s : Json.array(sequence.get("steps"))) {
            final Map<String, Object> c = Json.object(s);
            if (c.containsKey("restore")) {
                router.restore(snapshot(Json.object(c.get("restore"))));
                continue;
            }
            if (c.containsKey("reset")) {
                router.reset();
                continue;
            }
            final RouterTuning tuning = tuning(c.get("tuning"));
            final IncrementalStats stats = new IncrementalStats();
            final Map<String, GridRoutedEdge> got = router
                .solve(obstacles(c.get("obstacles")), requests(c.get("requests")), tuning, Json.bool(c, "thorough", true), stats);
            out.add(result(got, stats, tuning, true));
        }
        return Json.write(out);
    }

    /** The docks a card's rectangle offers: {left, top, right, bottom}; returns an array of endpoints. */
    @JSExport
    public static String layoutRimDocks(final String rectJson) {
        final Map<String, Object> r = Json.object(Json.parse(rectJson));
        final List<Object> out = new ArrayList<>();
        for (final GridEndpoint e : GridEdgeRouter
            .cardRimDocks(Json.num(r, "left", 0), Json.num(r, "top", 0), Json.num(r, "right", 0), Json.num(r, "bottom", 0))) {
            out.add(endpoint(e));
        }
        return Json.write(out);
    }

    /**
     * A board of boxes and wires routed by BoxRouter: {boxes, wires, moving, steps: [{shift, thorough}]}, the box
     * "moving" shifted along x by each step's shift on one router. Returns an array with one {routes, stats, measure}
     * per step.
     */
    @JSExport
    public static String layoutBoard(final String boardJson) {
        final Map<String, Object> board = Json.object(Json.parse(boardJson));
        final List<BoxRouter.Box> boxes = new ArrayList<>();
        for (final Object b : Json.array(board.get("boxes"))) {
            final Map<String, Object> o = Json.object(b);
            boxes.add(new BoxRouter.Box(Json.str(o, "key"), Json.num(o, "x", 0), Json.num(o, "y", 0), Json.num(o, "w", 0),
                Json.num(o, "h", 0), Json.bool(o, "square", false)));
        }
        final List<BoxRouter.Wire> wires = new ArrayList<>();
        for (final Object w : Json.array(board.get("wires"))) {
            final Map<String, Object> o = Json.object(w);
            wires.add(new BoxRouter.Wire(Json.str(o, "key"), (int) Json.num(o, "from", 0), (int) Json.num(o, "to", 0),
                (int) Json.num(o, "fromSides", 15), (int) Json.num(o, "toSides", 15), Json.num(o, "width", 4),
                new ArrayList<>()));
        }
        final int moving = (int) Json.num(board, "moving", 0);
        final BoxRouter router = new BoxRouter();
        final List<Object> out = new ArrayList<>();
        for (final Object s : Json.array(board.get("steps"))) {
            final Map<String, Object> c = Json.object(s);
            final double shift = Json.num(c, "shift", 0);
            final List<BoxRouter.Box> at = new ArrayList<>(boxes);
            final BoxRouter.Box m = at.get(moving);
            at.set(moving, new BoxRouter.Box(m.key(), m.x() + shift, m.y(), m.w(), m.h(), m.square()));
            final IncrementalStats stats = new IncrementalStats();
            final Map<String, GridRoutedEdge> got = router.route(at, wires, Json.bool(c, "thorough", true), stats);
            out.add(result(got, stats, RouterTuning.DEFAULT, true));
        }
        return Json.write(out);
    }

    // ---- reading the shapes

    private static GridPoint point(final Object e) {
        final Map<String, Object> o = Json.object(e);
        return new GridPoint(Json.num(o, "x", Double.NaN), Json.num(o, "y", Double.NaN));
    }

    private static List<GridPoint> points(final Object e) {
        if (!(e instanceof List)) return null;
        final List<GridPoint> out = new ArrayList<>();
        for (final Object p : Json.array(e)) out.add(point(p));
        return out;
    }

    private static GridEndpoint endpoint(final Object e) {
        if (!(e instanceof Map)) return null;
        final Map<String, Object> o = Json.object(e);
        return new GridEndpoint(Json.num(o, "x", Double.NaN), Json.num(o, "y", Double.NaN), GridSide.of(Json.str(o, "side")),
            Json.num(o, "penalty", 0), Json.num(o, "stubDepth", 0));
    }

    private static List<GridEndpoint> endpoints(final Object e) {
        final List<GridEndpoint> out = new ArrayList<>();
        for (final Object p : Json.array(e)) out.add(endpoint(p));
        return out;
    }

    private static List<String> ids(final Object e) {
        if (!(e instanceof List)) return null;
        final List<String> out = new ArrayList<>();
        for (final Object p : Json.array(e)) out.add((String) p);
        return out;
    }

    private static List<GridObstacle> obstacles(final Object e) {
        final List<GridObstacle> out = new ArrayList<>();
        for (final Object p : Json.array(e)) {
            final Map<String, Object> o = Json.object(p);
            out.add(new GridObstacle(Json.str(o, "id"), Json.num(o, "left", Double.NaN), Json.num(o, "top", Double.NaN),
                Json.num(o, "right", Double.NaN), Json.num(o, "bottom", Double.NaN)));
        }
        return out;
    }

    private static GridRouteRequest request(final Object e) {
        final Map<String, Object> o = Json.object(e);
        return new GridRouteRequest(Json.str(o, "edgeId"), Json.num(o, "order", 0), endpoints(o.get("sources")),
            endpoints(o.get("targets")), Json.str(o, "sourceCardId"), Json.str(o, "targetCardId"),
            Json.bool(o, "sourceSquare", false), Json.bool(o, "targetSquare", false), Json.num(o, "strokeWidth", Double.NaN),
            points(o.get("waypoints")), ids(o.get("exemptObstacleIds")), ids(o.get("homeObstacleIds")));
    }

    private static List<GridRouteRequest> requests(final Object e) {
        final List<GridRouteRequest> out = new ArrayList<>();
        for (final Object p : Json.array(e)) out.add(request(p));
        return out;
    }

    private static GridRoutedEdge route(final Object e) {
        final Map<String, Object> o = Json.object(e);
        return new GridRoutedEdge(Json.str(o, "edgeId"), points(o.get("points")), Json.boxed(o, "width"),
            points(o.get("vertices")), endpoint(o.get("source")), endpoint(o.get("target")));
    }

    private static List<GridRoutedEdge> routes(final Object e) {
        final List<GridRoutedEdge> out = new ArrayList<>();
        for (final Object p : Json.array(e)) out.add(route(p));
        return out;
    }

    private static List<PinnedRoute> pinned(final Object e) {
        final List<PinnedRoute> out = new ArrayList<>();
        for (final Object p : Json.array(e)) {
            final Map<String, Object> o = Json.object(p);
            out.add(new PinnedRoute(request(o.get("request")), route(o.get("route"))));
        }
        return out;
    }

    private static IncrementalRouter.Snapshot snapshot(final Map<String, Object> o) {
        final List<String[]> requests = new ArrayList<>();
        for (final Object r : Json.array(o.get("requests"))) {
            final List<Object> pair = Json.array(r);
            requests.add(new String[] { (String) pair.get(0), (String) pair.get(1) });
        }
        return new IncrementalRouter.Snapshot(Json.str(o, "tuningKey"), obstacles(o.get("obstacles")), requests,
            routes(o.get("routes")), ids(o.get("rough")));
    }

    private static RouterTuning tuning(final Object e) {
        final RouterTuning d = RouterTuning.DEFAULT;
        if (!(e instanceof Map)) return d;
        final Map<String, Object> o = Json.object(e);
        return new RouterTuning(Json.num(o, "costEmpty", d.costEmpty()), Json.num(o, "costShared", d.costShared()),
            Json.num(o, "costOverflow", d.costOverflow()), Json.num(o, "costInsideExempt", d.costInsideExempt()),
            Json.num(o, "costOutsideHome", d.costOutsideHome()), Json.num(o, "turn45", d.turn45()),
            Json.num(o, "turn90", d.turn90()), Json.num(o, "reverse", d.reverse()), Json.num(o, "earlyTurn", d.earlyTurn()),
            Json.num(o, "cleanCells", d.cleanCells()), Json.num(o, "crossing", d.crossing()),
            Json.bool(o, "diagonals", d.diagonals()), Json.num(o, "diagonalDistanceCells", d.diagonalDistanceCells()),
            Json.num(o, "dockTravelCells", d.dockTravelCells()), Json.num(o, "diagonalLength", d.diagonalLength()),
            Json.num(o, "diagonalLaneCapacity", d.diagonalLaneCapacity()), Json.num(o, "dockPlanBias", d.dockPlanBias()),
            Json.num(o, "dockPlanWindow", d.dockPlanWindow()), Json.num(o, "dockShare", d.dockShare()),
            Json.num(o, "negotiationRounds", d.negotiationRounds()), Json.num(o, "negotiationBudget", d.negotiationBudget()),
            Json.bool(o, "longestFirst", d.longestFirst()), Json.num(o, "windowPad", d.windowPad()),
            Json.num(o, "wideRungCells", d.wideRungCells()), Json.num(o, "islandAir", d.islandAir()),
            Json.num(o, "arrangeCrossing", d.arrangeCrossing()), Json.num(o, "searchTrials", d.searchTrials()),
            Json.num(o, "finalists", d.finalists()), Json.num(o, "polishBudget", d.polishBudget()),
            Json.num(o, "arrangeRowGap", d.arrangeRowGap()), Json.num(o, "arrangeColumnGap", d.arrangeColumnGap()),
            Json.num(o, "arrangeDrawerGap", d.arrangeDrawerGap()));
    }

    // ---- writing the answers

    private static Map<String, Object> pointJson(final GridPoint p) {
        final Map<String, Object> o = new LinkedHashMap<>();
        o.put("x", p.x());
        o.put("y", p.y());
        return o;
    }

    private static List<Object> pointsJson(final List<GridPoint> points) {
        if (points == null) return null;
        final List<Object> out = new ArrayList<>();
        for (final GridPoint p : points) out.add(pointJson(p));
        return out;
    }

    private static Map<String, Object> endpoint(final GridEndpoint e) {
        if (e == null) return null;
        final Map<String, Object> o = new LinkedHashMap<>();
        o.put("x", e.x());
        o.put("y", e.y());
        o.put("side", e.side() == null ? null : e.side().json);
        o.put("penalty", e.penalty());
        o.put("stubDepth", e.stubDepth());
        return o;
    }

    private static Map<String, Object> result(final Map<String, GridRoutedEdge> got, final GridSolveStats stats,
        final RouterTuning tuning, final boolean incremental) {
        final Map<String, Object> out = new LinkedHashMap<>();
        final List<GridRoutedEdge> routes = new ArrayList<>(got.values());
        final List<Object> list = new ArrayList<>();
        for (final GridRoutedEdge r : routes) {
            final Map<String, Object> o = new LinkedHashMap<>();
            o.put("edgeId", r.edgeId());
            o.put("width", r.width());
            o.put("points", pointsJson(r.points()));
            o.put("vertices", pointsJson(r.vertices()));
            o.put("source", endpoint(r.source()));
            o.put("target", endpoint(r.target()));
            list.add(o);
        }
        out.put("routes", list);
        final Map<String, Object> s = new LinkedHashMap<>();
        s.put("crossings", stats.crossings);
        s.put("rerouted", stats.rerouted);
        s.put("fallbacks", stats.fallbacks);
        s.put("pops", stats.pops);
        s.put("priced", stats.priced);
        s.put("searches", stats.searches);
        if (incremental && stats instanceof final IncrementalStats inc) {
            s.put("fresh", inc.fresh);
            s.put("routed", inc.routed);
            s.put("kept", inc.kept);
        }
        out.put("stats", s);
        final WireMetrics.Measure m = WireMetrics.measureRoutes(routes);
        final Map<String, Object> measure = new LinkedHashMap<>();
        measure.put("crossings", m.crossings());
        measure.put("length", m.length());
        measure.put("bends45", m.bends45());
        measure.put("bends90", m.bends90());
        measure.put("bendsSharp", m.bendsSharp());
        measure.put("weightedLength", m.weightedLength());
        measure.put("weightedCrossings", m.weightedCrossings());
        measure.put("points", WireMetrics.points(m, tuning));
        out.put("measure", measure);
        return out;
    }
}
