package com.gtnhplanner.core.solver.lp;

/**
 * One linear program, the only thing the solver kernel knows of its LP engine: columns with bounds and a cost, rows
 * with bounds and coefficients, minimised, then each column's value read back. Everything is a double. Columns and rows
 * are numbered 0, 1, 2 ... in the order they are added. Made by {@link LpEngine#newProgram}.
 *
 * <p>
 * Every call says exactly what the kernel asked for, in the order it asked, because an engine may round differently
 * depending on how a value got there: {@link #set} replaces a coefficient and {@link #add} adds to it; a bound or cost
 * that is not finite means none. One program belongs to one thread.
 */
public interface LinearProgram {

    /** A new column with no bounds and no cost; its number. {@code name} is for people (logs, dumps) only. */
    int addColumn(String name);

    /** The column's least value; not finite: none. */
    void columnLower(int column, double lower);

    /** The column's greatest value; not finite: none. */
    void columnUpper(int column, double upper);

    /** The column takes whole values only (the program becomes a MILP). */
    void integer(int column);

    /** The column's cost in the objective (minimised); 0 removes it. */
    void cost(int column, double cost);

    /** The column's cost now (0 when it has none). */
    double cost(int column);

    /** A new row with no terms and no bounds; its number. {@code name} is for people only. */
    int addRow(String name);

    /** The row's coefficient on the column, replacing what was there (0 removes it). */
    void set(int row, int column, double coefficient);

    /** Adds to the row's coefficient on the column. */
    void add(int row, int column, double coefficient);

    /** The row's least value; not finite: none. */
    void rowLower(int row, double lower);

    /** The row's greatest value; not finite: none. */
    void rowUpper(int row, double upper);

    /** The row held at exactly {@code level}. */
    default void rowLevel(final int row, final double level) {
        rowLower(row, level);
        rowUpper(row, level);
    }

    int columns();

    int rows();

    /** Minimises under the engine's own default set-up. */
    Result solve();

    /**
     * How many set-ups the engine offers for a model one set-up may get wrong (call infeasible, or answer with a point
     * that does not hold): the kernel tries them in turn, {@code 0} first, until one gives a point its own check
     * believes.
     */
    int setups();

    /** Minimises under set-up {@code setup} ({@code 0 <= setup < setups()}). */
    Result solve(int setup);

    /** The column's value at the last solve; 0 when it has none. */
    double value(int column);

    /**
     * What a solve said: whether it found a point that holds every row and bound, whether that point is proven least,
     * whether it stopped at the engine's safety limit (no answer then means "gave up", not "none"), and the engine's own
     * word for its state (for people: a note may quote it, nothing compares it).
     */
    record Result(boolean feasible, boolean optimal, boolean outOfTime, String word) {}
}
