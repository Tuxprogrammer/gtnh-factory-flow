package com.gtnhplanner.core.model.read;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.conformance.Conformance;
import com.gtnhplanner.core.base.JsonTree;
import com.gtnhplanner.machines.web.Web;

/**
 * SmeltingDatasetRecipe and CraftingDatasetRecipe against the dataset: every case of
 * conformance/data/smelting-records.json and crafting-records.json is a recipe as the export run's reader wrote it
 * (raw; for crafting with the ore dictionary lists its cells name) and the record normalize-oracle-export.mjs made of
 * it (dataset, or null where the dataset drops it). The mod's record of the same recipe must equal the dataset's on
 * every field {@link Web.Recipe} carries.
 */
class FurnaceCraftingDatasetTest {

    private static final double EPS = 1e-12;

    @Test
    void theModsFurnaceRecordIsTheDatasets() throws IOException {
        final TreeSet<String> tags = new TreeSet<>();
        int n = 0;
        for (final Map<String, Object> c : cases("data/smelting-records.json", tags)) {
            final Map<String, Object> raw = JsonTree.object(c.get("raw"));
            final SmeltingRecord r = new SmeltingRecord();
            r.input = raw.get("input") == null ? null : stack(JsonTree.object(raw.get("input")));
            r.output = raw.get("output") == null ? null : stack(JsonTree.object(raw.get("output")));
            same(c.get("dataset"), SmeltingDatasetRecipe.of(r), "smelting case " + n++);
        }
        assertTrue(n >= 5, "the file holds the cases");
        for (final String rule : new String[] { "wildcard", "stacked-output", "vanilla-input" })
            assertTrue(tags.contains(rule), "a case shows " + rule);
    }

    @Test
    void theModsCraftingRecordIsTheDatasets() throws IOException {
        final TreeSet<String> tags = new TreeSet<>();
        int n = 0;
        for (final Map<String, Object> c : cases("data/crafting-records.json", tags)) {
            final CraftingRecord r = crafting(JsonTree.object(c.get("raw")), JsonTree.object(c.get("oreDictionary")));
            same(c.get("dataset"), CraftingDatasetRecipe.of(r), "crafting case " + n++ + " (" + r.className + ")");
        }
        assertTrue(n >= 20, "the file holds the cases");
        for (final String rule : new String[] { "type:shaped", "type:shapeless", "width:1", "width:2", "width:3",
            "cell:item", "cell:oreDictionary", "cell:oreDictionary:names", "cell:oreDictionary:long", "cell:choice",
            "cell:missing", "nbt", "class:appeng.recipes.game.ShapedRecipe" })
            assertTrue(tags.contains(rule), "a case shows " + rule);
    }

    private static List<Map<String, Object>> cases(final String file, final TreeSet<String> tags) throws IOException {
        final Map<String, Object> doc = JsonTree.object(JsonTree.parse(Conformance.text(file)));
        final List<Map<String, Object>> out = new ArrayList<>();
        for (final Object o : JsonTree.array(doc.get("cases"))) {
            final Map<String, Object> c = JsonTree.object(o);
            for (final Object t : JsonTree.array(c.get("tags"))) tags.add((String) t);
            out.add(c);
        }
        return out;
    }

    // ---------------------------------------------------------------- the raw export's recipe as a record

    private static CraftingRecord crafting(final Map<String, Object> raw, final Map<String, Object> ores) {
        final CraftingRecord r = new CraftingRecord();
        r.className = JsonTree.str(raw, "className");
        r.type = JsonTree.str(raw, "type");
        r.width = (int) JsonTree.num(raw, "width");
        r.height = (int) JsonTree.num(raw, "height");
        for (final Object o : JsonTree.array(raw.get("inputs"))) {
            final Map<String, Object> cell = JsonTree.object(o);
            final CraftingRecord.Slot slot = new CraftingRecord.Slot();
            slot.kind = JsonTree.str(cell, "kind");
            slot.slotIndex = (int) JsonTree.num(cell, "slotIndex");
            if (CraftingRecord.Slot.ORE_DICTIONARY.equals(slot.kind)) {
                slot.oreNames = new ArrayList<>();
                slot.alternatives = new ArrayList<>();
                for (final Object name : JsonTree.array(cell.get("names"))) {
                    slot.oreNames.add((String) name);
                    for (final Object ore : JsonTree.array(ores.get(name))) slot.alternatives.add(stack(JsonTree.object(ore)));
                }
            } else if (CraftingRecord.Slot.CHOICE.equals(slot.kind)) {
                slot.choiceId = JsonTree.str(cell, "id");
                slot.alternatives = new ArrayList<>();
                for (final Object alt : JsonTree.array(cell.get("alternatives")))
                    slot.alternatives.add(stack(JsonTree.object(alt)));
            } else if (!CraftingRecord.Slot.MISSING.equals(slot.kind)) {
                slot.stack = stack(cell);
            }
            r.inputs.add(slot);
        }
        r.output = raw.get("output") == null ? null : stack(JsonTree.object(raw.get("output")));
        return r;
    }

