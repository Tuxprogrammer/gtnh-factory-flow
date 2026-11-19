package com.gtnhplanner.core.layout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One board held by a host that cannot hand the router its whole board cheaply on every call (the website's route
 * worker through the compiled core): the host tells it what changed since the last call (obstacles and requests put or
 * removed, the order when it moved) and gets back only the routes that differ from the ones it was last given. Routing
 * is {@link IncrementalRouter}'s on the board as it stands, so the answer is the one the whole board would give.
 *
 * <p>
 * Order is part of the board: obstacles and requests stand in the order they were first put, a put of a known id keeps
 * its place, and a host whose order moved says so ({@link #orderObstacles}, {@link #orderRequests}). One per board and
 * thread.
 */
public final class RouterBoard {

    /** What a call changed: routes that differ from the last ones handed out, in install order, and wires gone. */
    public record Changes(List<GridRoutedEdge> routes, List<String> gone, IncrementalStats stats) {}

    private final IncrementalRouter router;
    private Map<String, GridObstacle> obstacles = new LinkedHashMap<>();
    private Map<String, GridRouteRequest> requests = new LinkedHashMap<>();
    /** The points last handed out, by edge id. */
    private final Map<String, List<GridPoint>> given = new LinkedHashMap<>();

    public RouterBoard() {
        this(new IncrementalRouter());
    }

    public RouterBoard(final IncrementalRouter router) {
        this.router = router;
    }

    /** The router, for its snapshot and restore. */
    public IncrementalRouter router() {
        return router;
    }

    public void putObstacle(final GridObstacle obstacle) {
        obstacles.put(obstacle.id(), obstacle);
    }

    public void removeObstacle(final String id) {
        obstacles.remove(id);
    }

    public void putRequest(final GridRouteRequest request) {
        requests.put(request.edgeId(), request);
    }

    public void removeRequest(final String edgeId) {
        requests.remove(edgeId);
    }

    /** Puts the obstacles in this order; every id must be one the board holds, and all of them. */
    public void orderObstacles(final List<String> ids) {
        obstacles = reorder(obstacles, ids, "obstacle");
    }

    /** Puts the requests in this order; every id must be one the board holds, and all of them. */
    public void orderRequests(final List<String> ids) {
        requests = reorder(requests, ids, "request");
    }

    private static <T> Map<String, T> reorder(final Map<String, T> from, final List<String> ids, final String what) {
        if (ids.size() != from.size()) throw new IllegalArgumentException(what + " order names " + ids.size()
            + " ids for " + from.size());
        final Map<String, T> out = new LinkedHashMap<>();
        for (final String id : ids) {
            final T v = from.get(id);
            if (v == null) throw new IllegalArgumentException("unknown " + what + " " + id);
            out.put(id, v);
        }
        return out;
    }

    public int obstacleCount() {
        return obstacles.size();
    }

    public int requestCount() {
        return requests.size();
    }

    /** Forgets what was handed out: the next call answers with every route. */
    public void forgetGiven() {
        given.clear();
    }

    /** Forgets the board and the routes: the next call is a fresh solve of what the host puts again. */
    public void clear() {
        obstacles.clear();
        requests.clear();
        given.clear();
        router.reset();
    }

    /**
     * Takes up a written-down router state (a plan opened again): the board and what was handed out are forgotten, so
     * the host puts the whole board again and the next call diffs it against the restored routes.
     */
    public void restore(final IncrementalRouter.Snapshot snapshot) {
        obstacles.clear();
        requests.clear();
        given.clear();
        router.restore(snapshot);
    }

    /** Routes the board as it stands; returns the routes that changed since the last call and the wires gone. */
    public Changes route(final RouterTuning tuning, final boolean thorough) {
        final IncrementalStats stats = new IncrementalStats();
        final Map<String, GridRoutedEdge> solved = router
            .solve(new ArrayList<>(obstacles.values()), new ArrayList<>(requests.values()), tuning, thorough, stats);
        final List<GridRoutedEdge> changed = new ArrayList<>();
        for (final GridRoutedEdge r : solved.values()) {
            final List<GridPoint> before = given.get(r.edgeId());
            if (before != null && before.equals(r.points())) continue;
            given.put(r.edgeId(), r.points());
            changed.add(r);
        }
        final List<String> gone = new ArrayList<>();
        if (given.size() > solved.size()) {
            for (final String id : new ArrayList<>(given.keySet())) {
                if (solved.containsKey(id)) continue;
                given.remove(id);
                gone.add(id);
            }
        }
        return new Changes(changed, gone, stats);
    }
}
