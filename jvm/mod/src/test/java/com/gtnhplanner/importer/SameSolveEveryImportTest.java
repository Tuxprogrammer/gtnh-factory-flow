package com.gtnhplanner.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.balancer.BalanceMode;
import com.gtnhplanner.data.flowchart.balancer.Balancer;
import com.gtnhplanner.data.flowchart.balancer.GraphSolve;
import com.gtnhplanner.data.flowchart.balancer.SolutionView;
import com.gtnhplanner.data.flowchart.balancer.Spares;

/**
 * The same plan solves to the same answer every time it is imported. Ties between equal places for a leftover are
 * broken by the plan's own ids (their order, as the website gives them to the solver), never by the ids an import
 * makes up: spare-places.json has eight equal places for its plate and once got a different default each import.
 */
class SameSolveEveryImportTest {

    @Test
    void importingTheSamePlanTenTimesGivesTheSameSolve() {
        String first = null;
        for (int i = 0; i < 10; i++) {
            final FakeGame game = new FakeGame();
            game.routed = true;
            final Graph g = FfImport.importFromText(FakeGame.fixture("spare-places.json"), game, game)
                .graph();
            final Balancer.Answer answer = GraphSolve.solveWithAlternatives(BalanceMode.AUTO, g, null, Map.of());
            final SolutionView s = assertInstanceOf(Balancer.Answer.Solved.class, answer, () -> "failed: " + answer)
                .solution();
            final String read = read(g, s);
            if (first == null) first = read;
            assertEquals(first, read, "import " + i);
        }
        assertFalse(first.isEmpty(), "the plan has a leftover with places");
    }

    /** Every leftover's places in plan terms (card place and port), the default marked, with every card's rate. */
    private static String read(final Graph g, final SolutionView s) {
        final List<String> out = new ArrayList<>();
        for (final Spares.Leftover leftover : s.spares()) {
            final List<String> places = new ArrayList<>();
            for (final Spares.Bearer b : leftover.bearers()) {
                final Node n = g.nodes.get(
                    b.anchor()
                        .nodeId());
                places.add(
                    (b.solverPick() ? "*" : "") + n.x
                        + ","
                        + n.y
                        + (b.anchor()
                            .input() ? ":i" : ":o")
                        + b.anchor()
                            .portIndex());
            }
            places.sort(null);
            out.add(String.join(" ", places));
        }
        final List<String> rates = new ArrayList<>();
        for (final Node n : g.nodes.values())
            rates.add(n.x + "," + n.y + "=" + Math.round(s.machineCounts.getOrDefault(n.id, 0.0) * 1e6));
        rates.sort(null);
        out.add(String.join(" ", rates));
        return String.join("\n", out);
    }
}