    private static GtRecipeRecord.Stack stack(final Map<String, Object> m) {
        final GtRecipeRecord.Stack s = new GtRecipeRecord.Stack();
        s.kind = JsonTree.str(m, "kind");
        s.id = JsonTree.str(m, "id");
        s.registryId = JsonTree.str(m, "registryId");
        s.meta = m.get("meta") == null ? 0 : (int) JsonTree.num(m, "meta");
        s.amount = (int) JsonTree.num(m, "amount");
        s.displayName = JsonTree.str(m, "displayName");
        s.modId = JsonTree.str(m, "modId");
        s.nbt = JsonTree.str(m, "nbt");
        return s;
    }

    // ---------------------------------------------------------------- the comparison

    private static void same(final Object wanted, final Web.Recipe got, final String where) {
        if (wanted == null) {
            assertNull(got, where + ": the dataset drops it");
            return;
        }
        final Map<String, Object> want = JsonTree.object(wanted);
        assertEquals(JsonTree.str(want, "name"), got.name, where + " name");
        assertEquals(JsonTree.str(want, "kind"), got.kind, where + " kind");
        assertEquals(JsonTree.str(want, "category"), got.category, where + " category");
        assertEquals(JsonTree.str(want, "machineType"), got.machineType, where + " machineType");
        assertEquals(JsonTree.str(want, "minimumTier"), got.minimumTier, where + " minimumTier");
        assertEquals(JsonTree.num(want, "durationTicks"), got.durationTicks, EPS, where + " durationTicks");
        assertEquals(JsonTree.num(want, "eut"), got.eut, EPS, where + " eut");
        assertEquals(JsonTree.str(want, "programmedCircuit"), got.programmedCircuit, where + " programmedCircuit");
        assertEquals(JsonTree.boxed(want, "specialValue"), got.specialValue, where + " specialValue");
        resources(JsonTree.array(want.get("inputs")), got.inputs, where + " inputs");
        resources(JsonTree.array(want.get("outputs")), got.outputs, where + " outputs");
        assertEquals(
            JsonTree.str(JsonTree.object(want.get("source")), "recipeMap"),
            got.source.recipeMap,
            where + " source.recipeMap");
        final Object nei = want.get("nei") == null ? null : JsonTree.object(want.get("nei")).get("additionalInfo");
        assertEquals(nei, got.nei == null ? null : got.nei.additionalInfo, where + " nei.additionalInfo");
        assertNull(want.get("metadata"), where + " metadata");
        assertNull(got.metadata, where + " metadata");
        assertNull(want.get("runtimeCalculation"), where + " runtimeCalculation");
        assertNull(got.runtimeCalculation, where + " runtimeCalculation");
    }

    private static void resources(final List<Object> want, final List<Web.Resource> got, final String where) {
        assertEquals(want.size(), got.size(), where + " count");
        for (int i = 0; i < want.size(); i++) {
            final Map<String, Object> w = JsonTree.object(want.get(i));
            final Web.Resource g = got.get(i);
            final String at = where + "[" + i + "]";
            assertEquals(JsonTree.str(w, "kind"), g.kind, at + " kind");
            assertEquals(JsonTree.str(w, "id"), g.id, at + " id");
            assertEquals(JsonTree.num(w, "amount"), g.amount, EPS, at + " amount");
            assertEquals(JsonTree.boxed(w, "chance"), g.chance, at + " chance");
            assertEquals(JsonTree.bool(w, "consumed"), g.consumed, at + " consumed");
            assertEquals(JsonTree.str(w, "displayName"), g.displayName, at + " name");
            assertEquals(w.get("tooltip"), g.tooltip == null ? null : new ArrayList<Object>(g.tooltip), at + " tooltip");
        }
    }
}
