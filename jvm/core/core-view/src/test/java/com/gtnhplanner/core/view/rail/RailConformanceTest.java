package com.gtnhplanner.core.view.rail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.conformance.Conformance;

/**
 * The Java rail and notices against the website's: the input records and the views the website's own code made of them
 * (conformance/view, tools/conformance/view/generate.ts). The same input must give the same view, number for number.
 * The files are the test; nothing here restates a rule.
 */
class RailConformanceTest {

    private static JsonElement read(final String name) throws IOException {
        try (InputStream in = Conformance.open("view/" + name)) {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            in.transferTo(bytes);
            return new JsonParser().parse(bytes.toString(StandardCharsets.UTF_8));
        }
    }

    // region Reading the input records

    @Nullable
    private static JsonElement opt(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e;
    }

    @Nullable
    private static Double optNum(final JsonObject o, final String key) {
        final JsonElement e = opt(o, key);
        return e == null ? null : e.getAsDouble();
    }

    @Nullable
    private static String optStr(final JsonObject o, final String key) {
        final JsonElement e = opt(o, key);
        return e == null ? null : e.getAsString();
    }

    private static List<String> strings(final JsonElement e) {
        final List<String> out = new ArrayList<>();
        for (final JsonElement s : e.getAsJsonArray()) out.add(s.getAsString());
        return out;
    }

    private static Map<String, String> stringMap(final JsonObject o) {
        final Map<String, String> out = new LinkedHashMap<>();
        for (final Map.Entry<String, JsonElement> e : o.entrySet()) out.put(e.getKey(), e.getValue().getAsString());
        return out;
    }

    static RailInput railInput(final JsonObject o) {
        final List<RailInput.Flow> flows = new ArrayList<>();
        for (final JsonElement f : o.getAsJsonArray("flows")) {
            final JsonObject j = f.getAsJsonObject();
            final List<RailInput.Port> ports = new ArrayList<>();
            for (final JsonElement p : j.getAsJsonArray("ports")) {
                final JsonObject q = p.getAsJsonObject();
                ports.add(new RailInput.Port(q.get("output").getAsBoolean(), q.get("key").getAsString(),
                    q.get("kind").getAsString(), q.get("id").getAsString(), q.get("label").getAsString(),
                    q.get("perSecond").getAsDouble(), q.get("handle").getAsString(), q.get("wired").getAsBoolean(),
                    q.get("set").getAsDouble()));
            }
            flows.add(new RailInput.Flow(j.get("card").getAsString(), j.get("machine").getAsString(),
                optNum(j, "machines"), optNum(j, "utilization"), ports));
        }
        final List<RailInput.DrawerIn> drawers = new ArrayList<>();
        for (final JsonElement d : o.getAsJsonArray("drawers")) {
            final JsonObject j = d.getAsJsonObject();
            drawers.add(new RailInput.DrawerIn(j.get("id").getAsString(), j.get("kind").getAsString(),
                j.get("resourceId").getAsString(), j.get("label").getAsString(), j.get("role").getAsString(),
                j.get("rule").getAsString(), j.get("set").getAsBoolean(), j.get("rate").getAsDouble(),
                j.get("unmet").getAsBoolean(), j.get("eaten").getAsDouble()));
        }
        final List<RailInput.MachineIn> machines = new ArrayList<>();
        for (final JsonElement m : o.getAsJsonArray("machines")) {
            final JsonObject j = m.getAsJsonObject();
            RailInput.UpToIn upTo = null;
            final JsonElement u = opt(j, "upTo");
            if (u != null) {
                final JsonObject uj = u.getAsJsonObject();
                upTo = new RailInput.UpToIn(optStr(uj, "reason"), uj.get("reached").getAsDouble(),
                    uj.get("capacity").getAsDouble());
            }
            final JsonElement amps = opt(j, "amps");
            machines.add(new RailInput.MachineIn(j.get("nodeId").getAsString(), j.get("label").getAsString(),
                j.get("count").getAsDouble(), j.get("tank").getAsBoolean(), j.get("pinned").getAsBoolean(),
                j.get("rule").getAsString(), optNum(j, "pin"), upTo, optStr(j, "tier"),
                amps == null ? null : amps.getAsInt(), j.get("multiblock").getAsBoolean(), optStr(j, "madeTier"),
                j.get("state").getAsString(), optNum(j, "euT"), optNum(j, "avgEuT"), optNum(j, "steamLs"),
                optNum(j, "avgSteamLs"), optNum(j, "madeEuT"), optNum(j, "avgMadeEuT")));
        }
        final List<RailInput.SpareIn> spares = new ArrayList<>();
        for (final JsonElement s : o.getAsJsonArray("spares")) {
            final JsonObject j = s.getAsJsonObject();
            Map<String, Double> stored = null;
            final JsonElement st = opt(j, "stored");
            if (st != null) {
                stored = new LinkedHashMap<>();
                for (final Map.Entry<String, JsonElement> e : st.getAsJsonObject()
                    .entrySet()) stored.put(e.getKey(), e.getValue().getAsDouble());
            }
            final List<RailInput.BearerIn> bearers = new ArrayList<>();
            for (final JsonElement b : j.getAsJsonArray("bearers")) {
                final JsonObject bj = b.getAsJsonObject();
                final List<RailInput.PortRef> refs = new ArrayList<>();
                for (final JsonElement r : bj.getAsJsonArray("ports")) {
                    refs.add(new RailInput.PortRef(r.getAsJsonObject().get("nodeId").getAsString(),
                        r.getAsJsonObject().get("handle").getAsString()));
                }
                bearers.add(new RailInput.BearerIn(bj.get("key").getAsString(), bj.get("nodeId").getAsString(),
                    bj.get("handle").getAsString(), bj.get("output").getAsBoolean(), bj.get("name").getAsString(),
                    bj.get("machine").getAsString(), bj.get("resource").getAsString(), bj.get("max").getAsDouble(),
                    bj.get("solverPick").getAsBoolean(), bj.get("energy").getAsBoolean(),
                    bj.get("share").getAsDouble(), refs));
            }
            spares.add(new RailInput.SpareIn(j.get("key").getAsString(), j.get("importing").getAsBoolean(),
                j.get("custom").getAsBoolean(), stored, bearers));
        }
        final List<RailInput.Wire> wires = new ArrayList<>();
        for (final JsonElement w : o.getAsJsonArray("wires")) {
            wires.add(new RailInput.Wire(w.getAsJsonObject().get("from").getAsString(),
                w.getAsJsonObject().get("to").getAsString()));
        }
        final JsonObject v = o.getAsJsonObject("view");
        return new RailInput(o.get("answered").getAsBoolean(), stringMap(o.getAsJsonObject("aliases")), flows, drawers,
            machines, stringMap(o.getAsJsonObject("names")), spares, strings(o.get("cards")), wires,
            new RailInput.ViewIn(v.get("filter").getAsString(), v.get("peak").getAsBoolean(),
                v.get("power").getAsString(), v.has("keepOpen") && v.get("keepOpen").getAsBoolean()));
    }

