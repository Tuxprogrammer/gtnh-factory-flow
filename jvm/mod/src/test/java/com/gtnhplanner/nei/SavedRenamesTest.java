package com.gtnhplanner.nei;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Plan;
import com.gtnhplanner.data.flowchart.Serializer;

import codechicken.nei.recipe.Recipe;

/**
 * A plan of this world's save made before the pack renamed some of its items (2.9.1 moved IC2's plantball and bio chaff
 * to GregTech meta items, through Postea) is read under the new ids: a card the game no longer finds as saved is found
 * under its renamed items, its drawers, picks and leftover splits follow, and nothing changes the plan's identity (card
 * ids, wires, world links). A card found as saved, and the items it names, stay as they are; a card found neither way
 * stays as saved. The game here is stood in: its renames, the recipes it has, and its registry's spelling.
 */
class SavedRenamesTest {

    private static final String PLANTBALL = "IC2:itemFuelPlantBall";
    private static final String BIOCHAFF = "IC2:itemBiochaff";
    private static final String META = "gregtech:gt.metaitem.02";
    private static final String COMPRESSOR = "gregtech.nei.GTNEIDefaultHandler@gt.recipe.compressor";
    private static final String MACERATOR = "gregtech.nei.GTNEIDefaultHandler@gt.recipe.macerator";
    private static final String COKE = "gregtech.nei.GTNEIDefaultHandler@gt.recipe.industrialcokeoven";
    private static final String CRAFTING = "crafting";

    private static final String A = "00000000-0000-4000-8000-00000000000a";
    private static final String B = "00000000-0000-4000-8000-00000000000b";
    private static final String C = "00000000-0000-4000-8000-00000000000c";
    private static final String D = "00000000-0000-4000-8000-00000000000d";

    /** The recipes the stood-in game has, by handler, ingredients and result. */
    private final Set<String> recipes = new HashSet<>();
    /** Every id the game was asked for. */
    private final List<String> asked = new ArrayList<>();

    private Supplier<Map<String, String>> table;
    private Predicate<JsonObject> found;
    private Function<String, String> registryName;
    private Function<JsonObject, Recipe.RecipeId> ids;

    /** A saved id as NEI holds it, written back as it was read: NEI reads a save's items from the running game. */
    private static Recipe.RecipeId held(final JsonObject json) {
        final String handler = json.get("handlerName")
            .getAsString();
        return new Recipe.RecipeId(null, handler, List.of()) {

            @Override
            public JsonObject toJsonObject() {
                return new JsonParser().parse(json.toString())
                    .getAsJsonObject();
            }
        };
    }

    @BeforeAll
    static void defaultProfile() {
        com.gtnhplanner.harness.GtnhFlowLoader.ensureDefaultMachineProfile();
    }

    @BeforeEach
    void standInTheGame() {
        table = SavedRenames.table;
        found = SavedRenames.found;
        registryName = SavedRenames.registryName;
        ids = NeiRecipes.ids;
        NeiRecipes.ids = SavedRenamesTest::held;
        // The table as the game's Postea gives it (the dataset's resource-renames.json carries the same pairs).
        SavedRenames.table = () -> Map
            .of("ic2:itemfuelplantball", META + "@32020", "ic2:itembiochaff", META + "@32021");
        SavedRenames.found = json -> {
            asked.add(signature(json));
            return recipes.contains(signature(json));
        };
        SavedRenames.registryName = lower -> lower.equals(META) ? META : null;
        // 2.9.1: the compressor and the macerator are on the GregTech plant ball; a crafting recipe is still on IC2's.
        recipes.add(COMPRESSOR + "|Natura:berry@1->" + META + "@32020");
        recipes.add(MACERATOR + "|" + META + "@32020->dreamcraft:MaceratedPlantmass@0");
        recipes.add(CRAFTING + "|" + PLANTBALL + "@0->minecraft:dirt@0");
    }

