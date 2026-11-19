package com.gtnhplanner.core.layout.arrange;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.conformance.Conformance;
import com.gtnhplanner.core.layout.GridObstacle;
import com.gtnhplanner.core.layout.GridPoint;
import com.gtnhplanner.core.layout.GridRouteRequest;
import com.gtnhplanner.core.layout.RouteJudge;
import com.gtnhplanner.core.layout.RouterConformanceAccess;
import com.gtnhplanner.core.layout.RouterTuning;

/**
 * The Java Arrange against the website's (conformance/layout/arrange.json.gz, tools/conformance/layout/generate.mjs):
 * every arrange call the website's arrange tests make, and the reference boards arranged with the router judge, stage
 * by stage (the column candidates, the free placement) and whole. The same cards and wires in must give the same
 * positions, islands and steering stops out, to the bit. The polish's clock stands still on both sides, so it stops on
 * its count alone.
 */
class ArrangeConformanceTest {

    static JsonObject read() throws IOException {
        try (InputStream raw = Conformance.open("layout/arrange.json.gz"); InputStream in = new GZIPInputStream(raw)) {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            in.transferTo(bytes);
            return new JsonParser().parse(bytes.toString(StandardCharsets.UTF_8))
                .getAsJsonObject();
        }
    }

    // region Reading the website's JSON

    private static Double num(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsDouble();
    }

