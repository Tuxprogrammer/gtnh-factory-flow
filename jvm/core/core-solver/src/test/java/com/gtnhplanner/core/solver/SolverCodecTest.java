package com.gtnhplanner.core.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.gtnhplanner.conformance.Conformance;
import com.gtnhplanner.core.base.JsonTree;
import com.gtnhplanner.data.flowchart.balancer.Balancer;

/**
 * The codec reads the corpus's input files and writes answers in the goldens' shape, on the JVM where the golden tests
 * of core-solver prove the solver itself: here the reader and the writer are the ones under test, on a sample of the
 * corpus that solves fast (every plan runs through them in tools/web-core/run.mjs). The comparison is the corpus's:
 * the same keys, the same array lengths and order, numbers equal when {@code p == q} or within {@code 1e-6} of the larger
 * of 1 and their magnitudes.
 */
class SolverCodecTest {

    private static final String[] PLANS = { "single-card-v2", "mv", "benzene", "glass", "buffer-drawers", "sulfuric-acid",
        "gtnh-flow-excess_choice", "gtnh-flow-symmetric_choice", "gtnh-flow-two_decisions", "gtnh-flow-loopGraph" };

    /** Up to plans that bank, rank every reason, and have rings; each written by the website's engine. */
    private static final String[] UP_TO = { "banked-any-taker", "buffer-up-to-taker", "clog-ring", "dead-ring-of-three",
        "starved-chain", "two-sided-tied", "pin-diagnosis", "shared-pool", "unreachable-drawer" };

    @TestFactory
    Stream<DynamicTest> corpusPlansAnswerAsTheirGoldensDo() {
        return Stream.of(PLANS).map(name -> DynamicTest.dynamicTest(name, () -> {
            final String input = Conformance.text("solver/corpus/inputs/" + name + ".json");
            final String golden = Conformance.text("solver/corpus/goldens/" + name + ".json");
            check(name, JsonTree.parse(golden), solve(input));
        }));
    }

    @TestFactory
    Stream<DynamicTest> upToPlansAnswerAsTheWebsiteDoes() {
        return Stream.of(UP_TO).map(name -> DynamicTest.dynamicTest(name, () -> {
            final Map<String, Object> plan = JsonTree.object(JsonTree.parse(Conformance.text("solver/up-to/" + name + ".json")));
            final Object actual = solve(JsonTree.write(plan.get("input")));
            check(name, normalised(plan.get("answer")), normalised(actual));
        }));
    }

    private static Object solve(final String inputJson) {
        final String text = SolverCodec.writeAnswer(Balancer.solveWithAlternatives(SolverCodec.readInput(inputJson)));
        return JsonTree.parse(text);
    }

    private static void check(final String name, final Object expected, final Object actual) {
        final List<String> out = new ArrayList<>();
        compare("", expected, actual, out);
        assertTrue(out.isEmpty(), name + " answers differently:\n  " + String.join("\n  ", out));
    }

    /** The shortfalls' limiting nodes and drawers are sets on the website (sorted by text) and lists here. */
    @SuppressWarnings("unchecked")
    private static Object normalised(final Object digest) {
        if (digest instanceof List) {
            for (final Object e : (List<Object>) digest) normalised(e);
        } else if (digest instanceof Map) {
            final Map<String, Object> o = (Map<String, Object>) digest;
            for (final String key : List.of("limitingNodes", "limitingDrawers")) {
                if (o.get(key) instanceof List) {
                    final List<String> sorted = new ArrayList<>();
                    for (final Object x : (List<Object>) o.get(key)) sorted.add((String) x);
                    sorted.sort(null);
                    o.put(key, new ArrayList<Object>(sorted));
                }
            }
            for (final Object v : o.values()) normalised(v);
        }
        return digest;
    }

    @SuppressWarnings("unchecked")
    private static void compare(final String path, final Object a, final Object b, final List<String> out) {
        if (out.size() > 20) return;
        if (a instanceof Map && b instanceof Map) {
            final Map<String, Object> x = (Map<String, Object>) a, y = (Map<String, Object>) b;
            final TreeSet<String> keys = new TreeSet<>(x.keySet());
            keys.addAll(y.keySet());
            for (final String k : keys) {
                if (!x.containsKey(k) || !y.containsKey(k)) out.add(path + "/" + k + ": " + x.get(k) + " / " + y.get(k));
                else compare(path + "/" + k, x.get(k), y.get(k), out);
            }
        } else if (a instanceof List && b instanceof List) {
            final List<Object> x = (List<Object>) a, y = (List<Object>) b;
            if (x.size() != y.size()) {
                out.add(path + ": " + x.size() + " items / " + y.size());
                return;
            }
            for (int i = 0; i < x.size(); i++) compare(path + "[" + i + "]", x.get(i), y.get(i), out);
        } else if (a instanceof Double && b instanceof Double) {
            final double p = (Double) a, q = (Double) b;
            if (!(p == q || Math.abs(p - q) <= 1e-6 * Math.max(1, Math.max(Math.abs(p), Math.abs(q)))))
                out.add(path + ": " + p + " / " + q);
        } else if (a == null ? b != null : !a.equals(b)) out.add(path + ": " + a + " / " + b);
    }

    @TestFactory
    Stream<DynamicTest> aFailedSolveIsAnAnswerNotAnException() {
        return Stream.of(DynamicTest.dynamicTest("empty plan", () -> {
            final String empty = "{\"mode\":\"AUTO\",\"machines\":[],\"edges\":[],\"pools\":[],\"drawers\":[],"
                + "\"extentPins\":{},\"spares\":{}}";
            final Map<String, Object> answer = JsonTree.object(solve(empty));
            assertEquals(java.util.Set.of("failure", "notes", "drawers"), answer.keySet());
        }));
    }
}