    @AfterEach
    void putTheGameBack() {
        SavedRenames.table = table;
        SavedRenames.found = found;
        SavedRenames.registryName = registryName;
        NeiRecipes.ids = ids;
    }

    @Test
    void aSavedPlanFindsItsRenamedRecipesWithItsWiresDrawersAndWorldLinksIntact() {
        final JsonObject save = save(plan(false));
        final Graph graph = only(Serializer.decodePlan(Serializer.GSON.toJson(save)));

        // The plan is the same plan: its cards, wires and drawers by the ids it saved them under.
        assertEquals(Set.of(id(A), id(B), id(C)), graph.nodes.keySet());
        assertEquals(
            2,
            graph.getEdges()
                .size());
        final Edge ab = graph.getEdges()
            .stream()
            .filter(e -> e.sourceNodeId.equals(id(A)))
            .findFirst()
            .orElseThrow();
        assertEquals(id(B), ab.targetNodeId);
        assertEquals(0, ab.sourceOutputIndex);
        assertEquals(0, ab.targetInputIndex);
        final Node a = graph.nodes.get(id(A));
        assertEquals(1, a.worldLinks.size(), "the machine placed in the world stays linked to the card");
        assertArrayEquals(new int[] { 0, 10, 64, -20 }, a.worldLinks.getFirst());

        // The compressor (its result renamed) and the macerator (its input renamed) are read under the new ids.
        assertTrue(
            asked.contains(COMPRESSOR + "|Natura:berry@1->" + META + "@32020"),
            "the compressor is looked for under the plant ball's new id: " + asked);
        assertTrue(asked.contains(MACERATOR + "|" + META + "@32020->dreamcraft:MaceratedPlantmass@0"));
        assertEquals(
            META,
            item(
                Serializer.nodeToJson(graph.nodes.get(id(B)))
                    .getAsJsonObject("recipeId"),
                META),
            "the macerator is read, and written once edited, with its new recipe");
        assertEquals("item:" + META + ":32020", graph.nodes.get(id(B)).inputPicks.get(0), "the pick follows its card");

        // The drawer on the compressor's output, and the one on the coke oven's input, hold the new items.
        final Drawer product = drawer(graph, "10000000-0000-4000-8000-000000000001");
        assertEquals("item:" + META + ":32020", product.getResourceKey());
        assertEquals(List.of(new Drawer.Link(id(A), 0)), product.getLinks(), "the drawer stays on the same port");
        final Drawer source = drawer(graph, "10000000-0000-4000-8000-000000000002");
        assertEquals("item:" + META + ":32021", source.getResourceKey());
        assertEquals(List.of(new Drawer.Link(id(C), 0)), source.getLinks());
        assertEquals(Set.of("item:" + META + ":32020"), graph.spareShares.keySet());
        assertEquals(Map.of(A + ":o:0", 1.0), graph.spareShares.get("item:" + META + ":32020"));
    }

    @Test
    void aCardWhoseRenamedRecipeIsGoneStaysAsSaved() {
        final Graph graph = only(Serializer.decodePlan(Serializer.GSON.toJson(save(plan(false)))));
        final Node coke = graph.nodes.get(id(C));
        assertNotNull(coke, "the card is kept");
        assertTrue(
            asked.contains(COKE + "|" + META + "@32021,water->ic2biomass"),
            "it was looked for under the new id too: " + asked);
        final JsonObject written = Serializer.nodeToJson(coke);
        assertEquals(
            BIOCHAFF,
            item(written.getAsJsonObject("recipeId"), BIOCHAFF),
            "found neither way, it keeps the recipe it saved: " + written);
        assertTrue(coke.inputs.isEmpty() && coke.outputs.isEmpty(), "a card the game has no recipe for reads nothing");
        assertEquals(
            1,
            graph.getEdges()
                .stream()
                .filter(e -> e.targetNodeId.equals(id(C)))
                .count(),
            "its wire is kept for when the recipe is back");
    }

