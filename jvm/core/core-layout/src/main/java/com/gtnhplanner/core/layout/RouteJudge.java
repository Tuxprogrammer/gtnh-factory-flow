package com.gtnhplanner.core.layout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * The layout judge, the website's {@code route-judge.ts}: routes a board's real wires with its cards at a layout's
 * places and reports crossings, length and points. A full verdict solves every wire and is remembered by its places
 * (64 at most); a quick one, given the base layout it differs from, pins the base's routes for every wire that neither
 * touches a moved card nor runs where one now stands, and re-solves only the rest with a short negotiation.
 */
public final class RouteJudge {

    /** A verdict: crossings, length, the points (length, bends and crossings at the arrange's prices), the crossings. */
    public record Verdict(int crossings, double length, double points, List<WireMetrics.Crossing> events) {}

    private record Placed(List<GridObstacle> obstacles, List<GridRouteRequest> requests,
        Map<String, GridRoutedEdge> routes) {}

    private final List<GridObstacle> resting;
    private final List<GridRouteRequest> restingRequests;
    private final Map<String, String> riderOf = new HashMap<>();
    private final Map<String, GridObstacle> restingById = new HashMap<>();
    private final RouterTuning tuning, quickTuning;
    private final GridEdgeRouter router = new GridEdgeRouter();
    private final LinkedHashMap<String, Placed> fullByKey = new LinkedHashMap<>();

    /**
     * @param riders obstacles that ride on an arranged card, as {rider id, block id}: a kept group's cards move with
     *               its block
     */
    public RouteJudge(final List<GridObstacle> resting, final List<GridRouteRequest> restingRequests,
        final RouterTuning tuning, final List<String[]> riders) {
        this.resting = List.copyOf(resting);
        this.restingRequests = List.copyOf(restingRequests);
        for (final String[] r : riders) riderOf.put(r[0], r[1]);
        for (final GridObstacle o : resting) restingById.put(o.id(), o);
        this.tuning = tuning;
        this.quickTuning = tuning.quickJudge();
    }

    /** Where an obstacle's top-left stands in a layout: its own place, or its block's step from rest. */
    private GridPoint at(final Map<String, GridPoint> positions, final GridObstacle o) {
        final GridPoint own = positions.get(o.id());
        if (own != null) return own;
        final String blockId = riderOf.get(o.id());
        final GridPoint blockAt = blockId == null ? null : positions.get(blockId);
        final GridObstacle block = blockId == null ? null : restingById.get(blockId);
        if (blockAt != null && block != null)
            return new GridPoint(o.left() + blockAt.x() - block.left(), o.top() + blockAt.y() - block.top());
        return new GridPoint(o.left(), o.top());
    }

    private String keyOf(final Map<String, GridPoint> positions) {
        final StringBuilder s = new StringBuilder(resting.size() * 12);
        for (int i = 0; i < resting.size(); i++) {
            if (i > 0) s.append(';');
            final GridPoint p = at(positions, resting.get(i));
            s.append(JsMath.text(p.x()))
                .append(',')
                .append(JsMath.text(p.y()));
        }
        return s.toString();
    }

    private Placed place(final Map<String, GridPoint> positions) {
        final Map<String, double[]> delta = new HashMap<>();
        final List<GridObstacle> obstacles = new ArrayList<>(resting.size());
        for (final GridObstacle o : resting) {
            final GridPoint p = at(positions, o);
            delta.put(o.id(), new double[] { p.x() - o.left(), p.y() - o.top() });
            obstacles.add(
                new GridObstacle(o.id(), p.x(), p.y(), p.x() + (o.right() - o.left()), p.y() + (o.bottom() - o.top())));
        }
        final List<GridRouteRequest> requests = new ArrayList<>(restingRequests.size());
        for (final GridRouteRequest r : restingRequests) {
            requests.add(
                r.withEnds(
                    shift(r.sources(), delta.get(r.sourceCardId() == null ? "" : r.sourceCardId())),
                    shift(r.targets(), delta.get(r.targetCardId() == null ? "" : r.targetCardId())),
                    null));
        }
        return new Placed(obstacles, requests, Map.of());
    }

    private static List<GridEndpoint> shift(final List<GridEndpoint> ends, @Nullable final double[] d) {
        final double dx = d == null ? 0 : d[0], dy = d == null ? 0 : d[1];
        final List<GridEndpoint> out = new ArrayList<>(ends.size());
        for (final GridEndpoint e : ends) out.add(e.shifted(dx, dy));
        return out;
    }

