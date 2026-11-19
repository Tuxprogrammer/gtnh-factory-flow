package com.gtnhplanner.data.flowchart;

import javax.annotation.Nonnull;

import com.gtnhplanner.core.base.Uuid;

public class Edge {

    @Nonnull
    public final Uuid id;
    /// Nodes
    @Nonnull
    public final Uuid sourceNodeId;
    @Nonnull
    public final Uuid targetNodeId;
    /// Recipe source/targets inside the nodes
    public int sourceOutputIndex;
    public int targetInputIndex;
    /** The dots steering its route ({@code Waypoints}): board points it passes through, in order; empty for none. */
    @Nonnull
    public java.util.List<int[]> waypoints = java.util.List.of();

    public Edge(final Uuid id, final Uuid sourceNodeId, final Uuid targetNodeId, final int sourceOutputIndex,
        final int targetInputIndex) {
        this.id = id;
        this.sourceNodeId = sourceNodeId;
        this.targetNodeId = targetNodeId;
        this.sourceOutputIndex = sourceOutputIndex;
        this.targetInputIndex = targetInputIndex;
    }
}
