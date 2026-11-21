package com.gtnhplanner.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.conformance.Conformance;
import com.gtnhplanner.core.base.JsonTree;
import com.gtnhplanner.core.power.PowerCardTier;
import com.gtnhplanner.core.power.PowerCatalog;
import com.gtnhplanner.core.power.TurbineFlow;

/**
 * conformance/power/power-goldens.jsonl.gz is what the website's power sources compute (see
 * src/lib/power/golden-records.ts for the record kinds): every source, its settings and labels, its model at its
 * defaults and at each setting moved alone, the Amps dial's tier, and the turbine flow check. The sources here must
 * give every one of them back, numbers to 1e-9 relative (V8 and HotSpot can differ by an ulp in pow and log).
 */
class PowerGoldensTest {

    private static final String FILE = "power/power-goldens.jsonl.gz";

    @Test
    void everyGoldenRecordIsReproduced() throws Exception {
        final List<String> diffs = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        int cases = 0;
        int turbine = 0;
        int tiers = 0;
        int lines = 0;
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new GZIPInputStream(Conformance.open(FILE)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                lines++;
                final Map<String, Object> record = JsonTree.object(JsonTree.parse(line));
                final List<String> found = new ArrayList<>();
                final String kind = JsonTree.str(record, "kind");
                String where;
                if ("cardTier".equals(kind)) {
                    tiers++;
                    where = "cardTier " + record.get("euPerTick");
                    cardTier(record, found);
                } else if ("turbineFlow".equals(kind)) {
                    turbine++;
                    where = "turbineFlow " + JsonTree.str(record, "source") + " " + record.get("settings") + " "
                        + record.get("running") + "/" + record.get("turbines");
                    turbineFlow(record, found);
                } else {
                    final String id = JsonTree.str(record, "source");
                    seen.add(id);
                    final PowerSource source = PowerCatalog.get(id);
                    if (!record.containsKey("case")) {
                        where = id + " describe";
                        if (source == null) found.add("source missing in Java");
                        else describe(source, record, found);
                    } else {
                        cases++;
                        where = id + " case " + record.get("case") + " " + record.get("settings");
                        if (source == null) found.add("source missing in Java");
                        else {
                            final Map<String, String> values = new LinkedHashMap<>();
                            for (final Map.Entry<String, Object> e : JsonTree.object(record.get("settings"))
                                .entrySet()) values.put(e.getKey(), (String) e.getValue());
                            try {
                                model(source.compute(values), JsonTree.object(record.get("model")), found);
                            } catch (final RuntimeException e) {
                                found.add("threw " + e);
                            }
                        }
                    }
                }
                for (final String d : found) diffs.add(where + ": " + d);
            }
        }
        for (final PowerSource source : PowerCatalog.sources()) {
            if (!seen.contains(source.id())) diffs.add(source.id() + ": only in Java");
        }
        System.out.println(
            "power goldens: " + lines + " records (" + cases + " cases, " + turbine + " turbine checks, " + tiers
                + " tiers), " + diffs.size() + " differ");
        assertTrue(cases > 1000 && turbine > 100 && tiers > 20, "the file is incomplete");
        assertEquals(List.of(), diffs.subList(0, Math.min(diffs.size(), 30)), diffs.size() + " differ");
    }

    private static void cardTier(final Map<String, Object> record, final List<String> diffs) {
        final Object eu = record.get("euPerTick");
        final double value = eu instanceof Double ? (Double) eu : toNumber(eu);
        same(diffs, "tier", PowerCardTier.of(value), record.get("tier"));
    }

    private static void turbineFlow(final Map<String, Object> record, final List<String> diffs) {
        final PowerSource source = PowerCatalog.get(JsonTree.str(record, "source"));
        final Map<String, String> settings = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> e : JsonTree.object(record.get("settings"))
            .entrySet()) settings.put(e.getKey(), (String) e.getValue());
        final double running = JsonTree.num(record, "running");
        final double turbines = JsonTree.num(record, "turbines");
        final Double pin = JsonTree.boxed(record, "pin");
        near(diffs, "turbinesFor", TurbineFlow.turbinesFor(running, pin), record.get("turbines"));
        if (Boolean.TRUE.equals(record.get("may")) != TurbineFlow.mayTurbineFlow(source, running, turbines)) {
            diffs.add("may: " + TurbineFlow.mayTurbineFlow(source, running, turbines));
        }
        final TurbineFlow.Check got = TurbineFlow.turbineFlowCheck(source, settings, running, turbines);
        final Object want = record.get("check");
        if (want == null) {
            if (got != null) diffs.add("check: " + got + " for none");
            return;
        }
        if (got == null) {
            diffs.add("check: none for " + want);
            return;
        }
        final Map<String, Object> w = JsonTree.object(want);
        near(diffs, "turbines", got.turbines(), w.get("turbines"));
        near(diffs, "flow", got.flow(), w.get("flow"));
        near(diffs, "setFlow", got.setFlow(), w.get("setFlow"));
        near(diffs, "optimal", got.optimal(), w.get("optimal"));
        same(diffs, "unit", got.unit(), w.get("unit"));
        near(diffs, "euMade", got.euMade(), w.get("euMade"));
        near(diffs, "euCounted", got.euCounted(), w.get("euCounted"));
    }

    private static void describe(final PowerSource s, final Map<String, Object> c, final List<String> diffs) {
        same(diffs, "name", s.name(), c.get("name"));
        same(diffs, "group", s.group().id, c.get("group"));
        same(diffs, "unlock", s.unlock(), c.get("unlock"));
        final List<Object> want = JsonTree.array(c.get("settings"));
        final List<String> wantIds = new ArrayList<>();
        final List<String> gotIds = new ArrayList<>();
        for (final Object e : want) wantIds.add(JsonTree.str(JsonTree.object(e), "id"));
        for (final PowerSetting p : s.settings()) gotIds.add(p.id());
        if (!wantIds.equals(gotIds)) diffs.add("settings: " + gotIds + " for " + wantIds);
        for (final Object e : want) {
            final Map<String, Object> w = JsonTree.object(e);
            final String sid = JsonTree.str(w, "id");
            final PowerSetting p = s.setting(sid);
            if (p == null) continue;
            final String pre = "setting " + sid + ".";
            same(diffs, pre + "label", p.label(), w.get("label"));
            final PowerSetting.Condition when = p.enabledWhen();
            final Map<String, Object> ww = w.get("enabledWhen") == null ? null : JsonTree.object(w.get("enabledWhen"));
            final String gw = when == null ? null : when.settingId() + "=" + when.equals();
            final String wanted = ww == null ? null : JsonTree.str(ww, "settingId") + "=" + JsonTree.str(ww, "equals");
            if (gw == null ? wanted != null : !gw.equals(wanted)) diffs.add(pre + "enabledWhen: " + gw + " for " + wanted);
            final String type = JsonTree.str(w, "type");
            if (p instanceof final PowerSetting.Select sel) {
                if (!"select".equals(type)) {
                    diffs.add(pre + "type: select for " + type);
                    continue;
                }
                same(diffs, pre + "defaultKey", sel.defaultKey(), w.get("defaultKey"));
                final List<String> keys = new ArrayList<>();
                final List<String> labels = new ArrayList<>();
                for (final PowerSetting.Option o : sel.options()) {
                    keys.add(o.key());
                    labels.add(o.label());
                }
                final List<String> wk = strings(w.get("options"));
                final List<String> wl = strings(w.get("optionLabels"));
                if (!keys.equals(wk)) diffs.add(pre + "options: " + keys + " for " + wk);
                if (!labels.equals(wl)) diffs.add(pre + "optionLabels: " + labels + " for " + wl);
                final Map<String, String> legacy = new LinkedHashMap<>();
                for (final Map.Entry<String, Object> l : JsonTree.object(w.get("legacyKeys"))
                    .entrySet()) legacy.put(l.getKey(), (String) l.getValue());
                if (!legacy.equals(sel.legacyKeys())) diffs.add(pre + "legacyKeys: " + sel.legacyKeys() + " for " + legacy);
            } else if (p instanceof final PowerSetting.Number n) {
                if (!"number".equals(type)) {
                    diffs.add(pre + "type: number for " + type);
                    continue;
                }
                near(diffs, pre + "min", n.min(), w.get("min"));
                near(diffs, pre + "max", n.max(), w.get("max"));
                near(diffs, pre + "step", n.step(), w.get("step"));
                near(diffs, pre + "defaultValue", n.defaultValue(), w.get("defaultValue"));
            } else if (p instanceof final PowerSetting.Toggle t) {
                if (!"toggle".equals(type)) {
                    diffs.add(pre + "type: toggle for " + type);
                    continue;
                }
                if (t.defaultOn() != Boolean.TRUE.equals(w.get("defaultOn"))) diffs.add(pre + "defaultOn: " + t.defaultOn());
            }
        }
    }

    private static List<String> strings(final Object value) {
        final List<String> out = new ArrayList<>();
        for (final Object o : JsonTree.array(value)) out.add((String) o);
        return out;
    }

    private static void model(final PowerModel m, final Map<String, Object> w, final List<String> diffs) {
        near(diffs, "euPerTick", m.euPerTick(), w.get("euPerTick"));
        same(diffs, "cardTier", PowerCardTier.of(m.euPerTick()), w.get("cardTier"));
        flows(diffs, "inputs", m.inputs(), JsonTree.array(w.get("inputs")));
        flows(diffs, "outputs", m.outputs(), JsonTree.array(w.get("outputs")));
        final List<String> got = new ArrayList<>();
        final List<String> want = new ArrayList<>();
        for (final PowerModel.Stat s : m.stats()) got.add(s.label() + "=" + s.value());
        for (final Object e : JsonTree.array(w.get("stats"))) {
            final Map<String, Object> o = JsonTree.object(e);
            want.add(JsonTree.str(o, "label") + "=" + JsonTree.str(o, "value"));
        }
        for (int i = 0; i < Math.max(got.size(), want.size()); i++) {
            final String g = i < got.size() ? got.get(i) : null;
            final String x = i < want.size() ? want.get(i) : null;
            if (g == null ? x != null : !g.equals(x)) diffs.add("stats[" + i + "]: " + g + " for " + x);
        }
        final List<String> wg = new ArrayList<>(m.warnings());
        final List<String> ww = strings(w.get("warnings"));
        if (!wg.equals(ww)) diffs.add("warnings: " + wg + " for " + ww);
    }

    private static void flows(final List<String> diffs, final String f, final List<PowerModel.Flow> got, final List<Object> want) {
        if (got.size() != want.size()) {
            final List<String> g = new ArrayList<>();
            final List<String> w = new ArrayList<>();
            for (final PowerModel.Flow x : got) g.add(x.name());
            for (final Object e : want) w.add(JsonTree.str(JsonTree.object(e), "name"));
            diffs.add(f + ": " + g + " for " + w);
            return;
        }
        for (int i = 0; i < got.size(); i++) {
            final Map<String, Object> w = JsonTree.object(want.get(i));
            final PowerModel.Flow g = got.get(i);
            same(diffs, f + "[" + i + "].name", g.name(), w.get("name"));
            same(diffs, f + "[" + i + "].unit", g.unit() == PowerModel.Unit.L ? "L" : "item", w.get("unit"));
            near(diffs, f + "[" + i + "].perSecond(" + g.name() + ")", g.perSecond(), w.get("perSecond"));
            final List<String> ga = new ArrayList<>();
            final List<String> wa = new ArrayList<>();
            for (final PowerModel.Alternative a : g.alternatives()) ga.add(a.kind() + "|" + a.id() + "|" + a.displayName());
            for (final Object e : JsonTree.array(w.get("alternatives"))) {
                final Map<String, Object> o = JsonTree.object(e);
                wa.add(JsonTree.str(o, "kind") + "|" + JsonTree.str(o, "id") + "|" + JsonTree.str(o, "displayName"));
            }
            if (!ga.equals(wa)) diffs.add(f + "[" + i + "].alternatives: " + ga + " for " + wa);
        }
    }

    private static void same(final List<String> diffs, final String f, final String got, final Object want) {
        final String w = want == null ? null : want.toString();
        if (got == null ? w != null : !got.equals(w)) diffs.add(f + ": " + got + " for " + w);
    }

    private static double toNumber(final Object want) {
        if (want instanceof Double) return (Double) want;
        final String s = String.valueOf(want);
        return s.equals("Infinity") ? Double.POSITIVE_INFINITY : s.equals("-Infinity") ? Double.NEGATIVE_INFINITY : Double.NaN;
    }

    private static void near(final List<String> diffs, final String f, final double got, final Object want) {
        if (want == null) {
            diffs.add(f + ": " + got + " for nothing");
            return;
        }
        final double w = toNumber(want);
        if (Double.isNaN(w) && Double.isNaN(got)) return;
        if (w == got) return;
        if (Math.abs(w - got) <= 1e-9 * Math.max(Math.abs(w), Math.abs(got))) return;
        diffs.add(f + ": " + got + " for " + w);
    }
}
