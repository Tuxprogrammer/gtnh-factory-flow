package com.gtnhplanner.core.layout;

import java.util.List;

import javax.annotation.Nullable;

/**
 * One wire to route ({@code GridRouteRequest}): its id, a tiebreak ({@code order}), the candidate docks at each end
 * (the router picks the pair that routes cheapest), the cards the ends belong to (with them docks are planned per
 * card), whether an end must meet its side square, the stroke it draws at in px, the player's steering dots in order
 * (null or empty: none), the frames it may pass through (those holding its ends) and the frames holding both its ends.
 */
public record GridRouteRequest(String edgeId, double order, List<GridEndpoint> sources, List<GridEndpoint> targets,
    @Nullable String sourceCardId, @Nullable String targetCardId, boolean sourceSquare, boolean targetSquare,
    double strokeWidth, @Nullable List<GridPoint> waypoints, @Nullable List<String> exemptObstacleIds,
    @Nullable List<String> homeObstacleIds) {

    /** A plain wire between two cards' rims. */
    public GridRouteRequest(final String edgeId, final double order, final List<GridEndpoint> sources,
        final List<GridEndpoint> targets, @Nullable final String sourceCardId, @Nullable final String targetCardId,
        final double strokeWidth) {
        this(edgeId, order, sources, targets, sourceCardId, targetCardId, false, false, strokeWidth, null, null, null);
    }

    GridRouteRequest withEnds(final List<GridEndpoint> nextSources, final List<GridEndpoint> nextTargets,
        @Nullable final List<GridPoint> nextWaypoints) {
        return new GridRouteRequest(
            edgeId,
            order,
            nextSources,
            nextTargets,
            sourceCardId,
            targetCardId,
            sourceSquare,
            targetSquare,
            strokeWidth,
            nextWaypoints,
            exemptObstacleIds,
            homeObstacleIds);
    }
}
