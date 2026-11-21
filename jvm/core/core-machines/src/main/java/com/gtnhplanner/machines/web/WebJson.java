package com.gtnhplanner.machines.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.JsonTree;

/**
 * The website's recipe and node shapes ({@link Web}) read from a parsed JSON tree, field by field under the website's
 * names: no reflection, so the same binding runs where Gson cannot. A field the JSON lacks (or holds as null, or as a
 * kind the field cannot take) is null, false or 0, as Gson leaves it; a list or map field is null when absent.
 */
public final class WebJson {

    private WebJson() {}

    /** A recipe; null for anything that is not a JSON object. */
    @Nullable
    public static Web.Recipe recipe(@Nullable final Object tree) {
        if (!(tree instanceof Map)) return null;
        final Map<String, Object> o = JsonTree.object(tree);
        final Web.Recipe r = new Web.Recipe();
        r.id = JsonTree.str(o, "id");
        r.name = JsonTree.str(o, "name");
        r.kind = JsonTree.str(o, "kind");
        r.category = JsonTree.str(o, "category");
        r.machineType = JsonTree.str(o, "machineType");
        r.minimumTier = JsonTree.str(o, "minimumTier");
        r.maximumTier = JsonTree.str(o, "maximumTier");
        r.availableTiers = strings(o, "availableTiers");
        r.durationTicks = JsonTree.num(o, "durationTicks");
        r.eut = JsonTree.num(o, "eut");
        r.inputs = list(o, "inputs", WebJson::resource);
        r.outputs = list(o, "outputs", WebJson::resource);
        r.programmedCircuit = JsonTree.str(o, "programmedCircuit");
        r.specialValue = JsonTree.boxed(o, "specialValue");
        r.machineProfile = o.get("machineProfile") instanceof Map ? profile(JsonTree.object(o.get("machineProfile")))
            : null;
        r.machineHandlers = list(o, "machineHandlers", WebJson::handler);
        r.machineConfigControls = list(o, "machineConfigControls", WebJson::control);
        r.power = o.get("power") instanceof Map ? JsonTree.object(o.get("power")) : null;
        r.source = o.get("source") instanceof Map ? source(JsonTree.object(o.get("source"))) : null;
        r.metadata = o.get("metadata") instanceof Map ? JsonTree.object(o.get("metadata")) : null;
        r.nei = o.get("nei") instanceof Map ? nei(JsonTree.object(o.get("nei"))) : null;
        r.runtimeCalculation = o.get("runtimeCalculation") instanceof Map
            ? runtimeCalculation(JsonTree.object(o.get("runtimeCalculation")))
            : null;
        return r;
    }

    /** A node's fields the machine maths read; null for anything that is not a JSON object. */
    @Nullable
    public static Web.Node node(@Nullable final Object tree) {
        if (!(tree instanceof Map)) return null;
        final Map<String, Object> o = JsonTree.object(tree);
        final Web.Node n = new Web.Node();
        n.overclockTier = JsonTree.str(o, "overclockTier");
        n.hatchVoltageTier = JsonTree.str(o, "hatchVoltageTier");
        n.hatchAmps = JsonTree.boxed(o, "hatchAmps");
        n.energyHatches = JsonTree.boxed(o, "energyHatches");
        n.energyHatchType = JsonTree.str(o, "energyHatchType");
        n.powerInputMode = JsonTree.str(o, "powerInputMode");
        n.powerEuT = JsonTree.boxed(o, "powerEuT");
        n.coilTier = JsonTree.str(o, "coilTier");
        n.machineConfigTiers = stringMap(o, "machineConfigTiers");
        n.machineHandlerId = JsonTree.str(o, "machineHandlerId");
        n.parallel = JsonTree.boxed(o, "parallel");
        n.machineCount = JsonTree.boxed(o, "machineCount");
        return n;
    }

