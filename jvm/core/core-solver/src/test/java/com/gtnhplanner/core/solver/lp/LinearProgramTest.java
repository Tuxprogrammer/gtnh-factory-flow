package com.gtnhplanner.core.solver.lp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The LP interface's contract, held on the installed engine (ojAlgo). */
class LinearProgramTest {

    private static LinearProgram program() {
        return LpEngine.get()
            .newProgram(60_000);
    }

    /** min x + 2y with x + y >= 3, y >= 1, x <= 10: x 2, y 1, under the default and under every set-up. */
    @Test
    void solvesALeastPoint() {
        for (int setup = -1; setup < program().setups(); setup++) {
            final LinearProgram lp = program();
            final int x = lp.addColumn("x");
            final int y = lp.addColumn("y");
            lp.columnLower(x, 0);
            lp.columnUpper(x, 10);
            lp.columnLower(y, 1);
            lp.cost(x, 1);
            lp.cost(y, 2);
            final int row = lp.addRow("sum");
            lp.set(row, x, 1);
            lp.set(row, y, 1);
            lp.rowLower(row, 3);
            assertEquals(0, x);
            assertEquals(1, y);
            assertEquals(2, lp.columns());
            assertEquals(1, lp.rows());
            final LinearProgram.Result result = setup < 0 ? lp.solve() : lp.solve(setup);
            assertTrue(result.feasible(), result.word());
            assertTrue(result.optimal(), result.word());
            assertFalse(result.outOfTime());
            assertEquals(2, lp.value(x), 1e-9);
            assertEquals(1, lp.value(y), 1e-9);
        }
    }

    /** {@code set} replaces a coefficient, {@code add} adds to it; a cost reads back as set, 0 when none. */
    @Test
    void setReplacesAndAddAdds() {
        final LinearProgram lp = program();
        final int x = lp.addColumn("x");
        lp.columnLower(x, 0);
        lp.cost(x, 1);
        assertEquals(1, lp.cost(x));
        lp.cost(x, 0);
        assertEquals(0, lp.cost(x));
        lp.cost(x, 1);
        final int row = lp.addRow("r");
        lp.set(row, x, 5);
        lp.set(row, x, 1);
        lp.add(row, x, 1);
        // 2x >= 4: x 2.
        lp.rowLower(row, 4);
        assertTrue(lp.solve()
            .optimal());
        assertEquals(2, lp.value(x), 1e-9);
    }

    /** A bound that is not finite is no bound; a level holds a row exactly; no point says so. */
    @Test
    void boundsAndInfeasibility() {
        final LinearProgram lp = program();
        final int x = lp.addColumn("x");
        lp.columnLower(x, Double.NEGATIVE_INFINITY);
        lp.columnUpper(x, Double.POSITIVE_INFINITY);
        final int row = lp.addRow("r");
        lp.set(row, x, 1);
        lp.rowLevel(row, -4);
        assertTrue(lp.solve()
            .feasible());
        assertEquals(-4, lp.value(x), 1e-9);

        final LinearProgram none = program();
        final int y = none.addColumn("y");
        none.columnLower(y, 0);
        none.columnUpper(y, 1);
        final int r = none.addRow("r");
        none.set(r, y, 1);
        none.rowLower(r, 2);
        final LinearProgram.Result result = none.solve();
        assertFalse(result.feasible(), result.word());
    }

    /** An integer column takes whole values: max x with 2x <= 7 is 3. */
    @Test
    void integerColumns() {
        final LinearProgram lp = program();
        final int x = lp.addColumn("x");
        lp.integer(x);
        lp.columnLower(x, 0);
        lp.cost(x, -1);
        final int row = lp.addRow("r");
        lp.set(row, x, 2);
        lp.rowUpper(row, 7);
        assertTrue(lp.solve()
            .feasible());
        assertEquals(3, lp.value(x), 1e-9);
    }
}