    @Test
    void aCardFoundAsSavedKeepsItsItemsEverywhere() {
        final Graph graph = only(Serializer.decodePlan(Serializer.GSON.toJson(save(plan(true)))));
        assertTrue(asked.contains(CRAFTING + "|" + PLANTBALL + "@0->minecraft:dirt@0"));
        assertTrue(
            asked.stream()
                .noneMatch(s -> s.startsWith(CRAFTING + "|" + META)),
            "a card found as saved is never renamed: " + asked);
        // IC2's plant ball is still a real item a card uses: its drawer and split stay on it.
        assertEquals(
            "item:" + PLANTBALL + ":0",
            drawer(graph, "10000000-0000-4000-8000-000000000001").getResourceKey());
        assertEquals(Set.of("item:" + PLANTBALL + ":0"), graph.spareShares.keySet());
        // The bio chaff is named by no card found as saved: it follows the rename.
        assertEquals("item:" + META + ":32021", drawer(graph, "10000000-0000-4000-8000-000000000002").getResourceKey());
    }

    @Test
    void aSaveIsWrittenBackAsItWasUntilThePlanIsEdited() {
        final String file = Serializer.GSON.toJson(save(plan(false)));
        final Plan plan = Serializer.decodePlan(file);
        assertEquals(
            file,
            Serializer.GSON.toJson(Serializer.planTree(plan)),
            "read under the new ids, saved as it was");
        final Graph graph = only(plan);
        graph.touchLayout();
        final JsonObject data = data(Serializer.GSON.toJson(Serializer.planTree(plan)));
        // Once edited, the plan is written as it is read: the drawers on the new items, the cards, wires and links
        // kept.
        final JsonArray drawers = data.getAsJsonArray("drawers");
        assertEquals(
            "item:" + META + ":32020",
            drawers.get(0)
                .getAsJsonObject()
                .get("resourceKey")
                .getAsString());
        assertEquals(
            3,
            data.getAsJsonArray("nodes")
                .size());
        assertEquals(
            2,
            data.getAsJsonArray("edges")
                .size());
        assertTrue(
            data.toString()
                .contains("\"worldLinks\":[[0,10,64,-20]]"));
    }

    @Test
    void aSaveWithNothingRenamedRoundTripsByteForByte() {
        SavedRenames.table = Map::of;
        final String file = Serializer.GSON.toJson(save(plan(false)));
        final Plan plan = Serializer.decodePlan(file);
        assertEquals(file, Serializer.GSON.toJson(Serializer.planTree(plan)));
        assertTrue(asked.isEmpty(), "no renames: nothing is looked for twice");
        assertEquals(
            "item:" + PLANTBALL + ":0",
            drawer(only(plan), "10000000-0000-4000-8000-000000000001").getResourceKey());
    }

    @Test
    void aLoadReadsNoRecipeTwiceThatNamesNoRenamedItem() {
        final SavedRenames.Load load = SavedRenames.begin();
        final JsonObject id = recipe(COMPRESSOR, List.of(stack("Natura:berry", 1, 8)), stack("Natura:berry", 2, 1));
        assertSame(id, load.recipe(id));
        assertTrue(asked.isEmpty());
    }

    // ── The synthetic save ──

