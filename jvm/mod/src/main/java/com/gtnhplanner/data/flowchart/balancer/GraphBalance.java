package com.gtnhplanner.data.flowchart.balancer;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Plan;

/**
 * A plan's balance solved on the calling thread, cached per plan until its solve version moves, and the boundary view
 * built from it. The legacy canvas and the tests read it; the board solves through a {@link SolveService} instead.
 * Kept here, beside the solve, so the plan model holds no solver answer of its own: the cache is the solve's, keyed by
 * the plan, and a plan nobody holds any more drops its entry. Client thread.
 */
public final class GraphBalance {

    private GraphBalance() {}

    /** One plan's cache: the solve version it was built from, and the boundary built on first ask after it. */
    private static final class Cached {

        long solvedAt = -1;
        List<BalanceView.Boundary> boundary;
    }

    /** By plan, as the plan itself: a graph does not define equality, so this is by identity. */
    private static final Map<Graph, Cached> CACHE = new WeakHashMap<>();

    /**
     * The balance, solved on the calling thread when the solve version moved since the last ask. The plans' summary
     * holds the answer ({@link com.gtnhplanner.data.flowchart.Summary#recompute}).
     */
    public static BalanceResult of(final Graph graph) {
        final Cached c = CACHE.computeIfAbsent(graph, g -> new Cached());
        if (c.solvedAt != graph.solveVersion()) {
            Plan.getInstance()
                .getSummary()
                .recompute(graph);
            c.solvedAt = graph.solveVersion();
            c.boundary = null;
        }
        return Plan.getInstance()
            .getSummary()
            .balance();
    }

    /**
     * Everything crossing the chart's boundary. Held from solve to solve because the canvas asks once per frame and
     * the answer only moves when the chart does.
     */
    public static List<BalanceView.Boundary> boundary(final Graph graph) {
        of(graph); // drops a view built before the last edit
        final Cached c = CACHE.get(graph);
        if (c.boundary == null) c.boundary = BalanceView.boundary(graph, of(graph));
        return c.boundary;
    }
}
