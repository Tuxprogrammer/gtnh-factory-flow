package com.gtnhplanner.cloud.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gtnhplanner.conformance.Conformance;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/**
 * The solver's data in {@code conformance/} against {@code conformance/schema/solve-input.schema.json} and
 * {@code solve-digest.schema.json}, generated from the website's Zod schemas (test only validator: networknt). The
 * website's {@code solver-contract.test.ts} reads the same files through the Zod schemas, so a file one side accepts
 * and
 * the other refuses fails a test on one of them.
 */
class SolverSchemaTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static JsonSchema input;
    private static JsonSchema digest;

    private static JsonSchema load(final String name) throws IOException {
        try (InputStream in = Conformance.open("schema/" + name)) {
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(in);
        }
    }

    @BeforeAll
    static void schemas() throws IOException {
        input = load("solve-input.schema.json");
        digest = load("solve-digest.schema.json");
    }

    private static JsonNode read(final Path p) throws IOException {
        return JSON.readTree(Files.readAllBytes(p));
    }

    private static String describe(final Set<ValidationMessage> errors) {
        final List<String> out = new ArrayList<>();
        for (final ValidationMessage m : errors) {
            out.add(m.getMessage());
            if (out.size() == 3) break;
        }
        return out.toString();
    }

    private static void accepts(final JsonSchema schema, final JsonNode node, final String what) {
        final Set<ValidationMessage> errors = schema.validate(node);
        assertTrue(errors.isEmpty(), what + ": " + describe(errors));
    }

    @Test
    void theCorpusInputsAreSolveInputs() throws IOException {
        final List<Path> files = Conformance.list("solver/corpus/inputs");
        assertTrue(files.size() >= 31, "corpus inputs: " + files.size());
        for (final Path p : files) accepts(input, read(p), "inputs/" + p.getFileName());
    }

    @Test
    void theGoldensAreDigests() throws IOException {
        final List<Path> files = Conformance.list("solver/corpus/goldens");
        assertTrue(files.size() >= 33, "goldens: " + files.size());
        for (final Path p : files) accepts(digest, read(p), "goldens/" + p.getFileName());
    }

    @Test
    void theUpToPlansCarryAnInputAndADigest() throws IOException {
        final List<Path> files = Conformance.list("solver/up-to");
        assertTrue(files.size() >= 70, "up-to plans: " + files.size());
        for (final Path p : files) {
            final JsonNode plan = read(p);
            accepts(input, plan.get("input"), "up-to/" + p.getFileName() + " input");
            accepts(digest, plan.get("answer"), "up-to/" + p.getFileName() + " answer");
        }
    }

    @Test
    void theBoardScenariosCarryAnInput() throws IOException {
        final JsonNode all = read(Conformance.path("board/mod-answers.json"));
        int n = 0;
        for (final Iterator<String> it = all.fieldNames(); it.hasNext();) {
            final String name = it.next();
            accepts(
                input,
                all.get(name)
                    .get("input"),
                "board/mod-answers.json " + name);
            n++;
        }
        assertTrue(n > 0);
    }

    /**
     * The schemas refuse what they should: without this a schema that accepts everything would pass the tests above.
     */
    @Test
    void aBrokenInputOrDigestIsRefused() throws IOException {
        final ObjectNode in = (ObjectNode) read(Conformance.path("solver/corpus/inputs/platline.json"));
        final String first = in.fieldNames()
            .next();
        final ObjectNode lacking = in.deepCopy();
        lacking.remove(first);
        assertFalse(
            input.validate(lacking)
                .isEmpty(),
            "an input without " + first + " validates");
        assertFalse(
            input.validate(JSON.readTree("[]"))
                .isEmpty());

        final ObjectNode gold = (ObjectNode) read(Conformance.path("solver/corpus/goldens/platline.json"));
        final ObjectNode noSolution = gold.deepCopy();
        noSolution.remove("solution");
        assertFalse(
            digest.validate(noSolution)
                .isEmpty(),
            "a digest without a solution validates");
        final ObjectNode wrong = gold.deepCopy();
        ((ObjectNode) wrong.get("solution")).put("openGates", "many");
        assertFalse(
            digest.validate(wrong)
                .isEmpty(),
            "a digest with openGates as text validates");
        assertEquals(Set.of(), digest.validate(gold));
    }
}
