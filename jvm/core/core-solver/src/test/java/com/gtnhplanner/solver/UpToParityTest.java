package com.gtnhplanner.solver;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.conformance.Conformance;
import com.gtnhplanner.data.flowchart.balancer.Balancer;

/**
 * The Up to rule answers the same in the mod as on the website (docs/design/up-to.md): every plan the website's
 * {@code parity/up-to-export.test.ts} wrote under {@code conformance/solver/up-to/} (its hand-built Up to plans, and
 * every corpus plan with each exact count turned Up to, answered by the compiled core) is solved here and its whole
 * answer, the read-out, its rings and what a buffer banks included, compared with the website's by the golden rules
 * ({@link SolverCorpus#differences}: floating-point noise and nothing else). Re-export there after a change to the
 * core that is meant to move an answer.
 */
class UpToParityTest {

    private static final Path PLANS = Conformance.path("solver/up-to");

    @TestFactory
    Stream<DynamicTest> theWebsiteAndTheModAnswerAlike() throws IOException {
        final List<Path> files;
        try (Stream<Path> listed = Files.list(PLANS)) {
            files = listed.filter(
                p -> p.getFileName()
                    .toString()
                    .endsWith(".json"))
                .sorted()
                .toList();
        }
        assertFalse(files.isEmpty(), "no Up to plans under " + PLANS);
        return files.stream()
            .map(
                file -> DynamicTest.dynamicTest(
                    file.getFileName()
                        .toString(),
                    () -> check(file)));
    }

    private static void check(final Path file) throws IOException {
        final JsonObject plan = new JsonParser().parse(Files.readString(file, StandardCharsets.UTF_8))
            .getAsJsonObject();
        final Balancer.Answer answer = Balancer
            .solveWithAlternatives(SolverCorpus.read(plan.getAsJsonObject("input")));
        final JsonElement expected = plan.get("answer"), actual = normalised(digest(answer));
        final List<String> d = SolverCorpus.differences(expected, actual);
        assertTrue(
            d.isEmpty(),
            plan.get("name")
                .getAsString() + " answers differently from the website:\n  "
                + String.join("\n  ", d));
    }

    /**
     * The digest as the website writes it: the shortfalls' limiting nodes and drawers are sets there, sorted by their
     * text ({@code digest.ts}); here they are lists in the order the relaxation found them.
     */
    static JsonElement normalised(final JsonElement digest) {
        if (digest.isJsonArray()) {
            for (final JsonElement e : digest.getAsJsonArray()) normalised(e);
        } else if (digest.isJsonObject()) {
            final JsonObject o = digest.getAsJsonObject();
            for (final String key : List.of("limitingNodes", "limitingDrawers")) {
                if (!o.has(key) || !o.get(key)
                    .isJsonArray()) continue;
                final List<JsonElement> items = new java.util.ArrayList<>();
                for (final JsonElement e : o.getAsJsonArray(key)) items.add(e);
                items.sort(
                    (a, b) -> a.toString()
                        .compareTo(b.toString()));
                final com.google.gson.JsonArray sorted = new com.google.gson.JsonArray();
                for (final JsonElement e : items) sorted.add(e);
                o.add(key, sorted);
            }
            for (final java.util.Map.Entry<String, JsonElement> e : o.entrySet()) normalised(e.getValue());
        }
        return digest;
    }

    /** The golden digest of a solved answer; a failed one as its failure, as the website writes it. */
    static JsonElement digest(final Balancer.Answer answer) {
        if (answer instanceof final Balancer.Answer.Failed failed) {
            final JsonObject j = new JsonObject();
            j.addProperty(
                "failed",
                failed.failure()
                    .message()
                    .name());
            return j;
        }
        return SolverCorpus.digest(answer);
    }
}
