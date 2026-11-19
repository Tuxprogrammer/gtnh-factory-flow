package com.gtnhplanner.core.layout;

/**
 * Whether and when wires follow a card while it is dragged: the website's live-drag rule (its board, FactoryFlow.tsx),
 * written once for both hosts. While a card is in hand each beat routes again, roughly, only the wires the move
 * touched ({@link IncrementalRouter} with {@code thorough} false) and holds the rest; beats come at most every
 * {@link #SOLVE_MS}, and a beat that falls inside that window waits for its end (the trailing beat, so the cell the
 * pointer stopped in is still routed). Wires hold until the drop instead when the drag moves nothing wires route
 * round, when the board has more than {@link #ROUTE_EDGE_LIMIT} wires, or when a beat that held up the frame took
 * longer than {@link #BUDGET_MS} on a board this size or smaller (well below it follows again: {@link #SLOW_MARGIN}).
 * The drop always routes thoroughly. Times are the host's clock in ms; one per board.
 */
public final class LiveDrag {

    /** Past this many wires, wires hold until the drop. */
    public static final int ROUTE_EDGE_LIMIT = 1000;
    /** At most one beat this often, ms. */
    public static final double SOLVE_MS = 50;
    /** A beat that held up the frame longer than this, ms, marks the board's size as too slow to follow. */
    public static final double BUDGET_MS = 36;
    /**
     * Following resumes only below this share of the smallest wire count that blew the budget, so a board hovering at
     * that size does not flap between following and holding.
     */
    public static final double SLOW_MARGIN = 0.8;

    /** What a drag frame does: route a beat now, wait for the throttle window to end, or hold the wires. */
    public enum Beat {
        ROUTE,
        WAIT,
        HOLD
    }

    private double lastBeatAt = Double.NEGATIVE_INFINITY;
    private double slowAtEdgeCount = Double.POSITIVE_INFINITY;

    /** A card is picked up: the drag's first frame that moves something routes at once; the throttle paces the rest. */
    public void start() {
        lastBeatAt = Double.NEGATIVE_INFINITY;
    }

    /** Whether wires follow a drag on a board of {@code edgeCount} wires; false: they hold until the drop. */
    public boolean follows(final int edgeCount, final boolean movesObstacles) {
        return movesObstacles && edgeCount <= ROUTE_EDGE_LIMIT && !(edgeCount >= slowAtEdgeCount * SLOW_MARGIN);
    }

    /**
     * A drag frame at {@code nowMs} that moved something: {@link Beat#ROUTE} (a beat starts now), {@link Beat#WAIT}
     * (inside the throttle window: ask again at {@link #dueAt()}), or {@link Beat#HOLD}.
     */
    public Beat frame(final int edgeCount, final boolean movesObstacles, final double nowMs) {
        if (!follows(edgeCount, movesObstacles)) return Beat.HOLD;
        if (nowMs - lastBeatAt < SOLVE_MS) return Beat.WAIT;
        lastBeatAt = nowMs;
        return Beat.ROUTE;
    }

    /** When a waiting beat may route, ms on the host's clock. */
    public double dueAt() {
        return lastBeatAt + SOLVE_MS;
    }

    /**
     * A beat that held up the frame took {@code tookMs}: over the budget, this board size and larger hold from now on.
     * A beat routed off the frame (the website's worker, the game's router thread) is not metered.
     */
    public void meter(final int edgeCount, final double tookMs) {
        if (tookMs > BUDGET_MS) slowAtEdgeCount = Math.min(slowAtEdgeCount, edgeCount);
    }
}
