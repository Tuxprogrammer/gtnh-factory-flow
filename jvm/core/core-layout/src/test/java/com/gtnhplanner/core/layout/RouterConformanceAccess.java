package com.gtnhplanner.core.layout;

import java.util.List;

import com.google.gson.JsonElement;

/** The router conformance run's JSON readers, for the arrange's (another package). */
public final class RouterConformanceAccess {

    private RouterConformanceAccess() {}

    public static List<GridObstacle> obstacles(final JsonElement e) {
        return RouterConformanceTest.obstacles(e);
    }

    public static List<GridRouteRequest> requests(final JsonElement e) {
        return RouterConformanceTest.requests(e);
    }

    public static RouterTuning tuning(final JsonElement e) {
        return RouterConformanceTest.tuning(e);
    }
}
