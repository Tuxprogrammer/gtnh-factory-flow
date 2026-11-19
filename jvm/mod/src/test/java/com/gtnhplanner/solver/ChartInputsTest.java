package com.gtnhplanner.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.gtnhplanner.conformance.Conformance;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.balancer.GraphSolve;
import com.gtnhplanner.dev.SolveInputJson;
import com.gtnhplanner.harness.GtnhFlowLoader;

/**
 * The solver's own corpus (core-solver's {@code SolverCorpus}) reads each gtnh-flow chart from its stored input,
 * {@code conformance/solver/corpus/inputs/gtnh-flow-<chart>.json}, since the core has no chart loader. Here every chart
 * is loaded as the game would and snapshotted in its own mode with no stored choice, and must write exactly that input
 * (number for number; recipe properties are never written), so the corpus still solves the charts themselves.
 */
class ChartInputsTest {

    @BeforeAll
    static void profile() {
        GtnhFlowLoader.ensureDefaultMachineProfile();
    }

    @TestFactory
    Stream<DynamicTest> everyChartWritesItsStoredInput() {
        return Stream.of(GtnhFlowLoader.CORPUS)
            .map(name -> DynamicTest.dynamicTest(name, () -> check(name)));
    }

    private static void check(final String name) throws IOException {
        final Graph graph = GtnhFlowLoader.load(name)
            .graph();
        // Read back from its text, as the stored file is: a chance is a float, which Gson compares as its double.
        final JsonElement snapshot = new JsonParser().parse(
            SolveInputJson.write(GraphSolve.input(graph, graph.getBalanceMode(), null))
                .toString());
        final JsonElement stored;
        try (InputStream in = Conformance.open("solver/corpus/inputs/gtnh-flow-" + name + ".json")) {
            stored = new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        assertEquals(stored, snapshot, name + ": the chart no longer snapshots to its stored input");
    }
}
