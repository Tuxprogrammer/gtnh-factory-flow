package com.gtnhplanner.core.layout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * The board's router between edits, the website's {@code incremental-router.ts}: it keeps the last board and its
 * routes, and when the board changes it routes again only what the change touched. A wire keeps its route while both
 * its cards stand where they stood, its request is unchanged, and the route runs nowhere a card (or frame) left, took
 * or now stands; everything else routes again around the kept wires, which are pinned into the solve exactly as they
 * were. When most of the board changed, or the dials did, it solves afresh. Rough solves (a card in hand) skip the
 * negotiation; the thorough solve that follows routes those wires again. Its state can be written down
 * ({@link #snapshot}) and read back ({@link #restore}).
 */
public final class IncrementalRouter {

    /** How far round a changed card a kept route must stay: its clearance cell plus one. */
    private static final double AREA_PAD = 2 * GridEdgeRouter.BOARD_GRID;

    /** A router's whole state, plain data: the dials' key, the obstacles, each wire's request key, routes, rough. */
    public record Snapshot(String tuningKey, List<GridObstacle> obstacles, List<String[]> requests,
        List<GridRoutedEdge> routes, List<String> rough) {}

    private final GridEdgeRouter router;
    private Map<String, GridObstacle> obstacles = new LinkedHashMap<>();
    /** Each wire's request key, by edge id: what it asked for last time. */
    private Map<String, String> requests = new LinkedHashMap<>();
    private Map<String, GridRoutedEdge> routes = new LinkedHashMap<>();
    /** Wires last routed by a rough solve: a thorough one routes them again. */
    private Set<String> rough = new LinkedHashSet<>();
    @Nullable
    private String tuningKey;

    public IncrementalRouter() {
        this(new GridEdgeRouter());
    }

    /** One around the given grid router (its pools, its clock). */
    public IncrementalRouter(final GridEdgeRouter router) {
        this.router = router;
    }

    /** The state as plain data, or null before the first solve. */
    @Nullable
    public Snapshot snapshot() {
        if (tuningKey == null) return null;
        final List<String[]> keyed = new ArrayList<>();
        for (final Map.Entry<String, String> e : requests.entrySet())
            keyed.add(new String[] { e.getKey(), e.getValue() });
        return new Snapshot(
            tuningKey,
            new ArrayList<>(obstacles.values()),
            keyed,
            new ArrayList<>(routes.values()),
            new ArrayList<>(rough));
    }

    /** Takes up a written-down state: the next solve diffs against it. */
    public void restore(final Snapshot snapshot) {
        tuningKey = snapshot.tuningKey();
        obstacles = new LinkedHashMap<>();
        for (final GridObstacle o : snapshot.obstacles()) obstacles.put(o.id(), o);
        requests = new LinkedHashMap<>();
        for (final String[] r : snapshot.requests()) requests.put(r[0], r[1]);
        routes = new LinkedHashMap<>();
        for (final GridRoutedEdge r : snapshot.routes()) routes.put(r.edgeId(), r);
        rough = new LinkedHashSet<>(snapshot.rough());
    }

    /** Whether it last routed round an obstacle of this id: how a caller tells boards apart. */
    public boolean knows(final String id) {
        return obstacles.containsKey(id);
    }

    /** Forgets everything: the next solve routes the whole board. */
    public void reset() {
        obstacles.clear();
        requests.clear();
        routes.clear();
        rough.clear();
        tuningKey = null;
    }

    /** Routes the board, again only what changed since the last call; each wire's route by edge id. */
    public Map<String, GridRoutedEdge> solve(final List<GridObstacle> nextObstaclesList,
        final List<GridRouteRequest> nextRequests, final RouterTuning tuning, final boolean thorough,
        @Nullable final IncrementalStats stats) {
        final String key = tuning.key();
        final RouterTuning solveTuning = thorough ? tuning : tuning.withNegotiationRounds(0);
        final Map<String, GridObstacle> nextObstacles = new LinkedHashMap<>();
        for (final GridObstacle o : nextObstaclesList) nextObstacles.put(o.id(), o);

        final Plan plan = key.equals(tuningKey) ? diff(nextObstacles, nextRequests, thorough) : null;
        final Map<String, GridRoutedEdge> solved;
        if (plan == null) {
            solved = router.solve(nextObstaclesList, nextRequests, stats, solveTuning);
            rough = new LinkedHashSet<>();
            if (!thorough) for (final GridRouteRequest r : nextRequests) rough.add(r.edgeId());
        } else {
            solved = router.solve(nextObstaclesList, plan.loose, stats, solveTuning, plan.pinned);
            final Set<String> nextRough = new LinkedHashSet<>();
            for (final PinnedRoute pin : plan.pinned) {
                if (rough.contains(
                    pin.request()
                        .edgeId()))
                    nextRough.add(
                        pin.request()
                            .edgeId());
            }
            if (!thorough) for (final GridRouteRequest r : plan.loose) nextRough.add(r.edgeId());
            rough = nextRough;
        }
        if (stats != null) {
            stats.fresh = plan == null;
            stats.routed = plan != null ? plan.loose.size() : nextRequests.size();
            stats.kept = plan != null ? plan.pinned.size() : 0;
        }
        tuningKey = key;
        obstacles = nextObstacles;
        requests = new LinkedHashMap<>();
        for (final GridRouteRequest r : nextRequests) requests.put(r.edgeId(), requestKey(r));
        routes = solved;
        return solved;
    }

    private record Plan(List<PinnedRoute> pinned, List<GridRouteRequest> loose) {}

    /** Which wires keep their routes and which route again, or null when the board should be solved afresh. */
    @Nullable
    private Plan diff(final Map<String, GridObstacle> next, final List<GridRouteRequest> nextRequests,
        final boolean thorough) {
        if (routes.isEmpty()) return null;
        final Set<String> changed = new LinkedHashSet<>();
        final List<double[]> areas = new ArrayList<>();
        for (final Map.Entry<String, GridObstacle> e : next.entrySet()) {
            final GridObstacle before = obstacles.get(e.getKey());
            final GridObstacle obstacle = e.getValue();
            if (before != null && sameRect(before, obstacle)) continue;
            changed.add(e.getKey());
            // What the obstacle left and took: a frame that grows a row touches that row only.
            if (before != null) for (final double[] area : rectDifference(rect(before), rect(obstacle)))
                areas.add(pad(area));
            else areas.add(pad(rect(obstacle)));
        }
        for (final Map.Entry<String, GridObstacle> e : obstacles.entrySet()) {
            if (next.containsKey(e.getKey())) continue;
            changed.add(e.getKey());
            areas.add(pad(rect(e.getValue())));
        }
        // Most of the board changed: a fresh solve is as quick, and cleaner.
        if (2 * changed.size() > Math.max(next.size(), obstacles.size())) return null;
        final List<PinnedRoute> pinned = new ArrayList<>();
        final List<GridRouteRequest> loose = new ArrayList<>();
        for (final GridRouteRequest request : nextRequests) {
            final GridRoutedEdge route = routes.get(request.edgeId());
            final boolean keep = requestKey(request).equals(requests.get(request.edgeId())) && route != null
                && !(thorough && rough.contains(request.edgeId()))
                && !changed.contains(request.sourceCardId() == null ? "" : request.sourceCardId())
                && !changed.contains(request.targetCardId() == null ? "" : request.targetCardId())
                && !runsThrough(route, areas);
            if (keep) pinned.add(new PinnedRoute(request, route));
            else loose.add(request);
        }
        // Pinned wires install in the order the last solve installed them, so lane-mates keep their places.
        final Map<String, Integer> rank = new HashMap<>();
        for (final String id : routes.keySet()) rank.put(id, rank.size());
        pinned.sort(
            (a, b) -> rank.get(
                a.request()
                    .edgeId())
                - rank.get(
                    b.request()
                        .edgeId()));
        return new Plan(pinned, loose);
    }

    private static double[] rect(final GridObstacle o) {
        return new double[] { o.left(), o.top(), o.right(), o.bottom() };
    }

    private static double[] pad(final double[] r) {
        return new double[] { r[0] - AREA_PAD, r[1] - AREA_PAD, r[2] + AREA_PAD, r[3] + AREA_PAD };
    }

    /**
     * The ground two rectangles ({left, top, right, bottom}) do not share: both whole when apart, otherwise the bands
     * of each the other does not cover.
     */
    public static List<double[]> rectDifference(final double[] a, final double[] b) {
        final double left = Math.max(a[0], b[0]);
        final double right = Math.min(a[2], b[2]);
        final double top = Math.max(a[1], b[1]);
        final double bottom = Math.min(a[3], b[3]);
        if (left >= right || top >= bottom) return List.of(a, b);
        final double[] shared = { left, top, right, bottom };
        final List<double[]> out = new ArrayList<>(bandsOutside(a, shared));
        out.addAll(bandsOutside(b, shared));
        return out;
    }

    /** The parts of {@code rect} outside {@code inner} (which lies inside it), as up to four bands. */
    private static List<double[]> bandsOutside(final double[] rect, final double[] inner) {
        final List<double[]> bands = new ArrayList<>(4);
        if (inner[1] > rect[1]) bands.add(new double[] { rect[0], rect[1], rect[2], inner[1] });
        if (inner[3] < rect[3]) bands.add(new double[] { rect[0], inner[3], rect[2], rect[3] });
        if (inner[0] > rect[0]) bands.add(new double[] { rect[0], inner[1], inner[0], inner[3] });
        if (inner[2] < rect[2]) bands.add(new double[] { inner[2], inner[1], rect[2], inner[3] });
        return bands;
    }

    private static boolean sameRect(final GridObstacle a, final GridObstacle b) {
        return a.left() == b.left() && a.top() == b.top() && a.right() == b.right() && a.bottom() == b.bottom();
    }

    /**
     * What a wire asks for, as one string: equal keys mean the same request. The docks are left out (the rims of the
     * two cards, which the diff checks itself), and so are the tiebreak and the width (a kept route takes its new
     * width when its lanes are packed).
     */
    static String requestKey(final GridRouteRequest request) {
        final StringBuilder stops = new StringBuilder();
        if (request.waypoints() != null) {
            for (int i = 0; i < request.waypoints()
                .size(); i++) {
                if (i > 0) stops.append(' ');
                final GridPoint p = request.waypoints()
                    .get(i);
                stops.append(JsMath.text(p.x()))
                    .append(',')
                    .append(JsMath.text(p.y()));
            }
        }
        return (request.sourceCardId() == null ? "" : request.sourceCardId()) + "|"
            + (request.targetCardId() == null ? "" : request.targetCardId())
            + "|"
            + (request.sourceSquare() ? 1 : 0)
            + "|"
            + (request.targetSquare() ? 1 : 0)
            + "|"
            + (request.exemptObstacleIds() == null ? "" : String.join(",", request.exemptObstacleIds()))
            + "|"
            + (request.homeObstacleIds() == null ? "" : String.join(",", request.homeObstacleIds()))
            + "|"
            + stops;
    }

    /** Whether any segment of the drawn route enters any of the areas. */
    private static boolean runsThrough(final GridRoutedEdge route, final List<double[]> areas) {
        if (areas.isEmpty()) return false;
        final List<GridPoint> points = route.points();
        for (int i = 1; i < points.size(); i += 1) {
            final GridPoint a = points.get(i - 1);
            final GridPoint b = points.get(i);
            for (final double[] area : areas) if (segmentMeetsRect(a.x(), a.y(), b.x(), b.y(), area)) return true;
        }
        return false;
    }

    /** Liang-Barsky: does the segment touch the open rectangle? */
    private static boolean segmentMeetsRect(final double x0, final double y0, final double x1, final double y1,
        final double[] rect) {
        final double dx = x1 - x0;
        final double dy = y1 - y0;
        final double[] span = { 0, 1 };
        return clip(span, -dx, x0 - rect[0]) && clip(span, dx, rect[2] - x0)
            && clip(span, -dy, y0 - rect[1])
            && clip(span, dy, rect[3] - y0)
            && span[0] < span[1];
    }

    private static boolean clip(final double[] span, final double p, final double q) {
        if (p == 0) return q > 0;
        final double t = q / p;
        if (p < 0) {
            if (t > span[1]) return false;
            if (t > span[0]) span[0] = t;
        } else {
            if (t < span[0]) return false;
            if (t < span[1]) span[1] = t;
        }
        return true;
    }
}
