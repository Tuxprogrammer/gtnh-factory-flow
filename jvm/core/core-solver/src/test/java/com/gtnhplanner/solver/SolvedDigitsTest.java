package com.gtnhplanner.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.base.Format;
import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.flowchart.balancer.BalanceResult;
import com.gtnhplanner.data.flowchart.balancer.Balancer;

/**
 * A solved count is handed out as the nearest double to the ratio it is (core-base SolvedNumber, read in SolutionView),
 * so a figure exactly on a display half rounds up as it should. The corpus's alumina line runs one 4320 EU/t IV machine
 * 32/45 of the time: 0.375 A IV, which the mod's Amps key prints 0.38. As the LP engine left the count
 * (0.7111111111111084) it printed 0.37, the way the titanium line's 0.625 A HV printed 0.62. Solved the way the mod's
 * board solves ({@link Balancer#solve}, its NodeBalance operations are the card's machines). The website's side of the
 * same case is solved-digits.test.ts.
 */
class SolvedDigitsTest {

    private static final Uuid MACHINE = Uuid.fromString("e51d7803-7212-425e-a6a8-bee5f94a0bc5");

    @Test
    void theAluminaLinesIvMachineDrawsPointThreeEightAmps() {
        final SolverCorpus.Case alumina = SolverCorpus.cases()
            .stream()
            .filter(
                c -> c.name()
                    .equals("alumina-line-shared-reactor"))
            .findFirst()
            .orElseThrow();
        final BalanceResult result = Balancer.solve(alumina.input());
        final double count = result.nodeBalances()
            .get(MACHINE)
            .operations();
        assertEquals(32.0 / 45, count);
        assertEquals("0.38", Format.powerValue(4320 * count / 8192));
        assertEquals("0.37", Format.powerValue(4320 * 0.7111111111111084 / 8192));
    }

    @Test
    void theTitaniumLinesHvReactorReadsPointSixThreeAmpsFromTheCleanedCount() {
        assertEquals("0.63", Format.powerValue(480 * (2.0 / 3) / 512));
        assertEquals("0.62", Format.powerValue(480 * 0.66666666666666496 / 512));
    }
}
