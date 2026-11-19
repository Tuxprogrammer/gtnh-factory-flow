package com.gtnhplanner.core.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.conformance.Conformance;

/**
 * JsSort sorts as V8's Array.prototype.sort does (conformance/layout/js-sort.json, written by Node): consistent orders,
 * equal keys, and comparators that are no order at all, on sizes that take every path (runs, binary insertion,
 * merges with galloping).
 */
class JsSortTest {

    /** tools/conformance/layout/js-sort.mjs hashOrder. */
    static int hashOrder(final int seed, final int x, final int y) {
        int h = x * 0x9e3779b1 ^ y * 0x85ebca77 ^ seed;
        h ^= h >>> 15;
        h = h * 0x2c1b3c6d;
        h ^= h >>> 12;
        return Integer.remainderUnsigned(h, 3) - 1;
    }

    @Test
    void sortsAsV8() throws IOException {
        final JsonObject root;
        try (InputStream in = Conformance.open("layout/js-sort.json")) {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            in.transferTo(bytes);
            root = new JsonParser().parse(bytes.toString(StandardCharsets.UTF_8))
                .getAsJsonObject();
        }
        int n = 0;
        for (final JsonElement e : root.getAsJsonArray("cases")) {
            final JsonObject c = e.getAsJsonObject();
            final String kind = c.get("kind")
                .getAsString();
            final int seed = c.get("seed")
                .getAsInt();
            final JsonArray keysJson = c.getAsJsonArray("keys");
            final int[] keys = new int[keysJson.size()];
            for (int i = 0; i < keys.length; i++) keys[i] = keysJson.get(i)
                .getAsInt();
            final Comparator<Integer> order = switch (kind) {
                case "hash" -> (x, y) -> hashOrder(seed, x, y);
                case "keys" -> (x, y) -> keys[x] - keys[y];
                default -> (x, y) -> keys[x] != keys[y] ? keys[x] - keys[y] : hashOrder(seed, x, y);
            };
            final List<Integer> list = new ArrayList<>();
            for (final JsonElement v : c.getAsJsonArray("input")) list.add(v.getAsInt());
            JsSort.sort(list, order);
            final List<Integer> want = new ArrayList<>();
            for (final JsonElement v : c.getAsJsonArray("sorted")) want.add(v.getAsInt());
            assertEquals(want, list, kind + " " + c.get("shape")
                .getAsString() + " n=" + keys.length);
            n++;
        }
        assertTrue(n > 400, "cases: " + n);
    }
}
