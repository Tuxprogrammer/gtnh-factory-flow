package com.gtnhplanner.read.postea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Reads stand-in registries shaped like Postea's private ones (same field names, same nesting). */
class PosteaRenamesTest {

    /** Stands in for an Item: the name the item registry would give it. */
    static final class FakeItem {

        final String name;

        FakeItem(final String name) {
            this.name = name;
        }
    }

    /** Shaped like SimpleTransformationMap.Value. */
    static final class Value {

        public final Object target;
        public final int targetMeta;
        public int targetRuntimeId = -1;

        Value(final Object target, final int targetMeta) {
            this.target = target;
            this.targetMeta = targetMeta;
        }
    }

    /** Shaped like SimpleTransformationMap. */
    static final class TransformationMap {

        private final Map<String, Map<Integer, Value>> registry = new LinkedHashMap<>();

        void put(final String id, final int meta, final Object target, final int targetMeta) {
            registry.computeIfAbsent(id, k -> new HashMap<>())
                .put(meta, new Value(target, targetMeta));
        }
    }

    /** Shaped like SimpleTransformationRegistry. */
    static final class SimpleRegistry {

        private static final TransformationMap SIMPLE_ITEM_TRANSFORMATION_MAP = new TransformationMap();
    }

    /** Shaped like Guava's LinkedListMultimap: entries() lists every (id, handler). */
    public static final class Multimap {

        private final List<Map.Entry<String, Object>> list = new ArrayList<>();

        public List<Map.Entry<String, Object>> entries() {
            return list;
        }
    }

    /** Shaped like TransformerRegistry. */
    static final class TransformerRegistry {

        private static final Multimap ITEM_REPLACEMENT_MAP = new Multimap();
    }

    /** The table's compiled handler: skipped, its pairs come from the table. */
    static final class SimpleItemTransformationHandler {
    }

    /** A custom handler: code, listed by id. */
    static final class FrameBoxHandler {
    }

    static {
        final int w = PosteaRenames.WILDCARD;
        final FakeItem metaItem02 = new FakeItem("gregtech:gt.metaitem.02");
        final FakeItem metaItem01 = new FakeItem("gregtech:gt.metaitem.01");
        final FakeItem circuit = new FakeItem("gregtech:gt.integrated_circuit");
        SimpleRegistry.SIMPLE_ITEM_TRANSFORMATION_MAP.put("IC2:itemFuelPlantBall", w, metaItem02, 32020);
        SimpleRegistry.SIMPLE_ITEM_TRANSFORMATION_MAP.put("IC2:itemCellEmpty", 6, metaItem01, 30704);
        SimpleRegistry.SIMPLE_ITEM_TRANSFORMATION_MAP.put("IC2:itemCellEmpty", 7, metaItem01, 30245);
        SimpleRegistry.SIMPLE_ITEM_TRANSFORMATION_MAP.put("miscutils:item.BioRecipeSelector", w, circuit, w);
        SimpleRegistry.SIMPLE_ITEM_TRANSFORMATION_MAP.put("gone:item", w, new FakeItem(null), 0);
        TransformerRegistry.ITEM_REPLACEMENT_MAP.entries()
            .add(new AbstractMap.SimpleEntry<>("IC2:itemFuelPlantBall", new SimpleItemTransformationHandler()));
        TransformerRegistry.ITEM_REPLACEMENT_MAP.entries()
            .add(new AbstractMap.SimpleEntry<>("gregtech:gt.blockmachines", new FrameBoxHandler()));
    }

    private static PosteaRenames read() {
        return PosteaRenames
            .read(SimpleRegistry.class.getName(), TransformerRegistry.class.getName(), item -> ((FakeItem) item).name);
    }

    @Test
    void readsEverySimplePairWithItsMetas() {
        final PosteaRenames renames = read();
        assertEquals(4, renames.items.size(), "a target the registry cannot name is left out");
        final PosteaRenames.Pair first = renames.items.get(0);
        assertEquals("IC2:itemCellEmpty", first.from);
        assertEquals(6, first.fromMeta);
        assertEquals("gregtech:gt.metaitem.01", first.to);
        assertEquals(30704, first.toMeta);
        final PosteaRenames.Pair plantball = renames.items.get(2);
        assertEquals("IC2:itemFuelPlantBall", plantball.from);
        assertEquals(PosteaRenames.WILDCARD, plantball.fromMeta);
        assertEquals(32020, plantball.toMeta);
    }

