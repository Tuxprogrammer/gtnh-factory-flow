package com.gtnhplanner.core.layout;

/** A route to hold still in a solve: its request and the route it had. */
public record PinnedRoute(GridRouteRequest request, GridRoutedEdge route) {}