    /**
     * A port. The website sends a recipe's ports with only these keys (PORT_KEYS in src/lib/core/machines.ts): a key read
     * here goes into that list too.
     */
    static Web.Resource resource(final Map<String, Object> o) {
        final Web.Resource r = new Web.Resource();
        r.kind = JsonTree.str(o, "kind");
        r.id = JsonTree.str(o, "id");
        r.amount = JsonTree.num(o, "amount");
        r.chance = JsonTree.boxed(o, "chance");
        r.consumed = JsonTree.bool(o, "consumed");
        r.optional = JsonTree.bool(o, "optional");
        r.displayName = JsonTree.str(o, "displayName");
        r.tooltip = strings(o, "tooltip");
        return r;
    }

    static Web.TierOption tierOption(final Map<String, Object> o) {
        final Web.TierOption t = new Web.TierOption();
        t.key = JsonTree.str(o, "key");
        t.label = JsonTree.str(o, "label");
        t.heat = JsonTree.boxed(o, "heat");
        t.durationMultiplier = JsonTree.boxed(o, "durationMultiplier");
        t.eutMultiplier = JsonTree.boxed(o, "eutMultiplier");
        t.outputMultiplier = JsonTree.boxed(o, "outputMultiplier");
        t.parallelMultiplier = JsonTree.boxed(o, "parallelMultiplier");
        t.parallelPerVoltageTier = JsonTree.boxed(o, "parallelPerVoltageTier");
        t.parallelVoltageBase = JsonTree.boxed(o, "parallelVoltageBase");
        t.resource = o.get("resource") instanceof Map ? resource(JsonTree.object(o.get("resource"))) : null;
        return t;
    }

    static Web.Numeric numeric(final Map<String, Object> o) {
        final Web.Numeric n = new Web.Numeric();
        n.min = JsonTree.num(o, "min");
        n.max = JsonTree.boxed(o, "max");
        n.step = JsonTree.boxed(o, "step");
        return n;
    }

    static Web.Control control(final Map<String, Object> o) {
        final Web.Control c = new Web.Control();
        c.id = JsonTree.str(o, "id");
        c.label = JsonTree.str(o, "label");
        c.minimumKey = JsonTree.str(o, "minimumKey");
        c.defaultKey = JsonTree.str(o, "defaultKey");
        c.numeric = o.get("numeric") instanceof Map ? numeric(JsonTree.object(o.get("numeric"))) : null;
        c.minimumFromSpecialValue = JsonTree.bool(o, "minimumFromSpecialValue");
        c.minimumHeatFromSpecialValue = JsonTree.bool(o, "minimumHeatFromSpecialValue");
        c.tiers = list(o, "tiers", WebJson::tierOption);
        return c;
    }

    static Web.Handler handler(final Map<String, Object> o) {
        final Web.Handler h = new Web.Handler();
        h.id = JsonTree.str(o, "id");
        h.label = JsonTree.str(o, "label");
        h.machineType = JsonTree.str(o, "machineType");
        h.minimumTier = JsonTree.str(o, "minimumTier");
        h.maximumTier = JsonTree.str(o, "maximumTier");
        h.availableTiers = strings(o, "availableTiers");
        h.durationTicks = JsonTree.boxed(o, "durationTicks");
        h.eut = JsonTree.boxed(o, "eut");
        h.maxParallel = JsonTree.boxed(o, "maxParallel");
        h.eutLimit = JsonTree.boxed(o, "eutLimit");
        h.perfectOverclock = JsonTree.bool(o, "perfectOverclock");
        h.kind = JsonTree.str(o, "kind");
        h.machineConfigControls = list(o, "machineConfigControls", WebJson::control);
        h.notes = JsonTree.str(o, "notes");
        return h;
    }