    /**
     * Plan 5's chain cut short: a compressor making IC2's plant ball (its world link kept), a macerator on it (its
     * input picked), a coke oven on bio chaff whose 2.9.1 recipe is gone, wired A to B and B to C, a product drawer on
     * the plant ball and a source drawer of bio chaff on the coke oven, and a leftover split of the plant ball. With
     * {@code craft}, a crafting card still on IC2's plant ball, which the game finds as saved.
     */
    private static JsonObject plan(final boolean craft) {
        final JsonObject g = new JsonObject();
        g.addProperty("balanceMode", "AUTO");
        g.addProperty("zoom", 1.0f);
        g.addProperty("panX", 0f);
        g.addProperty("panY", 0f);
        g.addProperty("name", "Plan 5");
        final JsonArray nodes = new JsonArray();
        final JsonObject a = card(
            A,
            "Compressor",
            recipe(COMPRESSOR, List.of(stack("Natura:berry", 1, 8)), stack(PLANTBALL, 0, 0)),
            13);
        a.add("worldLinks", new JsonParser().parse("[[0,10,64,-20]]"));
        nodes.add(a);
        final JsonObject b = card(
            B,
            "Macerator",
            recipe(MACERATOR, List.of(stack(PLANTBALL, 0, 2)), stack("dreamcraft:MaceratedPlantmass", 0, 0)),
            0);
        final JsonObject picks = new JsonObject();
        picks.addProperty("0", "item:" + PLANTBALL + ":0");
        b.add("inputPicks", picks);
        nodes.add(b);
        final JsonObject water = new JsonObject();
        water.addProperty("Count", "4000L");
        water.addProperty("neiFluidName", "water");
        final JsonObject biomass = new JsonObject();
        biomass.addProperty("Count", "0L");
        biomass.addProperty("neiFluidName", "ic2biomass");
        final JsonObject chaff = stack(BIOCHAFF, 0, 4);
        nodes.add(card(C, "Industrial Coke Oven", recipe(COKE, List.of(chaff, water), biomass), 0));
        if (craft) nodes.add(
            card(D, "Crafting", recipe(CRAFTING, List.of(stack(PLANTBALL, 0, 1)), stack("minecraft:dirt", 0, 0)), 0));
        g.add("nodes", nodes);
        final JsonArray edges = new JsonArray();
        edges.add(edge("20000000-0000-4000-8000-000000000001", A, B));
        edges.add(edge("20000000-0000-4000-8000-000000000002", B, C));
        g.add("edges", edges);
        g.add("notes", new JsonArray());
        g.add("groups", new JsonArray());
        g.add("spareShares", new JsonParser().parse("{\"item:" + PLANTBALL + ":0\":{\"" + A + ":o:0\":1.0}}"));
        final JsonArray drawers = new JsonArray();
        drawers.add(drawer("10000000-0000-4000-8000-000000000001", "PRODUCT", A, "item:" + PLANTBALL + ":0"));
        drawers.add(drawer("10000000-0000-4000-8000-000000000002", "SOURCE", C, "item:" + BIOCHAFF + ":0"));
        g.add("drawers", drawers);
        return g;
    }

    private static JsonObject card(final String id, final String machine, final JsonObject recipe, final int index) {
        final JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("x", 100);
        o.addProperty("y", 200);
        o.addProperty("machine", machine);
        o.add("recipeId", recipe);
        o.addProperty("handlerRecipeIndex", index);
        o.addProperty("extractorIndex", 0);
        o.addProperty("machineCount", 1);
        o.add("inputs", new JsonParser().parse("[{\"type\":\"item\",\"chance\":1.0}]"));
        o.add("outputs", new JsonParser().parse("[{\"type\":\"item\",\"chance\":1.0}]"));
        return o;
    }

    private static JsonObject recipe(final String handler, final List<JsonObject> in, final JsonObject result) {
        final JsonObject o = new JsonObject();
        o.addProperty("handlerName", handler);
        final JsonArray ingredients = new JsonArray();
        for (final JsonObject i : in) ingredients.add(i);
        o.add("ingredients", ingredients);
        o.add("result", result);
        return o;
    }

    private static JsonObject stack(final String strId, final int damage, final int count) {
        final JsonObject o = new JsonObject();
        o.addProperty("Count", count);
        o.addProperty("Damage", damage);
        o.addProperty("strId", strId);
        return o;
    }

    private static JsonObject edge(final String id, final String src, final String dst) {
        final JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("src", src);
        o.addProperty("dst", dst);
        o.addProperty("srcOut", 0);
        o.addProperty("dstIn", 0);
        return o;
    }

