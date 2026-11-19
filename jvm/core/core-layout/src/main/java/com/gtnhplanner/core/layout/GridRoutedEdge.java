package com.gtnhplanner.core.layout;

import java.util.List;

import javax.annotation.Nullable;

/**
 * A routed wire: its drawn polyline in board px, the stroke it was routed at (null when unknown), and, unless it is a
 * fallback elbow, its vertex chain in cells and the docks it took, enough to pin it into a later solve as it was.
 */
public record GridRoutedEdge(String edgeId, List<GridPoint> points, @Nullable Double width,
    @Nullable List<GridPoint> vertices, @Nullable GridEndpoint source, @Nullable GridEndpoint target) {}
