package com.gtnhplanner.web.exports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.teavm.jso.JSExport;

import com.gtnhplanner.core.power.PowerCardTier;
import com.gtnhplanner.core.power.PowerCatalog;
import com.gtnhplanner.core.power.TurbineFlow;
import com.gtnhplanner.power.PowerModel;
import com.gtnhplanner.power.PowerSetting;
import com.gtnhplanner.power.PowerSource;
import com.gtnhplanner.web.json.Json;

/**
 * core-power for JavaScript: every generator source, its settings, its model and the two helpers the card shell uses.
 * Structured values cross as JSON text in the shapes conformance/power/power-goldens.jsonl.gz records (see
 * src/lib/power/golden-records.ts): a source's description, a model, a turbine check. The three data tables the
 * sources read are inside the module (web/build/CoreResources), so there is nothing to load first.
 */
public final class PowerExports {

    private PowerExports() {}

    /** The source ids in the registry's order, as a JSON array. */
    @JSExport
    public static String powerSourceIds() {
        final List<Object> ids = new ArrayList<>();
        for (final PowerSource source : PowerCatalog.sources()) ids.add(source.id());
        return Json.write(ids);
    }

    /** One source's name, group, unlock tier and settings (the golden "describe" record), or null for an unknown id. */
    @JSExport
    @Nullable
    public static String powerDescribe(final String sourceId) {
        final PowerSource source = PowerCatalog.get(sourceId);
        if (source == null) return null;
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", source.id());
        out.put("name", source.name());
        out.put("group", source.group().id);
        out.put("unlock", source.unlock());
        final List<Object> settings = new ArrayList<>();
        for (final PowerSetting setting : source.settings()) settings.add(describe(setting));
        out.put("settings", settings);
        return Json.write(out);
    }

    /**
     * The model of a source at stored settings ({"id":"value"} as a plan keeps them; missing ones read as defaults),
     * or null for an unknown source.
     */
    @JSExport
    @Nullable
    public static String powerCompute(final String sourceId, final String settingsJson) {
        final PowerSource source = PowerCatalog.get(sourceId);
        if (source == null) return null;
        return Json.write(model(source.compute(stored(settingsJson))));
    }

    /**
     * The turbine helpers for a card: input {"settings":{...},"running":n,"pin":n|null}; output
     * {"turbines":n,"may":bool,"check":null|{turbines,flow,setFlow,optimal,unit,euMade,euCounted}}. Null for an
     * unknown source.
     */
    @JSExport
    @Nullable
    public static String powerTurbineFlow(final String sourceId, final String inputJson) {
        final PowerSource source = PowerCatalog.get(sourceId);
        if (source == null) return null;
        final Map<String, Object> in = Json.object(Json.parse(inputJson));
        final double running = Json.num(in, "running", 0);
        final Double pin = Json.boxed(in, "pin");
        final double turbines = TurbineFlow.turbinesFor(running, pin);
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("turbines", turbines);
        out.put("may", TurbineFlow.mayTurbineFlow(source, running, turbines));
        final TurbineFlow.Check check = TurbineFlow.turbineFlowCheck(source, stored(in.get("settings")), running, turbines);
        if (check == null) out.put("check", null);
        else {
            final Map<String, Object> c = new LinkedHashMap<>();
            c.put("turbines", check.turbines());
            c.put("flow", check.flow());
            c.put("setFlow", check.setFlow());
            c.put("optimal", check.optimal());
            c.put("unit", check.unit());
            c.put("euMade", check.euMade());
            c.put("euCounted", check.euCounted());
            out.put("check", c);
        }
        return Json.write(out);
    }

    /** The Amps dial's tier label for an EU/t figure, or null when the figure is not a positive finite number. */
    @JSExport
    @Nullable
    public static String powerCardTier(final double euPerTick) {
        return PowerCardTier.of(euPerTick);
    }

    // ---- shapes

    private static Map<String, String> stored(final String settingsJson) {
        return stored(settingsJson == null ? null : Json.parse(settingsJson));
    }

    private static Map<String, String> stored(final Object settings) {
        final Map<String, String> out = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> e : Json.object(settings).entrySet()) {
            if (e.getValue() instanceof String) out.put(e.getKey(), (String) e.getValue());
        }
        return out;
    }

    private static Map<String, Object> describe(final PowerSetting setting) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", setting.id());
        out.put("label", setting.label());
        if (setting.enabledWhen() != null) {
            final Map<String, Object> when = new LinkedHashMap<>();
            when.put("settingId", setting.enabledWhen().settingId());
            when.put("equals", setting.enabledWhen().equals());
            out.put("enabledWhen", when);
        }
        if (setting instanceof final PowerSetting.Select select) {
            out.put("type", "select");
            out.put("defaultKey", select.defaultKey());
            final List<Object> keys = new ArrayList<>();
            final List<Object> labels = new ArrayList<>();
            for (final PowerSetting.Option option : select.options()) {
                keys.add(option.key());
                labels.add(option.label());
            }
            out.put("options", keys);
            out.put("optionLabels", labels);
            // The website leaves the field out when a select has no retired keys.
            if (!select.legacyKeys().isEmpty()) out.put("legacyKeys", new LinkedHashMap<String, Object>(select.legacyKeys()));
        } else if (setting instanceof final PowerSetting.Number number) {
            out.put("type", "number");
            out.put("defaultValue", number.defaultValue());
            out.put("min", number.min());
            out.put("max", number.max());
            out.put("step", number.step());
        } else if (setting instanceof final PowerSetting.Toggle toggle) {
            out.put("type", "toggle");
            out.put("defaultOn", toggle.defaultOn());
        }
        return out;
    }

    private static Map<String, Object> model(final PowerModel m) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("euPerTick", m.euPerTick());
        out.put("cardTier", PowerCardTier.of(m.euPerTick()));
        out.put("inputs", flows(m.inputs()));
        out.put("outputs", flows(m.outputs()));
        final List<Object> stats = new ArrayList<>();
        for (final PowerModel.Stat s : m.stats()) {
            final Map<String, Object> o = new LinkedHashMap<>();
            o.put("label", s.label());
            o.put("value", s.value());
            stats.add(o);
        }
        out.put("stats", stats);
        out.put("warnings", new ArrayList<Object>(m.warnings()));
        return out;
    }

    private static List<Object> flows(final List<PowerModel.Flow> flows) {
        final List<Object> out = new ArrayList<>();
        for (final PowerModel.Flow f : flows) {
            final Map<String, Object> o = new LinkedHashMap<>();
            o.put("name", f.name());
            o.put("perSecond", f.perSecond());
            o.put("unit", f.unit() == PowerModel.Unit.L ? "L" : "item");
            final List<Object> alternatives = new ArrayList<>();
            for (final PowerModel.Alternative a : f.alternatives()) {
                final Map<String, Object> x = new LinkedHashMap<>();
                x.put("kind", a.kind());
                x.put("id", a.id());
                x.put("displayName", a.displayName());
                alternatives.add(x);
            }
            o.put("alternatives", alternatives);
            out.add(o);
        }
        return out;
    }
}