    private static JsonObject drawer(final String id, final String kind, final String node, final String key) {
        return new JsonParser()
            .parse(
                "{\"kind\":\"" + kind
                    + "\",\"rule\":\"AT_LEAST\",\"rate\":1.0,\"links\":[{\"nodeId\":\""
                    + node
                    + "\",\"portIndex\":0}],\"takers\":[],\"resourceKey\":\""
                    + key
                    + "\",\"label\":\"x\",\"id\":\""
                    + id
                    + "\",\"x\":0,\"y\":0,\"header\":\"Drawer\",\"type\":\"drawer\"}")
            .getAsJsonObject();
    }

    /** The world's save file holding one plan, as the mod writes it: the plan's data gzipped and in base64. */
    private static JsonObject save(final JsonObject graph) {
        try {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (final OutputStreamWriter w = new OutputStreamWriter(
                new GZIPOutputStream(bytes),
                StandardCharsets.UTF_8)) {
                w.write(Serializer.GSON.toJson(graph));
            }
            final JsonObject slot = new JsonObject();
            slot.addProperty("name", "Plan 5");
            slot.addProperty(
                "data",
                Base64.getEncoder()
                    .encodeToString(bytes.toByteArray()));
            final JsonObject root = new JsonObject();
            root.addProperty("activeIndex", 0);
            root.addProperty("snapToGrid", false);
            root.add(
                "summary",
                new JsonParser().parse(
                    "{\"collapsedSummaryFolds\":0,\"mode\":\"CYCLES\",\"rateUnit\":\"SECONDS\","
                        + "\"sectionOrder\":[0,1,2,3,4,5,6,7],\"id\":\"30000000-0000-4000-8000-000000000001\","
                        + "\"x\":210,\"y\":46,\"header\":\"Summary\",\"type\":\"summary\"}"));
            final JsonArray graphs = new JsonArray();
            graphs.add(slot);
            root.add("graphs", graphs);
            return root;
        } catch (final java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The plan's data in a written save, unzipped. */
    private static JsonObject data(final String file) {
        final String data = new JsonParser().parse(file)
            .getAsJsonObject()
            .getAsJsonArray("graphs")
            .get(0)
            .getAsJsonObject()
            .get("data")
            .getAsString();
        try (final java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(
            new java.io.ByteArrayInputStream(
                Base64.getDecoder()
                    .decode(data)))) {
            return new JsonParser().parse(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        } catch (final java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── Reading back ──

    private static Graph only(final Plan plan) {
        assertEquals(
            1,
            plan.getGraphs()
                .size());
        return plan.getGraphs()
            .getFirst();
    }

    private static Uuid id(final String s) {
        return Uuid.fromString(s);
    }

    private static Drawer drawer(final Graph graph, final String id) {
        return graph.getDrawers()
            .stream()
            .filter(
                d -> d.getId()
                    .equals(id(id)))
            .findFirst()
            .orElseThrow();
    }

    /** The strId of the entry in a saved id that names {@code strId}, or "" when none does. */
    private static String item(final JsonObject id, final String strId) {
        for (final JsonElement e : id.getAsJsonArray("ingredients")) if (e.isJsonObject() && e.getAsJsonObject()
            .has("strId")
            && strId.equals(
                e.getAsJsonObject()
                    .get("strId")
                    .getAsString()))
            return strId;
        return "";
    }

    /** A saved id as the stood-in game knows its recipes: handler, ingredients, result. */
    private static String signature(final JsonObject id) {
        final StringBuilder s = new StringBuilder(
            id.get("handlerName")
                .getAsString()).append('|');
        final List<String> in = new ArrayList<>();
        for (final JsonElement e : id.getAsJsonArray("ingredients")) in.add(entry(e.getAsJsonObject()));
        s.append(String.join(",", in))
            .append("->")
            .append(entry(id.getAsJsonObject("result")));
        return s.toString();
    }

    private static String entry(final JsonObject o) {
        if (o.has("neiFluidName")) return o.get("neiFluidName")
            .getAsString();
        return o.get("strId")
            .getAsString() + "@"
            + o.get("Damage")
                .getAsInt();
    }
}