    @Test
    void listsCustomHandlersButNotTheTablesOwn() {
        final PosteaRenames renames = read();
        assertEquals(1, renames.handlers.size());
        assertEquals("gregtech:gt.blockmachines", renames.handlers.get(0).from);
        assertTrue(renames.handlers.get(0).handler.endsWith("FrameBoxHandler"));
    }

    @Test
    void exportsRawRows() {
        final Map<String, Object> out = read().toExport();
        assertEquals("postea", out.get("source"));
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        assertEquals(
            "miscutils:item.BioRecipeSelector",
            items.get(3)
                .get("from"));
        assertEquals(
            PosteaRenames.WILDCARD,
            items.get(3)
                .get("toMeta"));
    }

    @Test
    void withoutPosteaTheTableIsEmpty() {
        final PosteaRenames renames = PosteaRenames.read("no.such.Registry", "no.such.Other", item -> "x");
        assertTrue(renames.items.isEmpty());
        assertTrue(renames.handlers.isEmpty());
    }

    /**
     * The pipeline's fixture through the mod's rule: the same ids its resource-renames.test.mjs expects, so the
     * dataset's sidecar, the website and the mod rename alike.
     */
    @Test
    void turnsRowsIntoResourceIdsAsThePipelineDoes() throws IOException {
        final JsonObject raw = new JsonParser()
            .parse(
                new String(
                    Files.readAllBytes(
                        Paths.get(
                            "../../tools/dataset-pipeline/scripts/__fixtures__/postea-renames-gt-ic2-2.9.1.raw.json")),
                    StandardCharsets.UTF_8))
            .getAsJsonObject();
        final List<PosteaRenames.Pair> pairs = new ArrayList<>();
        for (final JsonElement e : raw.getAsJsonArray("items")) {
            final JsonObject row = e.getAsJsonObject();
            pairs.add(
                new PosteaRenames.Pair(
                    row.get("from")
                        .getAsString(),
                    row.get("fromMeta")
                        .getAsInt(),
                    row.get("to")
                        .getAsString(),
                    row.get("toMeta")
                        .getAsInt()));
        }
        final Map<String, String> table = PosteaRenames.table(pairs);
        assertEquals("gregtech:gt.metaitem.02@32020", table.get("ic2:itemfuelplantball@*"));
        assertEquals("gregtech:gt.metaitem.01@30704", table.get("ic2:itemcellempty@6"));
        assertEquals("gregtech:gt.metaitem.02@32020", PosteaRenames.rename(table, "ic2:itemfuelplantball"));
        assertEquals("gregtech:gt.metaitem.02@32021", PosteaRenames.rename(table, "ic2:itembiochaff"));
        assertEquals("gregtech:gt.metaitem.01@30245", PosteaRenames.rename(table, "ic2:itemcellempty@7"));
        assertEquals("ic2:itemcellempty@8", PosteaRenames.rename(table, "ic2:itemcellempty@8"));
        assertEquals("ic2:itemcellempty", PosteaRenames.rename(table, "ic2:itemcellempty"));
    }

    @Test
    void keepsTheStacksMetaWhenTheTargetSaysSo() {
        final Map<String, String> keep = PosteaRenames.table(
            List.of(
                new PosteaRenames.Pair(
                    "miscutils:item.BioRecipeSelector",
                    PosteaRenames.WILDCARD,
                    "gregtech:gt.integrated_circuit",
                    PosteaRenames.WILDCARD)));
        assertEquals(
            "gregtech:gt.integrated_circuit@12",
            PosteaRenames.rename(keep, "miscutils:item.biorecipeselector@12"));
        assertEquals("gregtech:gt.integrated_circuit", PosteaRenames.rename(keep, "miscutils:item.biorecipeselector"));
        assertEquals(
            Map.of(),
            PosteaRenames.table(List.of(new PosteaRenames.Pair("a:b", 3, "A:B", 3))),
            "no self rows");
    }
}
