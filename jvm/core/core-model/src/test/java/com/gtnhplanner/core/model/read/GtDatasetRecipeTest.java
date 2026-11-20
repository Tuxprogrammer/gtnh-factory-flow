package com.gtnhplanner.core.model.read;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * GtDatasetRecipe against the dataset: every case of conformance/data/gt-records.json is a GregTech recipe as the
 * export run's reader wrote it (raw) and the record normalize-oracle-export.mjs made of it (dataset). The mod's record
 * of the same recipe must equal the dataset's on every field {@link Web.Recipe} carries.
 */
class GtDatasetRecipeTest {

    private static final double EPS = 1e-12;

    @Test
    void theModsRecordIsTheDatasets() throws IOException {
        final Map<String, Object> doc = JsonTree.object(JsonTree.parse(Conformance.text("data/gt-records.json")));
        final TreeSet<String> tags = new TreeSet<>();
        int n = 0;
        for (final Object o : JsonTree.array(doc.get("cases"))) {
            final Map<String, Object> c = JsonTree.object(o);
            final Map<String, Object> raw = JsonTree.object(c.get("raw"));
            final Map<String, Object> want = JsonTree.object(c.get("dataset"));
            final GtRecipeRecord record = record(JsonTree.object(raw.get("map")), JsonTree.object(raw.get("recipe")));
            final String where = "case " + n + " (" + record.mapId + ")";
            same(want, GtDatasetRecipe.of(record, JsonTree.str(want, "machineType")), where);
            for (final Object t : JsonTree.array(c.get("tags"))) tags.add((String) t);
            n++;
        }
        assertTrue(n >= 40, "the file holds the cases");
        for (final String rule : new String[] { "amps", "circuit", "kept:itemInputs", "inputChance:itemInputs",
            "chance:itemOutputs", "alternatives:itemInputs", "alternatives:fluidInputs", "special", "nbt",
            "profile:blast-furnace-heat", "profile:pyrolyse-coil", "profile:oil-cracker-coil",
            "profile:large-chemical-reactor-perfect", "map:gt.recipe.fusionreactor", "map:gt.recipe.rockbreaker",
            "map:gt.recipe.scanner", "fluid-only" })
            assertTrue(tags.contains(rule), "a case shows " + rule);
    }

    @Test
    void theCircuitSettingIsTheProgrammedCircuitsDamage() {
        assertEquals("11", GtDatasetRecipe.programmedCircuit(List.of(item("gregtech:gt.integrated_circuit@11"))));
        assertNull(GtDatasetRecipe.programmedCircuit(List.of(item("gregtech:gt.integrated_circuit@32767"))));
        assertNull(
            GtDatasetRecipe.programmedCircuit(List.of(item("gregtech:gt.metaitem.01@32100"))),
            "a circuit board is no setting");
    }

    private static Web.Resource item(final String id) {
        final Web.Resource r = new Web.Resource();
        r.kind = "item";
        r.id = id;
        return r;
    }

    // ---------------------------------------------------------------- the raw export's recipe as a record

    private static GtRecipeRecord record(final Map<String, Object> map, final Map<String, Object> recipe) {
        final GtRecipeRecord r = new GtRecipeRecord();
        r.mapId = JsonTree.str(map, "id");
        r.mapName = JsonTree.str(map, "name");
        r.amperage = (int) JsonTree.num(map, "amperage");
        r.durationTicks = (int) JsonTree.num(recipe, "durationTicks");
        r.eut = (long) JsonTree.num(recipe, "eut");
        r.specialValue = (int) JsonTree.num(recipe, "specialValue");
        final Double startup = JsonTree.boxed(recipe, "fusionStartupEu");
        if (startup != null) r.fusionStartupEu = startup.longValue();
        stacks(r.itemInputs, recipe.get("itemInputs"));
        stacks(r.fluidInputs, recipe.get("fluidInputs"));
        stacks(r.itemOutputs, recipe.get("itemOutputs"));
        stacks(r.fluidOutputs, recipe.get("fluidOutputs"));
        stacks(r.nonConsumedInputs, recipe.get("nonConsumedInputs"));
        final Map<String, Object> rt = JsonTree.object(recipe.get("runtimeCalculation"));
        r.runtime = new GtRecipeRecord.Runtime();
        r.runtime.status = JsonTree.str(rt, "status");
        r.runtime.oracleEligible = !Boolean.FALSE.equals(JsonTree.bool(rt, "oracleEligible"));
        r.runtime.strict = !Boolean.FALSE.equals(JsonTree.bool(rt, "strict"));
        if (JsonTree.str(rt, "profile") != null) r.runtime.profile = JsonTree.str(rt, "profile");
        for (final Object v : JsonTree.array(rt.get("compactVariants"))) {
            final List<Object> a = JsonTree.array(v);
            final GtRecipeRecord.Variant variant = new GtRecipeRecord.Variant();
            variant.tier = ((Double) a.get(0)).intValue();
            variant.durationTicks = ((Double) a.get(1)).intValue();
            variant.eut = ((Double) a.get(2)).longValue();
            if (a.size() > 3) variant.profile = (String) a.get(3);
            if (a.size() > 4) variant.configKey = (String) a.get(4);
            r.runtime.variants.add(variant);
        }
        return r;
    }

    private static void stacks(final List<GtRecipeRecord.Stack> into, final Object list) {
        for (final Object o : JsonTree.array(list)) into.add(stack(JsonTree.object(o)));
    }

