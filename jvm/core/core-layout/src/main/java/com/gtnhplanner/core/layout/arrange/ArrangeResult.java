package com.gtnhplanner.core.layout.arrange;

import java.util.List;

import com.gtnhplanner.core.layout.GridPoint;

/**
 * An arrange's answer ({@code ArrangeResult} of board-arrange.ts): every card's new top-left in input order, the
 * rectangle each island landed in (the shelf of strays last when there is one), and the steering stops of bridges
 * between islands.
 */
public record ArrangeResult(List<Move> moves, List<Island> islands, List<WireRoute> wireRoutes) {

    /** A card's new top-left. */
    public record Move(String id, GridPoint position) {}

    /** An island's rectangle in board px; {@code backdrop}: whether it wants a drawn background. */
    public record Island(double x, double y, double width, double height, boolean backdrop) {}

    /** A bridge wire's stops round a third island's ground. */
    public record WireRoute(String id, List<GridPoint> waypoints) {}
}