    private static Web.Profile profile(final Map<String, Object> o) {
        final Web.Profile p = new Web.Profile();
        p.machineType = JsonTree.str(o, "machineType");
        p.minimumTier = JsonTree.str(o, "minimumTier");
        p.maximumTier = JsonTree.str(o, "maximumTier");
        p.availableTiers = strings(o, "availableTiers");
        p.durationTicks = JsonTree.boxed(o, "durationTicks");
        p.eut = JsonTree.boxed(o, "eut");
        p.maxParallel = JsonTree.boxed(o, "maxParallel");
        p.eutLimit = JsonTree.boxed(o, "eutLimit");
        p.perfectOverclock = JsonTree.bool(o, "perfectOverclock");
        p.kind = JsonTree.str(o, "kind");
        p.notes = JsonTree.str(o, "notes");
        return p;
    }

    private static Web.Source source(final Map<String, Object> o) {
        final Web.Source s = new Web.Source();
        s.recipeMap = JsonTree.str(o, "recipeMap");
        s.rawRecipeId = JsonTree.str(o, "rawRecipeId");
        return s;
    }

    private static Web.Nei nei(final Map<String, Object> o) {
        final Web.Nei n = new Web.Nei();
        n.additionalInfo = strings(o, "additionalInfo");
        return n;
    }

    private static Web.RuntimeVariant runtimeVariant(final Map<String, Object> o) {
        final Web.RuntimeVariant v = new Web.RuntimeVariant();
        v.id = JsonTree.str(o, "id");
        v.label = JsonTree.str(o, "label");
        v.machineHandlerId = JsonTree.str(o, "machineHandlerId");
        v.overclockTier = JsonTree.str(o, "overclockTier");
        v.coilTier = JsonTree.str(o, "coilTier");
        v.machineConfigTiers = stringMap(o, "machineConfigTiers");
        v.durationTicks = JsonTree.num(o, "durationTicks");
        v.eut = JsonTree.num(o, "eut");
        v.parallel = JsonTree.boxed(o, "parallel");
        v.outputs = list(o, "outputs", WebJson::resource);
        return v;
    }

    private static Web.RuntimeCalculation runtimeCalculation(final Map<String, Object> o) {
        final Web.RuntimeCalculation c = new Web.RuntimeCalculation();
        c.status = JsonTree.str(o, "status");
        c.oracleEligible = JsonTree.bool(o, "oracleEligible");
        c.strict = JsonTree.bool(o, "strict");
        c.variants = list(o, "variants", WebJson::runtimeVariant);
        return c;
    }

    // ---- readers shared with the table binders

    /** A list of objects bound one by one, null when the field is not a list; an item that is not an object is null. */
    @Nullable
    static <T> List<T> list(final Map<String, Object> from, final String key,
        final Function<Map<String, Object>, T> bind) {
        if (!(from.get(key) instanceof List)) return null;
        final List<T> out = new ArrayList<>();
        for (final Object item : JsonTree.array(from.get(key)))
            out.add(item instanceof Map ? bind.apply(JsonTree.object(item)) : null);
        return out;
    }

    /** A list of strings, null when the field is not a list; an item that is not a string is null. */
    @Nullable
    static List<String> strings(final Map<String, Object> from, final String key) {
        if (!(from.get(key) instanceof List)) return null;
        final List<String> out = new ArrayList<>();
        for (final Object item : JsonTree.array(from.get(key))) out.add(item instanceof String ? (String) item : null);
        return out;
    }

    /** A map of strings, null when the field is not an object; a value that is not a string is left out. */
    @Nullable
    static Map<String, String> stringMap(final Map<String, Object> from, final String key) {
        if (!(from.get(key) instanceof Map)) return null;
        final Map<String, String> out = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> e : JsonTree.object(from.get(key))
            .entrySet()) if (e.getValue() instanceof String) out.put(e.getKey(), (String) e.getValue());
        return out;
    }

    /** A boolean field, false when absent or not a boolean. */
    static boolean flag(final Map<String, Object> from, final String key) {
        return Boolean.TRUE.equals(from.get(key));
    }
}