    private Verdict verdict(final Iterable<GridRoutedEdge> routes) {
        final WireMetrics.Measure measure = WireMetrics.measureRoutes(routes);
        // Routed at the router's prices, scored at the arrange's crossing price.
        final double points = WireMetrics.points(measure, tuning)
            + (tuning.arrangeCrossing() - tuning.crossing()) * measure.crossings();
        return new Verdict(measure.crossings(), measure.length(), points, measure.events());
    }

    private Verdict full(final Map<String, GridPoint> positions) {
        final String key = keyOf(positions);
        final Placed cached = fullByKey.get(key);
        if (cached != null) return verdict(
            cached.routes()
                .values());
        final Placed placed = place(positions);
        final Map<String, GridRoutedEdge> routes = router.solve(placed.obstacles(), placed.requests(), null, tuning);
        fullByKey.put(key, new Placed(placed.obstacles(), placed.requests(), routes));
        if (fullByKey.size() > 64) {
            final Iterator<String> eldest = fullByKey.keySet()
                .iterator();
            eldest.next();
            eldest.remove();
        }
        return verdict(routes.values());
    }

    /** The layout's verdict; {@code quick} with a {@code base} it was judged at re-solves only what moved. */
    public Verdict judge(final Map<String, GridPoint> positions, final boolean quick,
        @Nullable final Map<String, GridPoint> base) {
        if (!quick || base == null) return full(positions);
        final Placed known = fullByKey.get(keyOf(base));
        if (known == null) return full(positions);
        // Which cards moved between the base and this layout?
        final Set<String> moved = new HashSet<>();
        for (final GridObstacle o : resting) {
            final GridPoint a = at(base, o);
            final GridPoint b = at(positions, o);
            if (a.x() != b.x() || a.y() != b.y()) moved.add(o.id());
        }
        if (moved.isEmpty()) return verdict(
            known.routes()
                .values());
        final Placed placed = place(positions);
        final List<double[]> movedRects = new ArrayList<>();
        for (final GridObstacle o : placed.obstacles()) {
            if (moved.contains(o.id())) movedRects.add(
                new double[] { o.left() - GridEdgeRouter.BOARD_GRID, o.top() - GridEdgeRouter.BOARD_GRID,
                    o.right() + GridEdgeRouter.BOARD_GRID, o.bottom() + GridEdgeRouter.BOARD_GRID });
        }
        final Map<String, GridRouteRequest> baseRequests = new HashMap<>();
        for (final GridRouteRequest r : known.requests()) baseRequests.put(r.edgeId(), r);
        final List<PinnedRoute> pinned = new ArrayList<>();
        final List<GridRouteRequest> loose = new ArrayList<>();
        for (final GridRouteRequest request : placed.requests()) {
            final GridRoutedEdge route = known.routes()
                .get(request.edgeId());
            final boolean touches = moved.contains(request.sourceCardId() == null ? "" : request.sourceCardId())
                || moved.contains(request.targetCardId() == null ? "" : request.targetCardId());
            if (route == null || touches || route.vertices() == null || runsThrough(route, movedRects))
                loose.add(request);
            else {
                final GridRouteRequest baseRequest = baseRequests.get(request.edgeId());
                pinned.add(new PinnedRoute(baseRequest != null ? baseRequest : request, route));
            }
        }
        // The loose wires negotiate briefly: the pinned ones cannot yield.
        final Map<String, GridRoutedEdge> routes = router
            .solve(placed.obstacles(), loose, null, quickTuning, pinned);
        return verdict(routes.values());
    }

    private static boolean runsThrough(final GridRoutedEdge route, final List<double[]> rects) {
        final List<GridPoint> points = route.points();
        for (int i = 1; i < points.size(); i += 1) {
            final GridPoint a = points.get(i - 1);
            final GridPoint b = points.get(i);
            final double left = Math.min(a.x(), b.x());
            final double right = Math.max(a.x(), b.x());
            final double top = Math.min(a.y(), b.y());
            final double bottom = Math.max(a.y(), b.y());
            for (final double[] rect : rects) {
                if (right > rect[0] && left < rect[2] && bottom > rect[1] && top < rect[3]) return true;
            }
        }
        return false;
    }
}