    static Notices.Input noticeInput(final JsonObject o) {
        Notices.Failure failure = null;
        final JsonElement f = opt(o, "failure");
        if (f != null) {
            final JsonObject j = f.getAsJsonObject();
            failure = new Notices.Failure(j.get("kind").getAsString(), strings(j.get("names")),
                strings(j.get("focus")));
        }
        final List<Notices.Loop> loops = new ArrayList<>();
        for (final JsonElement l : o.getAsJsonArray("loops")) {
            loops.add(new Notices.Loop(l.getAsJsonObject().get("kind").getAsString(),
                strings(l.getAsJsonObject().get("cards"))));
        }
        final List<Notices.Note> notes = new ArrayList<>();
        for (final JsonElement n : o.getAsJsonArray("notes")) {
            final JsonObject j = n.getAsJsonObject();
            final List<Notices.Holder> holders = new ArrayList<>();
            for (final JsonElement h : j.getAsJsonArray("holders")) {
                holders.add(new Notices.Holder(h.getAsJsonObject().get("kind").getAsString(),
                    h.getAsJsonObject().get("name").getAsString()));
            }
            notes.add(new Notices.Note(j.get("type").getAsString(), j.get("id").getAsString(),
                j.get("label").getAsString(), j.get("resource").getAsString(), j.get("supply").getAsBoolean(),
                j.get("exact").getAsBoolean(), j.get("asked").getAsDouble(), j.get("reached").getAsDouble(),
                j.get("made").getAsDouble(), j.get("target").getAsDouble(), j.get("hasShortfall").getAsBoolean(),
                holders, j.get("subject").getAsString(), strings(j.get("focus"))));
        }
        return new Notices.Input(failure, o.get("hasNodes").getAsBoolean(), loops, notes);
    }

    // endregion

