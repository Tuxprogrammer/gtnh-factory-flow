package com.gtnhplanner.web.exports;

import org.teavm.jso.JSExport;

import com.gtnhplanner.core.solver.SolverCodec;
import com.gtnhplanner.data.flowchart.balancer.Balancer;

/**
 * core-solver for JavaScript, as a JSON-in, JSON-out entry: one solve input in, the whole answer out, both as text. The
 * input is the file format of conformance/solver/corpus/inputs and the answer the digest of its goldens (the solver's
 * answers: counts, flows, gates, externals, notes, alternatives, drawers, spares and the Up to read-out), both through
 * core-solver's {@link SolverCodec}, the codec the JVM tests read and compare with. The solve runs on the calling thread
 * (the core's inline worker). A failed solve answers {failure, notes, drawers}; a malformed input throws.
 */
public final class SolverExports {

    private SolverExports() {}

    /**
     * The 64 islands solved last ({@link Balancer.Cache}), as the website's own engine keeps them (its
     * {@code IslandCache(64)}), held by this module's instance: one per thread that loads the solver module, which on
     * the website is the solve Worker, so it lives as long as the Worker.
     */
    private static final Balancer.Cache CACHE = new Balancer.Cache(64);

    /** Solves one plan and returns its answer's digest. Nothing is kept between calls. */
    @JSExport
    public static String solverSolve(final String solveInputJson) {
        return SolverCodec.writeAnswer(Balancer.solveWithAlternatives(SolverCodec.readInput(solveInputJson)));
    }

    /**
     * {@link #solverSolve} through the module's island cache: an edit re-solves only the islands it touched and undo
     * finds the answer it had. The cache never changes an answer, only what is solved again (core-solver's
     * {@code SolverIslandTest}). The website's solve Worker calls this one.
     */
    @JSExport
    public static String solverSolveCached(final String solveInputJson) {
        return SolverCodec.writeAnswer(
            Balancer.solveWithAlternatives(SolverCodec.readInput(solveInputJson), CACHE, () -> false));
    }
}
