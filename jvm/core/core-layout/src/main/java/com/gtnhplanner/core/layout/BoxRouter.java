package com.gtnhplanner.core.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * A board of boxes (cards, drawers, collapsed groups) routed by the one router, as the game's board and its Arrange
 * judge ask: every box is an obstacle and a rim of docks ({@link GridEdgeRouter#cardRimDocks}), a wire may meet its box
 * on the sides it is given, and a box marked square (a drawer, a group's summary) is met square to its side, as on
 * the website. Keeps the board between calls ({@link IncrementalRouter}), so a move routes again only what it touched.
 * One per board and thread.
 */
public final class BoxRouter {

    /** Sides, as bits: a wire may meet its box on any of those it is given. */
    public static final int RIGHT = 1, BOTTOM = 2, LEFT = 4, TOP = 8, ANY = 15;

    /** A box in board px, by a key that stays the same for the same box from one call to the next. */
    public record Box(String key, double x, double y, double w, double h, boolean square) {

        GridObstacle obstacle() {
            return new GridObstacle(key, x, y, x + w, y + h);
        }
    }

    /**
     * A wire between two boxes (indexes into the box list), on the sides allowed at each end, drawn {@code width} px
     * wide (the thickest route first), through the player's dots ({x, y} board px, in order; empty for none).
     */
    public record Wire(String key, int from, int to, int fromSides, int toSides, double width, List<int[]> stops) {}

    private final IncrementalRouter router;
    private RouterTuning tuning = RouterTuning.DEFAULT;

    public BoxRouter() {
        this(new IncrementalRouter());
    }

    public BoxRouter(final IncrementalRouter router) {
        this.router = router;
    }

    /** The dials it routes with (the shipped ones unless set). */
    public void tuning(final RouterTuning next) {
        tuning = next;
    }

    /**
     * Routes the board, again only what changed since the last call ({@code thorough} false: the rough solve while a
     * box is in hand, no negotiation). Each wire's route by its key: the drawn polyline in board px, and null
     * vertices for a wire that got the boxed-in elbow.
     */
    public Map<String, GridRoutedEdge> route(final List<Box> boxes, final List<Wire> wires, final boolean thorough,
        @Nullable final IncrementalStats stats) {
        final List<GridObstacle> obstacles = new ArrayList<>(boxes.size());
        for (final Box b : boxes) obstacles.add(b.obstacle());
        return router.solve(obstacles, requests(boxes, wires), tuning, thorough, stats);
    }

    /** The router's requests for these wires, in their order. */
    public static List<GridRouteRequest> requests(final List<Box> boxes, final List<Wire> wires) {
        final List<List<GridEndpoint>> rims = new ArrayList<>(boxes.size());
        for (final Box b : boxes) rims.add(GridEdgeRouter.cardRimDocks(b.obstacle()));
        final List<GridRouteRequest> out = new ArrayList<>(wires.size());
        for (int i = 0; i < wires.size(); i++) {
            final Wire w = wires.get(i);
            final Box from = boxes.get(w.from()), to = boxes.get(w.to());
            List<GridPoint> stops = null;
            if (!w.stops()
                .isEmpty()) {
                stops = new ArrayList<>(
                    w.stops()
                        .size());
                for (final int[] s : w.stops()) stops.add(new GridPoint(s[0], s[1]));
            }
            out.add(
                new GridRouteRequest(
                    w.key(),
                    i,
                    sides(rims.get(w.from()), w.fromSides()),
                    sides(rims.get(w.to()), w.toSides()),
                    from.key(),
                    to.key(),
                    from.square(),
                    to.square(),
                    Math.min(w.width(), GridEdgeRouter.LANE_CAPACITY),
                    stops,
                    null,
                    null));
        }
        return out;
    }

    /** The docks on the allowed sides; all of them when none is allowed. */
    private static List<GridEndpoint> sides(final List<GridEndpoint> rim, final int allowed) {
        if ((allowed & ANY) == ANY || (allowed & ANY) == 0) return rim;
        final List<GridEndpoint> out = new ArrayList<>();
        for (final GridEndpoint e : rim) if ((allowed & bit(e.side())) != 0) out.add(e);
        return out;
    }

    private static int bit(final GridSide side) {
        return switch (side) {
            case RIGHT -> RIGHT;
            case BOTTOM -> BOTTOM;
            case LEFT -> LEFT;
            case TOP -> TOP;
        };
    }

    /** A route's polyline on whole board px, as the game draws it. */
    public static List<int[]> pixels(final GridRoutedEdge route) {
        final List<int[]> out = new ArrayList<>(
            route.points()
                .size());
        for (final GridPoint p : route.points()) {
            final int[] xy = { (int) JsMath.round(p.x()), (int) JsMath.round(p.y()) };
            if (!out.isEmpty()) {
                final int[] last = out.get(out.size() - 1);
                if (last[0] == xy[0] && last[1] == xy[1]) continue;
            }
            out.add(xy);
        }
        return out;
    }
}
