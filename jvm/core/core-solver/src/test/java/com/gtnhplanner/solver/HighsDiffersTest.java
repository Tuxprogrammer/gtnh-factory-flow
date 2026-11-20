package com.gtnhplanner.solver;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.conformance.Conformance;
import com.gtnhplanner.data.flowchart.balancer.Balancer;

/**
 * The kernel gives the shared core's answer on the islands where the website's HiGHS answers otherwise
 * ({@code conformance/solver/highs-differs/cases.json.gz}, read the same way as {@link WebsiteCasesTest}): islands of
 * two generated stress boards, the record for retiring HiGHS. The website's {@code highs-differs.test.ts} holds the
 * compiled core to the same answers and checks HiGHS still differs on each.
 */
class HighsDiffersTest {

    @TestFactory
    Stream<DynamicTest> theKernelGivesTheCoresAnswer() throws IOException {
        final JsonArray cases;
        try (InputStream raw = Conformance.open("solver/highs-differs/cases.json.gz");
            Reader in = new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8)) {
            cases = new JsonParser().parse(in)
                .getAsJsonObject()
                .getAsJsonArray("cases");
        }
        assertFalse(cases.size() == 0, "no cases");
        final List<DynamicTest> tests = new ArrayList<>();
        for (final JsonElement e : cases) {
            final JsonObject c = e.getAsJsonObject();
            final String name = c.get("name")
                .getAsString();
            tests.add(DynamicTest.dynamicTest(name, () -> {
                final Balancer.Answer answer = Balancer
                    .solveWithAlternatives(SolverCorpus.read(c.getAsJsonObject("input")));
                final JsonElement expected = WebsiteCasesTest.engineWords(c.get("answer"));
                final JsonElement actual = WebsiteCasesTest
                    .engineWords(UpToParityTest.normalised(UpToParityTest.digest(answer)));
                final List<String> d = SolverCorpus.differences(expected, actual);
                assertTrue(d.isEmpty(), name + " answers otherwise than the core:\n  " + String.join("\n  ", d));
            }));
        }
        return tests.stream();
    }
}
