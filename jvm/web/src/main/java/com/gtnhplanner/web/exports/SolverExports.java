package com.gtnhplanner.web.exports;

import org.teavm.jso.JSExport;

import com.gtnhplanner.data.flowchart.balancer.Balancer;
import com.gtnhplanner.web.solver.SolverCodec;

/**
 * core-solver for JavaScript, as a JSON-in, JSON-out entry: one solve input in, the whole answer out, both as text. The
 * input is the file format of conformance/solver/corpus/inputs and the answer the digest of its goldens (the solver's
 * answers: counts, flows, gates, externals, notes, alternatives, drawers, spares and the Up to read-out). The solve
 * runs on the calling thread (the core's inline worker). A failed solve answers {failure, notes, drawers}; a malformed
 * input throws.
 */
public final class SolverExports {

    private SolverExports() {}

    /** Solves one plan and returns its answer's digest. */
    @JSExport
    public static String solverSolve(final String solveInputJson) {
        return SolverCodec.writeAnswer(Balancer.solveWithAlternatives(SolverCodec.readInput(solveInputJson)));
    }
}
