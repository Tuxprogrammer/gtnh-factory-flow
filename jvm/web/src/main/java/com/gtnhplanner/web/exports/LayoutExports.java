package com.gtnhplanner.web.exports;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSExport;
import org.teavm.jso.JSObject;

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
import com.gtnhplanner.core.layout.LiveDrag;
import com.gtnhplanner.core.layout.PinnedRoute;
import com.gtnhplanner.core.layout.RouteJudge;
import com.gtnhplanner.core.layout.RouterBoard;
import com.gtnhplanner.core.layout.RouterTuning;
import com.gtnhplanner.core.layout.WireMetrics;
import com.gtnhplanner.core.layout.arrange.Arrange;
import com.gtnhplanner.core.layout.arrange.ArrangeCard;
import com.gtnhplanner.core.layout.arrange.ArrangeInk;
import com.gtnhplanner.core.layout.arrange.ArrangeResult;
import com.gtnhplanner.core.layout.arrange.ArrangeWire;
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


    // ---- a board held between calls (the website's route worker)

    private static final Map<Integer, RouterBoard> BOARDS = new HashMap<>();
    private static final Map<Integer, RouterTuning> BOARD_TUNING = new HashMap<>();
    private static int nextHandle = 1;

    /** A new board, empty: its handle. Handles belong to the thread (each Worker loads its own core). */
    @JSExport
    public static int layoutBoardOpen() {
        final int handle = nextHandle++;
        BOARDS.put(handle, new RouterBoard());
        BOARD_TUNING.put(handle, RouterTuning.DEFAULT);
        return handle;
    }

    /** Lets a board go. */
    @JSExport
    public static void layoutBoardClose(final int handle) {
        BOARDS.remove(handle);
        BOARD_TUNING.remove(handle);
    }

    private static RouterBoard board(final int handle) {
        final RouterBoard board = BOARDS.get(handle);
        if (board == null) throw new IllegalArgumentException("no layout board " + handle);
        return board;
    }

    /**
     * What changed on the board since the last call, then a route of the board as it stands. In: {thorough, tuning?
     * (whole, when it changed), obstacles? [put], obstaclesGone? [id], obstacleOrder? [id, every one, when the order
     * moved], requests? [put, compact], requestsGone? [id], requestOrder? [id]}. A compact request is a request on
     * router-tests.json's shape whose sources and targets are flat number lists, five per dock: x, y, side (0 left,
     * 1 right, 2 top, 3 bottom), penalty, stubDepth. Out: {routes: [{edgeId, points: [x, y, x, y ...]}] (only those
     * whose points differ from the last answer's, in install order), gone: [edge ids no longer routed], stats}.
     */
    @JSExport
    public static String layoutBoardRoute(final int handle, final String deltaJson) {
        final RouterBoard board = board(handle);
        final Map<String, Object> d = Json.object(Json.parse(deltaJson));
        if (d.get("tuning") instanceof Map) BOARD_TUNING.put(handle, tuning(d.get("tuning")));
        for (final Object id : Json.array(d.get("obstaclesGone"))) board.removeObstacle((String) id);
        for (final GridObstacle o : obstacles(d.get("obstacles"))) board.putObstacle(o);
        if (d.get("obstacleOrder") instanceof List) board.orderObstacles(ids(d.get("obstacleOrder")));
        for (final Object id : Json.array(d.get("requestsGone"))) board.removeRequest((String) id);
        for (final Object r : Json.array(d.get("requests"))) board.putRequest(compactRequest(r));
        if (d.get("requestOrder") instanceof List) board.orderRequests(ids(d.get("requestOrder")));
        final RouterBoard.Changes changes = board.route(BOARD_TUNING.get(handle), Json.bool(d, "thorough", true));
        final StringBuilder out = new StringBuilder(64 + 160 * changes.routes()
            .size());
        out.append("{\"routes\":[");
        boolean first = true;
        for (final GridRoutedEdge r : changes.routes()) {
            if (!first) out.append(',');
            first = false;
            out.append("{\"edgeId\":");
            Json.write(out, r.edgeId());
            out.append(",\"points\":[");
            boolean firstPoint = true;
            for (final GridPoint p : r.points()) {
                if (!firstPoint) out.append(',');
                firstPoint = false;
                Json.write(out, p.x());
                out.append(',');
                Json.write(out, p.y());
            }
            out.append("]}");
        }
        out.append("],\"gone\":");
        Json.write(out, changes.gone());
        final IncrementalStats s = changes.stats();
        final Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("fresh", s.fresh);
        stats.put("routed", s.routed);
        stats.put("kept", s.kept);
        stats.put("crossings", s.crossings);
        stats.put("pops", s.pops);
        stats.put("searches", s.searches);
        out.append(",\"stats\":");
        Json.write(out, stats);
        out.append('}');
        return out.toString();
    }

    /** The board's router state on incremental-router.ts's RouterSnapshot shape, or null before its first route. */
    @JSExport
    @Nullable
    public static String layoutBoardSnapshot(final int handle) {
        final IncrementalRouter.Snapshot s = board(handle).router()
            .snapshot();
        if (s == null) return null;
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("tuningKey", s.tuningKey());
        final List<Object> obstacles = new ArrayList<>();
        for (final GridObstacle o : s.obstacles()) {
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", o.id());
            m.put("left", o.left());
            m.put("top", o.top());
            m.put("right", o.right());
            m.put("bottom", o.bottom());
            obstacles.add(m);
        }
        out.put("obstacles", obstacles);
        final List<Object> requests = new ArrayList<>();
        for (final String[] r : s.requests()) requests.add(List.of(r[0], r[1]));
        out.put("requests", requests);
        final List<Object> routes = new ArrayList<>();
        for (final GridRoutedEdge r : s.routes()) {
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("edgeId", r.edgeId());
            m.put("points", pointsJson(r.points()));
            if (r.width() != null) m.put("width", r.width());
            if (r.vertices() != null) m.put("vertices", pointsJson(r.vertices()));
            if (r.source() != null) m.put("source", storedEndpoint(r.source()));
            if (r.target() != null) m.put("target", storedEndpoint(r.target()));
            routes.add(m);
        }
        out.put("routes", routes);
        out.put("rough", new ArrayList<Object>(s.rough()));
        return Json.write(out);
    }

    /** A dock as the website stores it: no stubDepth when it is 0 (the router reads an absent one as 0). */
    private static Map<String, Object> storedEndpoint(final GridEndpoint e) {
        final Map<String, Object> o = endpoint(e);
        if (e.stubDepth() == 0) o.remove("stubDepth");
        return o;
    }

    /** Takes up a stored router state (RouterSnapshot's shape): the host then puts the whole board again. */
    @JSExport
    public static void layoutBoardRestore(final int handle, final String snapshotJson) {
        board(handle).restore(snapshot(Json.object(Json.parse(snapshotJson))));
    }

    private static final GridSide[] SIDE_BY_INDEX = { GridSide.LEFT, GridSide.RIGHT, GridSide.TOP, GridSide.BOTTOM };

    private static List<GridEndpoint> flatEndpoints(final Object e) {
        final List<Object> n = Json.array(e);
        final List<GridEndpoint> out = new ArrayList<>(n.size() / 5);
        for (int i = 0; i + 4 < n.size(); i += 5) {
            final int side = (int) (double) (Double) n.get(i + 2);
            out.add(new GridEndpoint((Double) n.get(i), (Double) n.get(i + 1),
                side >= 0 && side < 4 ? SIDE_BY_INDEX[side] : GridSide.LEFT, (Double) n.get(i + 3),
                (Double) n.get(i + 4)));
        }
        return out;
    }

    private static GridRouteRequest compactRequest(final Object e) {
        final Map<String, Object> o = Json.object(e);
        return new GridRouteRequest(Json.str(o, "edgeId"), Json.num(o, "order", 0), flatEndpoints(o.get("sources")),
            flatEndpoints(o.get("targets")), Json.str(o, "sourceCardId"), Json.str(o, "targetCardId"),
            Json.bool(o, "sourceSquare", false), Json.bool(o, "targetSquare", false), Json.num(o, "strokeWidth", Double.NaN),
            points(o.get("waypoints")), ids(o.get("exemptObstacleIds")), ids(o.get("homeObstacleIds")));
    }

    // ---- Arrange (the website's arrange worker)

    /** Where an arrange reports its progress: a plain object with {@code progress(step, stage, done, total)}. */
    public interface ArrangeSink extends JSObject {}

    @JSBody(params = { "sink", "step", "stage", "done", "total" },
        script = "if (sink && sink.progress) sink.progress(step, stage, done, total);")
    private static native void tell(ArrangeSink sink, int step, String stage, double done, double total);

    /**
     * One arrange job on arrange-job.ts's ArrangeJob shape (cards, wires, ink?, taste?, origin?, polishBudget?,
     * judgeInput? {obstacles, requests, tuning, riders?}, stage? "columns" | "free" | "polish", candidates?, which?),
     * answered as runArrangeJob answers it: {result, columns?, polishedPoints?, polishedCrossings?}. Progress goes to
     * the sink as it happens. The polish's clock is the host's.
     */
    @JSExport
    public static String layoutArrange(final String jobJson, final ArrangeSink sink) {
        final Map<String, Object> job = Json.object(Json.parse(jobJson));
        Arrange.Judge judge = null;
        RouterTuning tuning = null;
        if (job.get("judgeInput") instanceof Map) {
            final Map<String, Object> ji = Json.object(job.get("judgeInput"));
            tuning = tuning(ji.get("tuning"));
            final List<String[]> riders = new ArrayList<>();
            for (final Object r : Json.array(ji.get("riders"))) {
                final List<Object> pair = Json.array(r);
                riders.add(new String[] { (String) pair.get(0), (String) pair.get(1) });
            }
            final RouteJudge routeJudge = new RouteJudge(obstacles(ji.get("obstacles")), requests(ji.get("requests")),
                tuning, riders);
            judge = (positions, quick, base) -> {
                final RouteJudge.Verdict v = routeJudge.judge(positions, quick, base);
                return new Arrange.Verdict(v.crossings(), v.length(), v.points(), v.events());
            };
        }
        final Map<String, Object> taste = Json.object(job.get("taste"));
        final Arrange.Input input = new Arrange.Input(arrangeCards(job.get("cards")), arrangeWires(job.get("wires")),
            job.get("ink") instanceof List ? arrangeInk(job.get("ink")) : null,
            job.get("origin") instanceof Map ? point(job.get("origin")) : null, Json.str(taste, "spacing"), judge,
            Json.boxed(job, "polishBudget"), tuning,
            p -> tell(sink, p.step(), p.stage(), p.done(), p.total()), null, null);
        final String stage = Json.str(job, "stage");
        final Map<String, Object> out = new LinkedHashMap<>();
        if ("columns".equals(stage)) {
            final Arrange.Columns columns = Arrange.arrangeColumnStage(input);
            out.put("result", arrangeResult(columns.plain()));
            final Map<String, Object> c = new LinkedHashMap<>();
            c.put("plain", arrangeResult(columns.plain()));
            c.put("challenger", arrangeResult(columns.challenger()));
            out.put("columns", c);
        } else if ("free".equals(stage)) {
            out.put("result", arrangeResult(Arrange.arrangeFreeStage(input)));
        } else if ("polish".equals(stage) && job.get("candidates") instanceof Map) {
            final Map<String, Object> c = Json.object(job.get("candidates"));
            final Arrange.Polished polished = Arrange.arrangePolishStage(input,
                new Arrange.Candidates(readArrangeResult(c.get("plain")), readArrangeResult(c.get("challenger")),
                    readArrangeResult(c.get("free"))),
                (int) Json.num(job, "which", 0));
            out.put("result", arrangeResult(polished.result()));
            out.put("polishedPoints", polished.points());
            out.put("polishedCrossings", polished.crossings());
        } else {
            out.put("result", arrangeResult(Arrange.arrangeBoard(input)));
        }
        return Json.write(out);
    }

    private static List<ArrangeCard> arrangeCards(final Object e) {
        final List<ArrangeCard> out = new ArrayList<>();
        for (final Object p : Json.array(e)) {
            final Map<String, Object> o = Json.object(p);
            out.add(new ArrangeCard(Json.str(o, "id"), Json.num(o, "x", Double.NaN), Json.num(o, "y", Double.NaN),
                Json.num(o, "width", Double.NaN), Json.num(o, "height", Double.NaN), "storage".equals(Json.str(o, "role"))));
        }
        return out;
    }

    private static List<ArrangeWire> arrangeWires(final Object e) {
        final List<ArrangeWire> out = new ArrayList<>();
        for (final Object p : Json.array(e)) {
            final Map<String, Object> o = Json.object(p);
            out.add(new ArrangeWire(Json.str(o, "id"), Json.str(o, "source"), Json.str(o, "target"),
                Json.boxed(o, "sourcePortY"), Json.boxed(o, "targetPortY"), Json.boxed(o, "weight"),
                Json.boxed(o, "width")));
        }
        return out;
    }

    private static List<ArrangeInk> arrangeInk(final Object e) {
        final List<ArrangeInk> out = new ArrayList<>();
        for (final Object p : Json.array(e)) {
            final Map<String, Object> o = Json.object(p);
            out.add(new ArrangeInk(Json.str(o, "id"), Json.num(o, "x", Double.NaN), Json.num(o, "y", Double.NaN),
                Json.num(o, "width", Double.NaN), Json.num(o, "height", Double.NaN)));
        }
        return out;
    }

    private static ArrangeResult readArrangeResult(final Object e) {
        final Map<String, Object> o = Json.object(e);
        final List<ArrangeResult.Move> moves = new ArrayList<>();
        for (final Object m : Json.array(o.get("moves"))) {
            final Map<String, Object> mo = Json.object(m);
            moves.add(new ArrangeResult.Move(Json.str(mo, "id"), point(mo.get("position"))));
        }
        final List<ArrangeResult.Island> islands = new ArrayList<>();
        for (final Object i : Json.array(o.get("islands"))) {
            final Map<String, Object> io = Json.object(i);
            islands.add(new ArrangeResult.Island(Json.num(io, "x", Double.NaN), Json.num(io, "y", Double.NaN),
                Json.num(io, "width", Double.NaN), Json.num(io, "height", Double.NaN), Json.bool(io, "backdrop", false)));
        }
        final List<ArrangeResult.WireRoute> wireRoutes = new ArrayList<>();
        for (final Object w : Json.array(o.get("wireRoutes"))) {
            final Map<String, Object> wo = Json.object(w);
            wireRoutes.add(new ArrangeResult.WireRoute(Json.str(wo, "id"), points(wo.get("waypoints"))));
        }
        return new ArrangeResult(moves, islands, wireRoutes);
    }

    private static Map<String, Object> arrangeResult(final ArrangeResult r) {
        final Map<String, Object> out = new LinkedHashMap<>();
        final List<Object> moves = new ArrayList<>();
        for (final ArrangeResult.Move m : r.moves()) {
            final Map<String, Object> mo = new LinkedHashMap<>();
            mo.put("id", m.id());
            mo.put("position", pointJson(m.position()));
            moves.add(mo);
        }
        out.put("moves", moves);
        final List<Object> islands = new ArrayList<>();
        for (final ArrangeResult.Island i : r.islands()) {
            final Map<String, Object> io = new LinkedHashMap<>();
            io.put("x", i.x());
            io.put("y", i.y());
            io.put("width", i.width());
            io.put("height", i.height());
            io.put("backdrop", i.backdrop());
            islands.add(io);
        }
        out.put("islands", islands);
        final List<Object> wireRoutes = new ArrayList<>();
        for (final ArrangeResult.WireRoute w : r.wireRoutes()) {
            final Map<String, Object> wo = new LinkedHashMap<>();
            wo.put("id", w.id());
            wo.put("waypoints", pointsJson(w.waypoints()));
            wireRoutes.add(wo);
        }
        out.put("wireRoutes", wireRoutes);
        return out;
    }

    // ---- the live-drag rule (the website's board, FactoryFlow.tsx)

    private static final Map<Integer, LiveDrag> DRAGS = new HashMap<>();

    /** A board's live-drag rule: its handle. */
    @JSExport
    public static int layoutDragOpen() {
        final int handle = nextHandle++;
        DRAGS.put(handle, new LiveDrag());
        return handle;
    }

    @JSExport
    public static void layoutDragClose(final int handle) {
        DRAGS.remove(handle);
    }

    private static LiveDrag drag(final int handle) {
        final LiveDrag d = DRAGS.get(handle);
        if (d == null) throw new IllegalArgumentException("no live drag " + handle);
        return d;
    }

    /** A card is picked up. */
    @JSExport
    public static void layoutDragStart(final int handle) {
        drag(handle).start();
    }

    /** Whether wires follow a drag on a board this size: LiveDrag.follows. */
    @JSExport
    public static boolean layoutDragFollows(final int handle, final int edgeCount, final boolean movesObstacles) {
        return drag(handle).follows(edgeCount, movesObstacles);
    }

    /** A drag frame that moved something: 0 route now, 1 wait until layoutDragDueAt, 2 hold the wires. */
    @JSExport
    public static int layoutDragFrame(final int handle, final int edgeCount, final boolean movesObstacles,
        final double nowMs) {
        return drag(handle).frame(edgeCount, movesObstacles, nowMs)
            .ordinal();
    }

    /** When a waiting beat may route, ms on the host's clock. */
    @JSExport
    public static double layoutDragDueAt(final int handle) {
        return drag(handle).dueAt();
    }

    /** A beat that held up the frame took this long. */
    @JSExport
    public static void layoutDragMeter(final int handle, final int edgeCount, final double tookMs) {
        drag(handle).meter(edgeCount, tookMs);
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
