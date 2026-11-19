package com.gtnhplanner.core.layout;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a solve did, for tests and benches: crossings on the finished board, wires ripped up and routed again, wires
 * that got the boxed-in elbow, the search work (A* pops, steps priced, window searches), the time the first pass and
 * the negotiation took (ms, from the router's clock; 0 without one), and per wire its crossings and dock plan. Work is
 * counted in doubles, as the website's numbers are.
 */
public class GridSolveStats {

    public int crossings, rerouted, fallbacks;
    public double pops, priced, searches, firstPassMs, negotiationMs;
    public final Map<String, PerEdge> perEdge = new LinkedHashMap<>();

    public record PerEdge(int crossings, boolean overflowed, boolean fallback) {}
}
