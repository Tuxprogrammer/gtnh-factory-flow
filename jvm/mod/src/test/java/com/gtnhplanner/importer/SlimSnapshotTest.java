package com.gtnhplanner.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhplanner.client.dave.SlimSnapshot;
import com.gtnhplanner.conformance.Conformance;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;

/**
 * The Ask Dave attachment: the demo plans' slim snapshots, kept in conformance/slim/mod, which the website's
 * {@code plans/import} validation reads ({@code src/lib/agent-api/slim-conformance.test.ts}), so the two sides cannot
 * drift. Rewrite them with -PwriteSlim.
 */
class SlimSnapshotTest {

    static List<String> plans() {
        return List.of("biodiesel-demo", "early-baux-ti-w-boards", "alumina-line-shared-reactor");
    }

    private static Graph open(final String name, final FakeGame game) {
        final Graph g = FfImport.importFromText(FakeGame.fixture(name + ".json"), game, game)
            .graph();
        // The plan's id on this computer, fixed so the file is the same every run.
        g.setLocalId("demo-" + name);
        return g;
    }

    private static String snapshot(final String name) {
        final FakeGame game = new FakeGame();
        final Graph g = open(name, game);
        return new GsonBuilder().setPrettyPrinting()
            .create()
            .toJson(canonical(SlimSnapshot.of(g, LibraryRoundTripTest.world(game, g)))) + "\n";
    }

    /**
     * The snapshot with the ids an import makes afresh each time (cards, drawers, wires, a shared machine's later
     * recipes) named by what does not change: cards and drawers in board order (board, y, x) as {@code card-<n>} and
     * {@code drawer-<n>}, wires by their ends. Lists keep that order, so the file is the same every run; the shape and
     * every rule the site checks are the mod's as it made them.
     */
    static JsonObject canonical(final JsonObject s) {
        final java.util.Map<String, String> names = new java.util.HashMap<>();
        final java.util.function.ToDoubleFunction<JsonElement> x = e -> e.getAsJsonObject()
            .getAsJsonObject("position")
            .get("x")
            .getAsDouble();
        final java.util.function.ToDoubleFunction<JsonElement> y = e -> e.getAsJsonObject()
            .getAsJsonObject("position")
            .get("y")
            .getAsDouble();
        final java.util.function.Function<JsonElement, String> board = e -> e.getAsJsonObject()
            .has("board")
                ? e.getAsJsonObject()
                    .get("board")
                    .getAsString()
                : "";
        final java.util.Comparator<JsonElement> place = java.util.Comparator.comparing(board)
            .thenComparingDouble(y)
            .thenComparingDouble(x);
        if (s.has("boards")) s.add(
            "boards",
            sorted(
                s.getAsJsonArray("boards"),
                java.util.Comparator.comparing(
                    e -> e.getAsJsonObject()
                        .get("id")
                        .getAsString())));
        final JsonArray cards = sorted(s.getAsJsonArray("cards"), place);
        for (int i = 0; i < cards.size(); i++) {
            final JsonObject c = cards.get(i)
                .getAsJsonObject();
            final String name = "card-" + (i + 1);
            names.put(
                c.get("id")
                    .getAsString(),
                name);
            c.addProperty("id", name);
            c.addProperty("recipeId", "gtnhplanner:" + name);
            if (c.has("sections")) {
                final JsonArray sections = new JsonArray();
                for (int k = 1; k <= c.getAsJsonArray("sections")
                    .size(); k++) sections.add(new com.google.gson.JsonPrimitive("gtnhplanner:" + name + "-r" + k));
                c.add("sections", sections);
            }
        }
        s.add("cards", cards);
        final JsonArray drawers = sorted(s.getAsJsonArray("drawers"), place);
        for (int i = 0; i < drawers.size(); i++) {
            final JsonObject d = drawers.get(i)
                .getAsJsonObject();
            names.put(
                d.get("id")
                    .getAsString(),
                "drawer-" + (i + 1));
            d.addProperty("id", "drawer-" + (i + 1));
        }
        s.add("drawers", drawers);
        for (final JsonElement e : s.getAsJsonArray("wires")) {
            final JsonObject w = e.getAsJsonObject();
            w.addProperty(
                "from",
                names.get(
                    w.get("from")
                        .getAsString()));
            w.addProperty(
                "to",
                names.get(
                    w.get("to")
                        .getAsString()));
        }
        // Sorted by what they join, the import's random id left out.
        for (final JsonElement e : s.getAsJsonArray("wires")) e.getAsJsonObject()
            .remove("id");
        final JsonArray wires = new JsonArray();
        final JsonArray byEnds = sorted(
            s.getAsJsonArray("wires"),
            java.util.Comparator.comparing(JsonElement::toString));
        for (int i = 0; i < byEnds.size(); i++) {
            final JsonObject w = new JsonObject();
            w.addProperty("id", "wire-" + (i + 1));
            for (final java.util.Map.Entry<String, JsonElement> e : byEnds.get(i)
                .getAsJsonObject()
                .entrySet()) w.add(e.getKey(), e.getValue());
            wires.add(w);
        }
        s.add("wires", wires);
        for (final JsonElement e : s.getAsJsonArray("targets")) e.getAsJsonObject()
            .addProperty(
                "card",
                names.get(
                    e.getAsJsonObject()
                        .get("card")
                        .getAsString()));
        s.add("targets", sorted(s.getAsJsonArray("targets"), java.util.Comparator.comparing(JsonElement::toString)));
        return s;
    }

