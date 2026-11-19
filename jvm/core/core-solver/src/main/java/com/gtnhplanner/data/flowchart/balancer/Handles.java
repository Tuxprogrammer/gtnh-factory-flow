package com.gtnhplanner.data.flowchart.balancer;

import java.util.ArrayList;
import java.util.List;

import com.gtnhplanner.core.solver.lp.LinearProgram;

/**
 * A fresh linear program with its column and row numbers unwrapped. Programs are the ONE per-stage object in the
 * pipeline; the data that feeds them (machines/ports/gates) lives once on {@link ModelData} and only these numbers are
 * new for each solve.
 *
 * @param lp           the program
 * @param extentVars   per machine, its column
 * @param flowVars     per drawn edge, its column
 * @param extVars      per connected port, its column
 * @param conservation per connected port, its conservation row (empty in a model without them)
 * @param fed          extra crossings at a port ({@link Solver#feedPort}): the independent check counts each as that
 *                     port's external
 */
public record Handles(LinearProgram lp, int[] extentVars, int[] flowVars, int[] extVars, int[] conservation,
    List<Fed> fed) {

    /** A free crossing at connected port {@code port} (column {@code column}): supply at an input, a sink at an output. */
    public record Fed(int column, int port) {}

    public Handles(final LinearProgram lp, final int[] extentVars, final int[] flowVars, final int[] extVars,
        final int[] conservation) {
        this(lp, extentVars, flowVars, extVars, conservation, new ArrayList<>());
    }

    /** The column's cost in the objective (minimised). */
    public void cost(final int column, final double cost) {
        lp.cost(column, cost);
    }

    /** The column's greatest value. */
    public void upper(final int column, final double upper) {
        lp.columnUpper(column, upper);
    }

    /** The column's value at the program's solution (0 when it has none). */
    public double value(final int column) {
        return lp.value(column);
    }

    /** The columns' values at the program's solution. */
    public double[] values(final int[] columns) {
        final double[] out = new double[columns.length];
        for (int i = 0; i < columns.length; i++) out[i] = lp.value(columns[i]);
        return out;
    }
}