    private static GtRecipeRecord.Stack stack(final Map<String, Object> m) {
        final GtRecipeRecord.Stack s = new GtRecipeRecord.Stack();
        s.kind = JsonTree.str(m, "kind");
        s.id = JsonTree.str(m, "id");
        s.registryId = JsonTree.str(m, "registryId");
        s.meta = (int) JsonTree.num(m, "meta");
        s.amount = (int) JsonTree.num(m, "amount");
        s.displayName = JsonTree.str(m, "displayName");
        s.modId = JsonTree.str(m, "modId");
        s.nbt = JsonTree.str(m, "nbt");
        s.consumed = JsonTree.bool(m, "consumed");
        final Double inputChance = JsonTree.boxed(m, "inputChance");
        if (inputChance != null) s.inputChance = inputChance.intValue();
        s.chance = JsonTree.boxed(m, "chance");
        if (m.get("alternatives") != null) {
            s.alternatives = new ArrayList<>();
            stacks(s.alternatives, m.get("alternatives"));
        }
        return s;
    }

    // ---------------------------------------------------------------- the comparison

    private static void same(final Map<String, Object> want, final Web.Recipe got, final String where) {
        assertEquals(JsonTree.str(want, "name"), got.name, where + " name");
        assertEquals(JsonTree.str(want, "kind"), got.kind, where + " kind");
        assertEquals(JsonTree.str(want, "category"), got.category, where + " category");
        assertEquals(JsonTree.str(want, "machineType"), got.machineType, where + " machineType");
        assertEquals(JsonTree.str(want, "minimumTier"), got.minimumTier, where + " minimumTier");
        assertEquals(JsonTree.num(want, "durationTicks"), got.durationTicks, EPS, where + " durationTicks");
        assertEquals(JsonTree.num(want, "eut"), got.eut, EPS, where + " eut");
        assertEquals(JsonTree.str(want, "programmedCircuit"), got.programmedCircuit, where + " programmedCircuit");
        assertEquals(JsonTree.num(want, "specialValue"), got.specialValue, EPS, where + " specialValue");
        resources(JsonTree.array(want.get("inputs")), got.inputs, where + " inputs");
        resources(JsonTree.array(want.get("outputs")), got.outputs, where + " outputs");
        assertEquals(
            JsonTree.str(JsonTree.object(want.get("source")), "recipeMap"),
            got.source.recipeMap,
            where + " source.recipeMap");
        assertEquals(
            JsonTree.array(JsonTree.object(want.get("nei")).get("additionalInfo")),
            new ArrayList<Object>(got.nei.additionalInfo),
            where + " nei");
        final Map<String, Object> meta = JsonTree.object(want.get("metadata"));
        assertEquals(meta.keySet(), got.metadata.keySet(), where + " metadata keys");
        for (final Map.Entry<String, Object> e : meta.entrySet())
            assertEquals(e.getValue(), got.metadata.get(e.getKey()), where + " metadata." + e.getKey());
        runtime(JsonTree.object(want.get("runtimeCalculation")), got.runtimeCalculation, where + " runtime");
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
            if (w.containsKey("displayName")) assertEquals(JsonTree.str(w, "displayName"), g.displayName, at + " name");
            assertEquals(w.get("tooltip"), g.tooltip == null ? null : new ArrayList<Object>(g.tooltip), at + " tooltip");
        }
    }

    private static void runtime(final Map<String, Object> want, final Web.RuntimeCalculation got, final String where) {
        assertNotNull(got, where);
        assertEquals(JsonTree.str(want, "status"), got.status, where + " status");
        assertEquals(JsonTree.bool(want, "oracleEligible"), got.oracleEligible, where + " oracleEligible");
        assertEquals(JsonTree.bool(want, "strict"), got.strict, where + " strict");
        final List<Object> variants = JsonTree.array(want.get("variants"));
        assertEquals(variants.size(), got.variants.size(), where + " variants");
        for (int i = 0; i < variants.size(); i++) {
            final Map<String, Object> w = JsonTree.object(variants.get(i));
            final Web.RuntimeVariant g = got.variants.get(i);
            final String at = where + "[" + i + "]";
            assertEquals(JsonTree.str(w, "id"), g.id, at + " id");
            assertEquals(JsonTree.str(w, "label"), g.label, at + " label");
            assertEquals(JsonTree.str(w, "overclockTier"), g.overclockTier, at + " tier");
            assertEquals(JsonTree.str(w, "coilTier"), g.coilTier, at + " coil");
            assertEquals(JsonTree.num(w, "durationTicks"), g.durationTicks, EPS, at + " durationTicks");
            assertEquals(JsonTree.num(w, "eut"), g.eut, EPS, at + " eut");
            assertEquals(JsonTree.boxed(w, "parallel"), g.parallel, at + " parallel");
            final List<Object> outs = JsonTree.array(w.get("outputs"));
            assertEquals(outs.size(), g.outputs.size(), at + " outputs");
            for (int k = 0; k < outs.size(); k++) {
                final Map<String, Object> wo = JsonTree.object(outs.get(k));
                final Web.Resource go = g.outputs.get(k);
                assertEquals(JsonTree.str(wo, "id"), go.id, at + " output id");
                assertEquals(JsonTree.str(wo, "kind"), go.kind, at + " output kind");
                assertEquals(JsonTree.num(wo, "amount"), go.amount, EPS, at + " output amount");
                assertEquals(JsonTree.boxed(wo, "chance"), go.chance, at + " output chance");
            }
        }
    }
}
