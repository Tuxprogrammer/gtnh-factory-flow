package com.gtnhplanner.solver;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.conformance.Conformance;
import com.gtnhplanner.data.flowchart.balancer.Balancer;
import com.gtnhplanner.dev.SolveInputJson;

/**
 * The kernel answers the website's own cases as the website does ({@code conformance/solver/website/cases.json.gz},
 * written by {@code tools/conformance/solver/generate.mjs}): every distinct solve input the website's tests and the
 * reference plans hand its plan solver, with the website's answer, compared whole by the golden rules
 * ({@link SolverCorpus#differences}: 1e-6 relative). The website's {@code website-cases.test.ts} holds its own engine to
 * the same file.
 *
 * <p>
 * {@code -PcasesReport=<file>} writes every case's differences to the file (one JSON line per case that differs) and
 * fails nothing (with the kernel's whole answer beside them): how the differences between the two engines were
 * measured.
 */
class WebsiteCasesTest {

    private static final String REPORT = System.getProperty("gtnhplanner.solver.casesReport", "");

    private static final String OIL_LINE = "the oil line of website 07b310f3 (loosened holds): see the state note";

    /**
     * Cases the two engines answer differently, with why. A listed case that agrees fails, so the list never outlives
     * its reason.
     */
    private static final Map<String, String> DIFFERS = Map.ofEntries(
        Map.entry(
            "src/lib/solver/plan/loosened-holds.test.ts > held levels read off a slightly inexact point never fail the"
                + " plan > Up to 0.31 solves at the 0.3 machines it needs (forward columns) #3",
            OIL_LINE),
        Map.entry(
            "src/lib/solver/plan/loosened-holds.test.ts > held levels read off a slightly inexact point never fail the"
                + " plan > Up to 0.5 solves at the 0.3 machines it needs (forward columns) #2",
            OIL_LINE),
        Map.entry(
            "src/lib/solver/plan/loosened-holds.test.ts > held levels read off a slightly inexact point never fail the"
                + " plan > Up to 1 solves at the 0.3 machines it needs (forward columns) #1",
            OIL_LINE),
        Map.entry(
            "src/lib/solver/plan/loosened-holds.test.ts > held levels read off a slightly inexact point never fail the"
                + " plan > Up to 2 solves at the 0.3 machines it needs (forward columns) #4",
            OIL_LINE));

    private static final List<String> reported = new ArrayList<>();

    static JsonArray cases() throws IOException {
        try (InputStream raw = Conformance.open("solver/website/cases.json.gz");
            Reader in = new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8)) {
            return new JsonParser().parse(in)
                .getAsJsonObject()
                .getAsJsonArray("cases");
        }
    }

    @TestFactory
    Stream<DynamicTest> theKernelAnswersTheWebsitesCasesAlike() throws IOException {
        final JsonArray cases = cases();
        assertFalse(cases.size() == 0, "no website cases");
        final List<DynamicTest> tests = new ArrayList<>();
        for (final JsonElement e : cases) {
            final JsonObject c = e.getAsJsonObject();
            final String name = c.get("name")
                .getAsString();
            tests.add(DynamicTest.dynamicTest(name, () -> check(name, c)));
        }
        return tests.stream();
    }

    private static void check(final String name, final JsonObject c) {
        final Balancer.Answer answer = Balancer.solveWithAlternatives(SolveInputJson.read(c.getAsJsonObject("input")));
        final JsonElement expected = engineWords(c.get("answer"));
        final JsonElement actual = engineWords(UpToParityTest.normalised(UpToParityTest.digest(answer)));
        final List<String> d = SolverCorpus.differences(expected, actual);
        if (!REPORT.isEmpty()) {
            if (!d.isEmpty()) {
                final JsonObject line = new JsonObject();
                line.addProperty("name", name);
                final JsonArray diffs = new JsonArray();
                for (final String s : d) diffs.add(new com.google.gson.JsonPrimitive(s));
                line.add("differences", diffs);
                line.add("actual", actual);
                synchronized (reported) {
                    reported.add(line.toString());
                }
            }
            return;
        }
        final String why = DIFFERS.get(name);
        if (why != null) {
            assertFalse(d.isEmpty(), name + " is listed as differing (" + why + ") but now agrees: take it off the list");
            return;
        }
        assertTrue(d.isEmpty(), name + " answers differently from the website:\n  " + String.join("\n  ", d));
    }

    /**
     * The digest with the engine's own words taken out: what a SOLVER_UNSATISFIABLE note says after its key is the LP
     * engine's status ("infeasible" from HiGHS, "INVALID" or "INFEASIBLE" from ojAlgo), which says nothing about the
     * plan. Every other part of a note is compared.
     */
    static JsonElement engineWords(final JsonElement digest) {
        if (digest.isJsonArray()) {
            for (final JsonElement e : digest.getAsJsonArray()) engineWords(e);
        } else if (digest.isJsonObject()) {
            final JsonObject o = digest.getAsJsonObject();
            if (o.has("message") && o.get("message")
                .isJsonPrimitive()
                && "SOLVER_UNSATISFIABLE".equals(
                    o.get("message")
                        .getAsString())) {
                final JsonArray args = new JsonArray();
                args.add(new com.google.gson.JsonPrimitive("(the engine's status)"));
                o.add("args", args);
            }
            for (final Map.Entry<String, JsonElement> e : o.entrySet()) engineWords(e.getValue());
        }
        return digest;
    }

    @AfterAll
    static void writeReport() throws IOException {
        if (REPORT.isEmpty()) return;
        final Path out = Path.of(REPORT);
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.write(out, reported, StandardCharsets.UTF_8);
        System.out.println("[website cases] " + reported.size() + " cases differ; report in " + out);
    }
}