    private static JsonArray sorted(final JsonArray a, final java.util.Comparator<JsonElement> order) {
        final java.util.List<JsonElement> list = new java.util.ArrayList<>();
        for (final JsonElement e : a) list.add(e);
        list.sort(order);
        final JsonArray out = new JsonArray();
        for (final JsonElement e : list) out.add(e);
        return out;
    }

    @ParameterizedTest
    @MethodSource("plans")
    void theDemoPlansSnapshotsAreTheConformanceFiles(final String name) throws Exception {
        final String path = "slim/mod/" + name + ".json";
        if (System.getProperty("gtnhplanner.slim.write") != null) {
            Files.createDirectories(Conformance.path("slim/mod"));
            Files.write(Conformance.path(path), snapshot(name).getBytes(StandardCharsets.UTF_8));
            return;
        }
        assertEquals(Conformance.text(path), snapshot(name));
    }

    @ParameterizedTest
    @MethodSource("plans")
    void cardsAndDrawersAreNamedByTheirBoardIdsAndThePlanByItsId(final String name) {
        final FakeGame game = new FakeGame();
        final Graph g = open(name, game);
        final JsonObject s = SlimSnapshot.of(g, LibraryRoundTripTest.world(game, g));
        assertEquals(
            g.planId(),
            s.getAsJsonObject("plan")
                .get("id")
                .getAsString());
        final Set<String> nodes = new HashSet<>(), drawers = new HashSet<>();
        for (final Node n : g.getNodes()) nodes.add(n.id.toString());
        for (final Drawer d : g.getDrawers()) drawers.add(
            d.getId()
                .toString());
        final JsonArray cards = s.getAsJsonArray("cards");
        assertTrue(cards.size() > 0, name);
        for (final JsonElement c : cards) assertTrue(
            nodes.contains(
                c.getAsJsonObject()
                    .get("id")
                    .getAsString()));
        for (final JsonElement d : s.getAsJsonArray("drawers")) assertTrue(
            drawers.contains(
                d.getAsJsonObject()
                    .get("id")
                    .getAsString()));
        assertTrue(
            s.getAsJsonArray("wires")
                .size() > 0,
            name);
    }

    @ParameterizedTest
    @MethodSource("plans")
    void gameCardsCarryWhatTheirRecipeDoes(final String name) {
        final FakeGame game = new FakeGame();
        final Graph g = open(name, game);
        final JsonObject s = SlimSnapshot.of(g, LibraryRoundTripTest.world(game, g));
        for (final JsonElement e : s.getAsJsonArray("cards")) {
            final JsonObject c = e.getAsJsonObject();
            assertTrue(
                c.get("recipeId")
                    .getAsString()
                    .startsWith("gtnhplanner:"),
                name);
            assertNotNull(c.get("recipe"), name + " " + c);
            final JsonObject r = c.getAsJsonObject("recipe");
            assertTrue(
                r.getAsJsonArray("outputs")
                    .size() > 0);
            assertNotNull(r.get("durationTicks"));
            assertNotNull(r.get("eut"));
            if (c.has("sections")) assertEquals(
                c.getAsJsonArray("sections")
                    .size(),
                c.getAsJsonArray("sectionRecipes")
                    .size(),
                name);
        }
    }

    @Test
    void aRecipesContentIsWhatTheSiteMatchesOnAndNoMore() {
        final JsonObject body = new com.google.gson.JsonParser().parse(
            "{\"id\":\"gtnhplanner:x\",\"name\":\"Steel\",\"kind\":\"gregtech_machine\",\"machineType\":\"Electric Blast Furnace\","
                + "\"minimumTier\":\"MV\",\"durationTicks\":400,\"eut\":120,\"specialValue\":1000,"
                + "\"inputs\":[{\"kind\":\"item\",\"id\":\"iron\",\"amount\":1,\"displayName\":\"Iron\",\"alternatives\":[]},"
                + "{\"kind\":\"item\",\"id\":\"spawner\",\"amount\":1,\"consumed\":false}],"
                + "\"outputs\":[{\"kind\":\"item\",\"id\":\"steel\",\"amount\":1,\"chance\":1}],"
                + "\"source\":{\"recipeMap\":\"Blast Furnace\",\"rawRecipeId\":\"raw-1\",\"exporter\":\"unknown\"},\"metadata\":{\"a\":1}}")
            .getAsJsonObject();
        assertEquals(
            "{\"name\":\"Steel\",\"machineType\":\"Electric Blast Furnace\",\"recipeMap\":\"Blast Furnace\",\"rawRecipeId\":\"raw-1\","
                + "\"inputs\":[{\"id\":\"iron\",\"kind\":\"item\",\"amount\":1},{\"id\":\"spawner\",\"kind\":\"item\",\"amount\":1,\"consumed\":false}],"
                + "\"outputs\":[{\"id\":\"steel\",\"kind\":\"item\",\"amount\":1}],\"durationTicks\":400,\"eut\":120}",
            SlimSnapshot.content(body)
                .toString());
        // The export's own recipes (power, tank, custom rate) and one that makes nothing stand for no dataset recipe.
        body.addProperty("kind", "custom");
        assertNull(SlimSnapshot.content(body));
        body.addProperty("kind", "crop_produce");
        body.add("outputs", new JsonArray());
        assertNull(SlimSnapshot.content(body));
    }

    @Test
    void aSnapshotOverTheBridgesLimitIsNotSent() {
        final JsonObject small = new JsonObject();
        small.addProperty("version", 1);
        assertNotNull(SlimSnapshot.text(small));
        final JsonObject big = new JsonObject();
        big.addProperty("pad", "x".repeat(SlimSnapshot.MAX_BYTES));
        assertNull(SlimSnapshot.text(big));
    }
}
