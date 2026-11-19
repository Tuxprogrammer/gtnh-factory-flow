package com.gtnhplanner.core.layout.arrange;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhplanner.core.layout.GridPoint;
import com.gtnhplanner.core.layout.RouterConformanceAccess;
import com.gtnhplanner.core.layout.RouterTuning;

/**
 * The Arrange's parts against the website's, call by call (conformance/layout/arrange.json.gz): the optimiser's search
 * with its stand-in judge ({@code optimizes}), the free placement ({@code frees}) and the proxy score
 * ({@code proxies}), each as the website's arrange called it. The same inputs must give the same numbers out, to the
 * bit.
 */
class ArrangePartsConformanceTest {

    // region Reading the website's JSON

    private static Double num(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsDouble();
    }

    private static String str(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static List<Optimize.OptimizeCard> optimizeCards(final JsonArray a) {
        final List<Optimize.OptimizeCard> out = new ArrayList<>();
        for (final JsonElement e : a) {
            final JsonObject o = e.getAsJsonObject();
            final JsonElement s = o.get("satellite");
            Optimize.Satellite satellite = null;
            if (s != null && !s.isJsonNull()) {
                final JsonObject so = s.getAsJsonObject();
                satellite = new Optimize.Satellite(str(so, "anchorId"), "left".equals(str(so, "side")));
            }
            out.add(
                new Optimize.OptimizeCard(
                    str(o, "id"),
                    num(o, "width"),
                    num(o, "height"),
                    "storage".equals(str(o, "role")),
                    num(o, "layer"),
                    num(o, "seq"),
                    num(o, "section"),
                    satellite));
        }
        return out;
    }

    private static List<Optimize.OptimizeWire> optimizeWires(final JsonArray a) {
        final List<Optimize.OptimizeWire> out = new ArrayList<>();
        for (final JsonElement e : a) {
            final JsonObject o = e.getAsJsonObject();
            out.add(new Optimize.OptimizeWire(str(o, "source"), str(o, "target"), num(o, "weight"), num(o, "width")));
        }
        return out;
    }

    private static RouterTuning tuning(final JsonElement e) {
        return e == null || e.isJsonNull() ? null : RouterConformanceAccess.tuning(e);
    }

    /** The air term the website built from these inputs (makeAirTerm), or none. */
    private static ToDoubleFunction<Map<String, GridPoint>> air(final JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        final JsonObject o = e.getAsJsonObject();
        // The air's cards carry no position: id, size and role only.
        final List<ArrangeCard> cards = new ArrayList<>();
        for (final JsonElement c : o.getAsJsonArray("cards")) {
            final JsonObject co = c.getAsJsonObject();
            cards.add(
                new ArrangeCard(
                    str(co, "id"),
                    0,
                    0,
                    num(co, "width"),
                    num(co, "height"),
                    "storage".equals(str(co, "role"))));
        }
        return Air.makeAirTerm(cards, ArrangeConformanceTest.wires(o.getAsJsonArray("wires")), num(o, "perPixel"));
    }

    private static Map<String, GridPoint> pairs(final JsonArray a) {
        final Map<String, GridPoint> out = new LinkedHashMap<>();
        for (final JsonElement e : a) {
            final JsonArray pair = e.getAsJsonArray();
            out.put(
                pair.get(0)
                    .getAsString(),
                ArrangeConformanceTest.point(pair.get(1)));
        }
        return out;
    }

    private static List<ArrangeCard> sizes(final JsonArray a) {
        final List<ArrangeCard> out = new ArrayList<>();
        for (final JsonElement e : a) {
            final JsonObject o = e.getAsJsonObject();
            out.add(new ArrangeCard(str(o, "id"), 0, 0, num(o, "width"), num(o, "height"), false));
        }
        return out;
    }

    // endregion

    // region Comparing

    private final List<String> differences = new ArrayList<>();

    private void differ(final String where, final Object want, final Object got) {
        differences.add(where + ": website " + want + ", java " + got);
    }

    private void same(final String where, final Double want, final Double got) {
        if (want == null || got == null) {
            if (want != got) differ(where, want, got);
            return;
        }
        final double w = want, g = got;
        if (w != g && !(Double.isNaN(w) && Double.isNaN(g))) differ(where, want, got);
    }

    private void samePoints(final String where, final List<GridPoint> want, final List<GridPoint> got) {
        if (want.size() != got.size()) {
            differ(where + " count", want.size(), got.size());
            return;
        }
        for (int i = 0; i < want.size(); i++) {
            if (want.get(i)
                .x() != got.get(i)
                    .x()
                || want.get(i)
                    .y() != got.get(i)
                        .y())
                differ(where + " " + i, want.get(i), got.get(i));
        }
    }

    private void finish(final String what) {
        if (differences.isEmpty()) return;
        final StringBuilder s = new StringBuilder(what + ": " + differences.size() + " differences; the first ones:\n");
        for (int i = 0; i < Math.min(20, differences.size()); i++) s.append("  ")
            .append(differences.get(i))
            .append('\n');
        fail(s.toString());
    }

    // endregion

    @TestFactory
    List<DynamicTest> theOptimiserSearchesAsTheWebsiteDoes() throws IOException {
        final JsonArray calls = ArrangeConformanceTest.read()
            .getAsJsonArray("optimizes");
        assertTrue(calls.size() > 0);
        final List<DynamicTest> out = new ArrayList<>();
        int n = 0;
        for (final JsonElement e : calls) {
            final JsonObject c = e.getAsJsonObject();
            final String where = "optimize " + n++ + " (" + str(c, "name") + ")";
            out.add(DynamicTest.dynamicTest(where, () -> {
                differences.clear();
                final JsonObject o = c.getAsJsonObject("options");
                final Optimize.OptimizeOptions options = new Optimize.OptimizeOptions(
                    num(o, "rowGapCells"),
                    num(o, "sectionGapCells"),
                    num(o, "columnGapCells"),
                    num(o, "satellitePadCells"),
                    num(o, "trials"),
                    false,
                    null,
                    tuning(o.get("prices")),
                    air(c.get("air")),
                    null);
                final Optimize.OptimizeResult got = Optimize.optimizeIslandLayout(
                    optimizeCards(c.getAsJsonArray("cards")),
                    optimizeWires(c.getAsJsonArray("wires")),
                    options);
                final JsonObject want = c.getAsJsonObject("result");
                final List<GridPoint> positions = new ArrayList<>();
                for (final JsonElement p : want.getAsJsonArray("positions"))
                    positions.add(ArrangeConformanceTest.point(p));
                samePoints(where + " position", positions, got.positions());
                same(where + " before", num(want, "before"), got.before());
                same(where + " after", num(want, "after"), got.after());
                same(where + " points", num(want, "points"), got.points());
                final JsonElement finalists = want.get("finalists");
                if (finalists == null || finalists.isJsonNull()) {
                    if (got.finalists() != null) differ(where + " finalists", null, got.finalists());
                } else if (got.finalists() == null || finalists.getAsJsonArray()
                    .size()
                    != got.finalists()
                        .size()) {
                    differ(where + " finalists", finalists, got.finalists());
                } else {
                    for (int i = 0; i < finalists.getAsJsonArray()
                        .size(); i++) {
                        final JsonObject f = finalists.getAsJsonArray()
                            .get(i)
                            .getAsJsonObject();
                        final Optimize.Finalist g = got.finalists()
                            .get(i);
                        same(where + " finalist " + i + " score", num(f, "score"), g.score());
                        same(where + " finalist " + i + " proxyCrossings", num(f, "proxyCrossings"), g.proxyCrossings());
                        same(where + " finalist " + i + " points", num(f, "points"), g.points());
                    }
                }
                finish(where);
            }));
        }
        return out;
    }

    @TestFactory
    List<DynamicTest> theFreePlacementPlacesAsTheWebsiteDoes() throws IOException {
        final JsonArray calls = ArrangeConformanceTest.read()
            .getAsJsonArray("frees");
        assertTrue(calls.size() > 0);
        final List<DynamicTest> out = new ArrayList<>();
        int n = 0;
        for (final JsonElement e : calls) {
            final JsonObject c = e.getAsJsonObject();
            final String where = "free " + n++ + " (" + str(c, "name") + ")";
            out.add(DynamicTest.dynamicTest(where, () -> {
                differences.clear();
                final JsonObject o = c.getAsJsonObject("options");
                final FreeArrange.FreeResult got = FreeArrange.arrangeFree(
                    ArrangeConformanceTest.cards(c.getAsJsonArray("cards")),
                    ArrangeConformanceTest.wires(c.getAsJsonArray("wires")),
                    new FreeArrange.FreeOptions(
                        tuning(o.get("prices")),
                        num(o, "trials"),
                        num(o, "gapCells"),
                        num(o, "besideCells"),
                        null));
                final JsonObject want = c.getAsJsonObject("result");
                final Map<String, GridPoint> positions = pairs(want.getAsJsonArray("positions"));
                if (!new ArrayList<>(positions.keySet()).equals(
                    new ArrayList<>(
                        got.positions()
                            .keySet())))
                    differ(where + " ids in order", positions.keySet(), got.positions()
                        .keySet());
                else samePoints(
                    where + " position",
                    new ArrayList<>(positions.values()),
                    new ArrayList<>(
                        got.positions()
                            .values()));
                final JsonArray islands = want.getAsJsonArray("islands");
                if (islands.size() != got.islands()
                    .size())
                    differ(
                        where + " island count",
                        islands.size(),
                        got.islands()
                            .size());
                else for (int i = 0; i < islands.size(); i++) {
                    final JsonObject w = islands.get(i)
                        .getAsJsonObject();
                    final ArrangeResult.Island g = got.islands()
                        .get(i);
                    same(where + " island " + i + " x", num(w, "x"), g.x());
                    same(where + " island " + i + " y", num(w, "y"), g.y());
                    same(where + " island " + i + " width", num(w, "width"), g.width());
                    same(where + " island " + i + " height", num(w, "height"), g.height());
                    if (w.get("backdrop")
                        .getAsBoolean() != g.backdrop())
                        differ(where + " island " + i + " backdrop", w.get("backdrop"), g.backdrop());
                }
                finish(where);
            }));
        }
        return out;
    }

    @TestFactory
    List<DynamicTest> theProxyScoresAsTheWebsiteDoes() throws IOException {
        final JsonArray calls = ArrangeConformanceTest.read()
            .getAsJsonArray("proxies");
        assertTrue(calls.size() > 0);
        final List<DynamicTest> out = new ArrayList<>();
        int n = 0;
        for (final JsonElement e : calls) {
            final JsonObject c = e.getAsJsonObject();
            final String where = "proxy " + n++ + " (" + str(c, "name") + ")";
            out.add(DynamicTest.dynamicTest(where, () -> {
                differences.clear();
                final Double airValue = num(c, "airValue");
                final double got = Optimize.scoreLayoutProxy(
                    sizes(c.getAsJsonArray("cards")),
                    optimizeWires(c.getAsJsonArray("wires")),
                    pairs(c.getAsJsonArray("positions")),
                    tuning(c.get("prices")),
                    airValue == null ? null : positions -> airValue);
                same(where + " score", num(c, "score"), got);
                finish(where);
            }));
        }
        return out;
    }
}
