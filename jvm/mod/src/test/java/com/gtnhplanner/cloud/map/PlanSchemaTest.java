package com.gtnhplanner.cloud.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.gtnhplanner.conformance.Conformance;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.library.PlanExport;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/**
 * The plan JSON against {@code conformance/schema/plan.schema.json}, the schema generated from the website's Zod
 * schemas (the validator is networknt's json-schema-validator, test only). It fails when the mod writes a plan the
 * website would refuse, and when the schema file stops describing the plans in {@code conformance/plans/}.
 *
 * <p>
 * Three sets of plans. What the mod writes today, for every solver-corpus plan and every website plan on a board
 * (dressed with what a player adds): as a shared plan ({@link CloudPlans#write}) and as the website export
 * ({@link PlanExport#project}). The mod's committed plans in {@code conformance/plans/mod/}, which the website's tests
 * read through its own Zod schema, and which this test regenerates and compares (run {@code -PwritePlans} to rewrite
 * them). And the plans the website writes ({@code plans/website/}) with the hand-made ones ({@code plans/valid/},
 * {@code plans/invalid/}).
 */
class PlanSchemaTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting()
        .disableHtmlEscaping()
        .create();
    private static final boolean WRITE = System.getProperty("gtnhplanner.plans.write") != null;

    /**
     * The mod's plans kept in the tree. Corpus plans only: their ids come from the corpus files, where an imported
     * website plan gets new random ids each time it is read.
     */
    private static final String[] COMMITTED = { "corpus single-card-v2", "corpus glass", "corpus benzene",
        "corpus hv-diesel", "corpus mv" };

    private static JsonSchema plan;

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = Conformance.open("schema/plan.schema.json")) {
            plan = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(in);
        }
    }

    private static Set<ValidationMessage> check(final String text) throws IOException {
        return plan.validate(JSON.readTree(text));
    }

    private static String describe(final Set<ValidationMessage> errors) {
        final List<String> out = new ArrayList<>();
        for (final ValidationMessage m : errors) {
            out.add(m.getMessage());
            if (out.size() == 5) break;
        }
        return out + (errors.size() > 5 ? " ... " + errors.size() + " in all" : "");
    }

    /** Every corpus plan and every website plan as a board, by name, each with the game that knows its cards. */
    private static Map<String, Object[]> boards() {
        final Map<String, Object[]> out = new LinkedHashMap<>();
        for (final String name : Boards.CORPUS) {
            final Graph g = Boards.dress(Boards.corpus(name));
            out.put("corpus " + name, new Object[] { g, new TestGame().knowing(g) });
        }
        for (final String name : Boards.WEBSITE) {
            final TestGame game = new TestGame();
            final Graph g = Boards.dress(Boards.imported(name, game.fake));
            out.put("website " + name, new Object[] { g, game.knowing(g) });
        }
        return out;
    }

    private static JsonObject cloud(final String name, final Object[] board) {
        return withFixedId(
            CloudPlans.write((Graph) board[0], (TestGame) board[1], null, null)
                .plan(),
            name);
    }

    private static JsonObject export(final String name, final Object[] board) {
        return withFixedId(PlanExport.project((Graph) board[0], name, (TestGame) board[1]), name);
    }

    /** A new plan gets a random id; the committed ones carry one made from the name. */
    private static JsonObject withFixedId(final JsonObject plan, final String name) {
        plan.addProperty("id", "mod-" + name.replace(' ', '-'));
        return plan;
    }

    private static String file(final String prefix, final String name) {
        return "plans/mod/" + prefix + "-" + name.replace(' ', '-') + ".json";
    }

    @Test
    void everyPlanTheModWritesIsOneTheWebsiteReads() throws IOException {
        int n = 0;
        for (final Map.Entry<String, Object[]> b : boards().entrySet()) {
            final String cloud = cloud(b.getKey(), b.getValue()).toString();
            assertEquals(Set.of(), check(cloud), "shared plan of " + b.getKey() + ": " + describe(check(cloud)));
            final String export = export(b.getKey(), b.getValue()).toString();
            assertEquals(Set.of(), check(export), "export of " + b.getKey() + ": " + describe(check(export)));
            n += 2;
        }
        assertEquals(2 * (Boards.CORPUS.length + Boards.WEBSITE.length), n);
    }

    @Test
    void theCommittedModPlansAreWhatTheModWritesToday() throws IOException {
        final Map<String, Object[]> boards = boards();
        for (final String key : COMMITTED) {
            final Object[] board = boards.get(key);
            assertTrue(board != null, "no board " + key);
            final String name = key.substring(key.indexOf(' ') + 1);
            final Map<String, JsonObject> made = new LinkedHashMap<>();
            made.put(file("cloud", key), cloud(key, board));
            made.put(file("export", key), export(key, board));
            for (final Map.Entry<String, JsonObject> e : made.entrySet()) {
                final String text = PRETTY.toJson(e.getValue()) + "\n";
                if (WRITE) {
                    Files.write(Conformance.path(e.getKey()), text.getBytes(StandardCharsets.UTF_8));
                } else {
                    assertEquals(
                        Conformance.text(e.getKey()),
                        text,
                        e.getKey() + " is out of date ("
                            + name
                            + "): run -PwritePlans, then node tools/conformance/manifest.mjs --write");
                }
            }
        }
    }

    private static List<Path> plans(final String dir) throws IOException {
        final List<Path> out = Conformance.list(dir);
        assertFalse(out.isEmpty(), "no plans in " + dir);
        return out;
    }

    @Test
    void theCommittedValidPlansValidate() throws IOException {
        for (final String dir : new String[] { "plans/valid", "plans/website", "plans/mod" }) {
            for (final Path p : plans(dir)) {
                final String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                assertEquals(Set.of(), check(text), dir + "/" + p.getFileName() + ": " + describe(check(text)));
            }
        }
    }

    @Test
    void theCommittedInvalidPlansAreRefused() throws IOException {
        for (final Path p : plans("plans/invalid")) {
            final String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            assertFalse(check(text).isEmpty(), "plans/invalid/" + p.getFileName() + " validates");
        }
    }

    /** The plans of the mod's own test resources: the website's, as the website writes them. */
    @Test
    void theWebsitePlansTheModImportsValidate() throws IOException {
        final List<String> names = new ArrayList<>(List.of(Boards.WEBSITE));
        names.add("crusher-and-empty-crop-farm");
        for (final String name : names) {
            final String text = Boards.json("/factory-flow/" + name + ".json")
                .toString();
            assertEquals(Set.of(), check(text), name + ": " + describe(check(text)));
        }
    }
}
