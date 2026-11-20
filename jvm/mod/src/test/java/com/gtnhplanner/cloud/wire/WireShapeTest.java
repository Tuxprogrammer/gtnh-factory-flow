package com.gtnhplanner.cloud.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** The shape checker, on the same cases as src/test-support/wire-shape.test.ts: both ends read a shape alike. */
class WireShapeTest {

    private static final JsonObject SHAPES = Wire.read("shapes.json");

    private static JsonElement j(final String json) {
        return new JsonParser().parse(json);
    }

    private static void check(final String shape, final String value, final boolean ok) {
        final List<String> problems = WireShape.problems(SHAPES, j(shape), j(value));
        assertEquals(ok, problems.isEmpty(), shape + " against " + value + ": " + problems);
    }

    @Test
    void sharesTheCasesOfTheWebsitesChecker() {
        check("\"string\"", "\"a\"", true);
        check("\"string\"", "1", false);
        check("\"integer\"", "1", true);
        check("\"integer\"", "1.5", false);
        check("\"number\"", "1.5", true);
        check("\"string|null\"", "null", true);
        check("\"string|null\"", "3", false);
        check("[\"string\"]", "[]", true);
        check("[\"string\"]", "[\"a\", 2]", false);
        check("{\"a\":\"string\",\"b?\":\"integer\"}", "{\"a\":\"x\"}", true);
        check("{\"a\":\"string\",\"b?\":\"integer\"}", "{\"a\":\"x\",\"b\":\"y\"}", false);
        check("{\"a\":\"string\"}", "{\"a\":\"x\",\"extra\":1}", true);
        check("{\"a\":\"string\"}", "{}", false);
        check("{\"=\":true}", "true", true);
        check("{\"=\":true}", "false", false);
        check("{\"in\":[\"a\",\"b\"]}", "\"b\"", true);
        check("{\"in\":[\"a\",\"b\"]}", "\"c\"", false);
        check("\"$Account\"", "{\"id\":\"1\",\"username\":\"ann\"}", true);
        check("\"$Account\"", "{\"id\":\"1\"}", false);
        check("\"$LiveCursor|null\"", "null", true);
        check("\"object\"", "[]", false);
        check("\"array\"", "[]", true);
        assertTrue(WireShape.problems(SHAPES, j("\"any\""), null).isEmpty());
    }

    @Test
    void namesEveryProblemWithItsPath() {
        assertEquals(
            List.of("$.id: expected string, got 1", "$.username: missing"),
            WireShape.problems(SHAPES, j("\"$Account\""), j("{\"id\":1}")));
    }

    @Test
    void readsDottedPathsIntoArraysAndObjects() {
        assertEquals(
            List.of(),
            WireShape.expectProblems(j("{\"a\":[{\"b\":2}]}").getAsJsonObject(), j("{\"a.0.b\":2}").getAsJsonObject()));
        assertEquals(
            List.of("a.0.b: expected 3, got 2"),
            WireShape.expectProblems(j("{\"a\":[{\"b\":2}]}").getAsJsonObject(), j("{\"a.0.b\":3}").getAsJsonObject()));
    }

    @Test
    void fillsVariablesWholeAndInsideStrings() {
        final Map<String, JsonElement> vars = Map.of("a.id", j("\"u1\""), "plan", j("{\"x\":1}"));
        assertEquals(j("\"u1\""), Wire.fill(j("\"{a.id}\""), vars));
        assertEquals(j("\"/x/u1?q\""), Wire.fill(j("\"/x/{a.id}?q\""), vars));
        assertEquals(j("{\"p\":{\"x\":1}}"), Wire.fill(j("{\"p\":\"{plan}\"}"), vars));
    }

    @Test
    void everyShapeOfTheFileIsWellFormed() {
        for (final String name : entryKeys(SHAPES)) {
            if (name.startsWith("$")) continue;
            // A well-formed shape never throws, whatever it is shown.
            WireShape.problems(SHAPES, SHAPES.get(name), j("null"));
        }
    }

    private static java.util.List<String> entryKeys(final com.google.gson.JsonObject o) {
        final java.util.List<String> out = new java.util.ArrayList<>();
        for (final java.util.Map.Entry<String, com.google.gson.JsonElement> e : o.entrySet()) out.add(e.getKey());
        return out;
    }
}