    private static String str(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    static List<ArrangeCard> cards(final JsonArray a) {
        final List<ArrangeCard> out = new ArrayList<>();
        for (final JsonElement e : a) {
            final JsonObject o = e.getAsJsonObject();
            out.add(
                new ArrangeCard(
                    str(o, "id"),
                    num(o, "x"),
                    num(o, "y"),
                    num(o, "width"),
                    num(o, "height"),
                    "storage".equals(str(o, "role"))));
        }
        return out;
    }

    static List<ArrangeWire> wires(final JsonArray a) {
        final List<ArrangeWire> out = new ArrayList<>();
        for (final JsonElement e : a) {
            final JsonObject o = e.getAsJsonObject();
            out.add(
                new ArrangeWire(
                    str(o, "id"),
                    str(o, "source"),
                    str(o, "target"),
                    num(o, "sourcePortY"),
                    num(o, "targetPortY"),
                    num(o, "weight"),
                    num(o, "width")));
        }
        return out;
    }

    static List<ArrangeInk> ink(final JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        final List<ArrangeInk> out = new ArrayList<>();
        for (final JsonElement i : e.getAsJsonArray()) {
            final JsonObject o = i.getAsJsonObject();
            out.add(new ArrangeInk(str(o, "id"), num(o, "x"), num(o, "y"), num(o, "width"), num(o, "height")));
        }
        return out;
    }

    static GridPoint point(final JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        final JsonObject o = e.getAsJsonObject();
        return new GridPoint(num(o, "x"), num(o, "y"));
    }

    static String spacing(final JsonElement taste) {
        if (taste == null || taste.isJsonNull()) return null;
        return str(taste.getAsJsonObject(), "spacing");
    }

    static Arrange.Input input(final JsonObject o, final Arrange.Judge judge, final RouterTuning tuning) {
        return new Arrange.Input(
            cards(o.getAsJsonArray("cards")),
            wires(o.getAsJsonArray("wires")),
            ink(o.get("ink")),
            point(o.get("origin")),
            spacing(o.get("taste")),
            judge,
            num(o, "polishBudget"),
            tuning,
            null,
            () -> 0,
            null);
    }

    /** A judge on the board's own router, built fresh, as each of the website's runs builds its own. */
    static Arrange.Judge judge(final JsonObject judgeInput) {
        final List<GridObstacle> obstacles = RouterConformanceAccess.obstacles(judgeInput.get("obstacles"));
        final List<GridRouteRequest> requests = RouterConformanceAccess.requests(judgeInput.get("requests"));
        final RouteJudge judge = new RouteJudge(
            obstacles,
            requests,
            RouterConformanceAccess.tuning(judgeInput.get("tuning")),
            List.of());
        return (positions, quick, base) -> {
            final RouteJudge.Verdict v = judge.judge(positions, quick, base);
            return new Arrange.Verdict(v.crossings(), v.length(), v.points(), v.events());
        };
    }

    /** A host's second thread, as the staged arrange asks for one: each job on a thread of its own. */
    static final Arrange.Worker THREAD = new Arrange.Worker() {

        @Override
        public <T> Arrange.Job<T> start(final Supplier<T> work) {
            final List<T> answer = new ArrayList<>();
            final List<Throwable> thrown = new ArrayList<>();
            final Thread thread = new Thread(() -> {
                try {
                    answer.add(work.get());
                } catch (final Throwable t) {
                    thrown.add(t);
                }
            });
            thread.start();
            return () -> {
                try {
                    thread.join();
                } catch (final InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                if (!thrown.isEmpty()) {
                    if (thrown.get(0) instanceof RuntimeException r) throw r;
                    throw new IllegalStateException(thrown.get(0));
                }
                return answer.get(0);
            };
        }
    };

    // endregion

    // region Comparing

    private final List<String> differences = new ArrayList<>();

    /** What sameResult found so far, for the other conformance tests of the package. */
    List<String> differences() {
        return differences;
    }

    private void differ(final String where, final Object want, final Object got) {
        differences.add(where + ": website " + want + ", java " + got);
    }

    private void same(final String where, final double want, final double got) {
        if (want != got && !(Double.isNaN(want) && Double.isNaN(got))) differ(where, want, got);
    }

    void sameResult(final String where, final JsonObject want, final ArrangeResult got) {
        final JsonArray moves = want.getAsJsonArray("moves");
        if (moves.size() != got.moves()
            .size()) differ(
                where + " move count",
                moves.size(),
                got.moves()
                    .size());
        else for (int i = 0; i < moves.size(); i++) {
            final JsonObject m = moves.get(i)
                .getAsJsonObject();
            final ArrangeResult.Move g = got.moves()
                .get(i);
            if (!str(m, "id").equals(g.id())) {
                differ(where + " move " + i + " id", str(m, "id"), g.id());
                continue;
            }
            final GridPoint p = point(m.get("position"));
            if (p.x() != g.position()
                .x()
                || p.y() != g.position()
                    .y())
                differ(where + " move " + g.id(), p, g.position());
        }
        final JsonArray islands = want.getAsJsonArray("islands");
        if (islands.size() != got.islands()
            .size()) differ(
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
                .getAsBoolean() != g.backdrop()) differ(where + " island " + i + " backdrop", w.get("backdrop"), g.backdrop());
        }
        final JsonArray routes = want.getAsJsonArray("wireRoutes");
        if (routes.size() != got.wireRoutes()
            .size()) differ(
                where + " wireRoutes count",
                routes.size(),
                got.wireRoutes()
                    .size());
        else for (int i = 0; i < routes.size(); i++) {
            final JsonObject w = routes.get(i)
                .getAsJsonObject();
            final ArrangeResult.WireRoute g = got.wireRoutes()
                .get(i);
            final JsonArray stops = w.getAsJsonArray("waypoints");
            boolean equal = str(w, "id").equals(g.id()) && stops.size() == g.waypoints()
                .size();
            for (int k = 0; equal && k < stops.size(); k++) {
                final GridPoint p = point(stops.get(k));
                equal = p.x() == g.waypoints()
                    .get(k)
                    .x()
                    && p.y() == g.waypoints()
                        .get(k)
                        .y();
            }
            if (!equal) differ(where + " wireRoute " + i, w, g);
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
    List<DynamicTest> theWebsitesArrangeTestsArrangeTheSame() throws IOException {
        final JsonArray tests = read().getAsJsonArray("tests");
        assertTrue(tests.size() >= 20, "the website's arrange tests make more calls than that");
        final List<DynamicTest> out = new ArrayList<>();
        int n = 0;
        for (final JsonElement e : tests) {
            final JsonObject c = e.getAsJsonObject();
            final String where = "call " + n++ + " (" + str(c, "name") + ")";
            out.add(DynamicTest.dynamicTest(where, () -> {
                differences.clear();
                final JsonObject in = c.getAsJsonObject("input");
                final RouterTuning tuning = in.has("tuning") ? RouterConformanceAccess.tuning(in.get("tuning")) : null;
                final Arrange.Input input = input(in, null, tuning);
                final ArrangeResult got = "arrangeBoardColumns".equals(str(c, "call"))
                    ? Arrange.arrangeBoardColumns(input)
                    : Arrange.arrangeBoard(input);
                sameResult(where, c.getAsJsonObject("result"), got);
                finish(where);
            }));
        }
        return out;
    }

    @TestFactory
    List<DynamicTest> referenceBoardsArrangeTheSameStageByStage() throws IOException {
        final JsonArray boards = read().getAsJsonArray("boards");
        assertTrue(boards.size() > 0);
        final List<DynamicTest> out = new ArrayList<>();
        for (final JsonElement e : boards) {
            final JsonObject b = e.getAsJsonObject();
            final String name = str(b, "name");
            final JsonObject judgeInput = b.getAsJsonObject("judgeInput");
            final RouterTuning tuning = RouterConformanceAccess.tuning(judgeInput.get("tuning"));
            out.add(DynamicTest.dynamicTest("board " + name + " columns", () -> {
                differences.clear();
                final Arrange.Columns columns = Arrange.arrangeColumnStage(input(b, judge(judgeInput), tuning));
                sameResult(name + " plain", b.getAsJsonObject("plain"), columns.plain());
                sameResult(name + " challenger", b.getAsJsonObject("challenger"), columns.challenger());
                finish("board " + name + " columns");
            }));
            out.add(DynamicTest.dynamicTest("board " + name + " free", () -> {
                differences.clear();
                sameResult(
                    name + " free",
                    b.getAsJsonObject("free"),
                    Arrange.arrangeFreeStage(input(b, judge(judgeInput), tuning)));
                finish("board " + name + " free");
            }));
            out.add(DynamicTest.dynamicTest("board " + name + " staged", () -> {
                differences.clear();
                final Arrange.Input in = input(b, judge(judgeInput), tuning);
                sameResult(
                    name + " staged",
                    b.getAsJsonObject("result"),
                    Arrange.arrangeStaged(
                        new Arrange.Input(
                            in.cards(),
                            in.wires(),
                            in.ink(),
                            in.origin(),
                            in.spacing(),
                            in.judge(),
                            in.polishBudget(),
                            in.tuning(),
                            in.onProgress(),
                            in.clock(),
                            THREAD)));
                finish("board " + name + " staged");
            }));
            out.add(DynamicTest.dynamicTest("board " + name + " whole", () -> {
                differences.clear();
                sameResult(
                    name + " result",
                    b.getAsJsonObject("result"),
                    Arrange.arrangeBoard(input(b, judge(judgeInput), tuning)));
                finish("board " + name + " whole");
            }));
        }
        return out;
    }
}