    @TestFactory
    Stream<DynamicTest> railViewsMatchTheWebsite() throws IOException {
        final JsonArray cases = read("rail-cases.json").getAsJsonArray();
        assertTrue(cases.size() >= 40, "the rail cases are all there");
        final List<DynamicTest> tests = new ArrayList<>();
        for (final JsonElement c : cases) {
            final JsonObject j = c.getAsJsonObject();
            tests.add(DynamicTest.dynamicTest(j.get("name").getAsString(), () -> {
                final RailView made = RailBuilder.build(railInput(j.getAsJsonObject("input")));
                assertEquals(j.get("view"), new JsonParser().parse(made.toJson()));
            }));
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> noticesMatchTheWebsite() throws IOException {
        final JsonArray cases = read("notice-cases.json").getAsJsonArray();
        assertTrue(cases.size() >= 25, "the notice cases are all there");
        final List<DynamicTest> tests = new ArrayList<>();
        for (final JsonElement c : cases) {
            final JsonObject j = c.getAsJsonObject();
            tests.add(DynamicTest.dynamicTest(j.get("name").getAsString(), () -> {
                final List<Notices.Notice> made = Notices.build(noticeInput(j.getAsJsonObject("input")));
                assertEquals(j.get("notices"), new JsonParser().parse(Notices.toJson(made)));
            }));
        }
        return tests.stream();
    }

    // region Arithmetic

    @Nullable
    private static Double numOrNull(final JsonObject o, final String key) {
        return optNum(o, key);
    }

    private static double[] doubles(final JsonElement e) {
        final JsonArray a = e.getAsJsonArray();
        final double[] out = new double[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsDouble();
        return out;
    }

    private static void assertSame(final double[] expected, final double[] actual, final String what) {
        assertEquals(expected.length, actual.length, what);
        for (int i = 0; i < expected.length; i++) assertEquals(expected[i], actual[i], 0.0, what + " [" + i + "]");
    }

    @Test
    void rateBoxAndShareArithmeticMatches() throws IOException {
        final JsonObject m = read("rail-math.json").getAsJsonObject();
        for (final JsonElement e : m.getAsJsonArray("parseRateInput")) {
            final JsonObject j = e.getAsJsonObject();
            final Double want = numOrNull(j, "value");
            final double got = RailMath.parseRateInput(j.get("text").getAsString());
            if (want == null) assertTrue(Double.isNaN(got), "'" + j.get("text").getAsString() + "' is not a number");
            else assertEquals(want, got, 0.0, "'" + j.get("text").getAsString() + "'");
        }
        for (final JsonElement e : m.getAsJsonArray("nudged")) {
            final JsonObject j = e.getAsJsonObject();
            assertSame(doubles(j.get("out")),
                RailMath.nudged(doubles(j.get("shares")), j.get("index").getAsInt(), j.get("step").getAsDouble()),
                "nudged " + j);
        }
        for (final JsonElement e : m.getAsJsonArray("evenShares")) {
            final JsonObject j = e.getAsJsonObject();
            assertSame(doubles(j.get("out")), RailMath.evenShares(j.get("count").getAsInt()), "evenShares " + j);
        }
        for (final JsonElement e : m.getAsJsonArray("allAt")) {
            final JsonObject j = e.getAsJsonObject();
            assertSame(doubles(j.get("out")), RailMath.allAt(j.get("count").getAsInt(), j.get("index").getAsInt()),
                "allAt " + j);
        }
        for (final JsonElement e : m.getAsJsonArray("shareAtX")) {
            final JsonObject j = e.getAsJsonObject();
            assertEquals(j.get("out").getAsDouble(),
                RailMath.shareAtX(j.get("x").getAsDouble(), j.get("left").getAsDouble(), j.get("width").getAsDouble()),
                0.0, "shareAtX " + j);
        }
        for (final JsonElement e : m.getAsJsonArray("sameShares")) {
            final JsonObject j = e.getAsJsonObject();
            assertEquals(j.get("out").getAsBoolean(), RailMath.sameShares(doubles(j.get("a")), doubles(j.get("b"))),
                "sameShares " + j);
        }
        for (final JsonElement e : m.getAsJsonArray("wheelStep")) {
            final JsonObject j = e.getAsJsonObject();
            assertEquals(j.get("out").getAsInt(),
                RailMath.wheelStep(j.get("ctrl").getAsBoolean(), j.get("shift").getAsBoolean()));
        }
        for (final JsonElement e : m.getAsJsonArray("steppedRate")) {
            final JsonObject j = e.getAsJsonObject();
            assertEquals(j.get("out").getAsDouble(),
                RailMath.steppedRate(j.get("shown").getAsDouble(), j.get("direction").getAsInt(),
                    j.get("ctrl").getAsBoolean(), j.get("shift").getAsBoolean()),
                0.0, "steppedRate " + j);
        }
    }

    @Test
    void peakCountAndPowerDialMatch() throws IOException {
        final JsonObject m = read("rail-math.json").getAsJsonObject();
        for (final JsonElement e : m.getAsJsonArray("peakCount")) {
            final JsonObject j = e.getAsJsonObject();
            assertEquals(j.get("out").getAsDouble(),
                RailMath.peakCount(j.get("solved").getAsDouble(), numOrNull(j, "set"),
                    j.get("keepSolved").getAsBoolean()),
                0.0, "peakCount " + j);
        }
        for (final JsonElement e : m.getAsJsonArray("dial")) {
            final JsonObject j = e.getAsJsonObject();
            final String unit = j.get("unit").getAsString();
            final String tier = optStr(j, "ownTier");
            assertEquals(numOrNull(j, "divisor"), RailMath.divisor(unit, tier), "divisor " + j);
            assertEquals(j.get("value").getAsDouble(), RailMath.dialValue(j.get("euT").getAsDouble(), unit, tier),
                0.0, "value " + j);
            assertEquals(j.get("suffix").getAsString(), RailMath.dialSuffix(unit, tier), "suffix " + j);
        }
    }

    // endregion
}
