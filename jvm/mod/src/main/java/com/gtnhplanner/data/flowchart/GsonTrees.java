package com.gtnhplanner.data.flowchart;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * The core's JSON trees (ordered maps, lists, strings, numbers, booleans, null: {@code JsonTree}, {@code SaveCodec})
 * and Gson's, both ways. A number keeps its Java type going to Gson, so Gson prints an {@code Integer} as {@code 3} and
 * a {@code Double} as {@code 3.0}, as it printed the fields the core's codec now writes; coming back a number stays the
 * {@code Number} Gson holds (its parsed text or its box), so a tree that goes there and back prints as it came. The
 * core's readers take any {@code Number}.
 */
public final class GsonTrees {

    private GsonTrees() {}

    /** A core tree as Gson's. */
    public static JsonElement json(@Nullable final Object tree) {
        if (tree == null) return JsonNull.INSTANCE;
        if (tree instanceof final String s) return new JsonPrimitive(s);
        if (tree instanceof final Boolean b) return new JsonPrimitive(b);
        if (tree instanceof final Number n) return new JsonPrimitive(n);
        if (tree instanceof final Map<?, ?> m) {
            final JsonObject o = new JsonObject();
            for (final Map.Entry<?, ?> e : m.entrySet()) o.add(String.valueOf(e.getKey()), json(e.getValue()));
            return o;
        }
        if (tree instanceof final Iterable<?> list) {
            final JsonArray a = new JsonArray();
            for (final Object x : list) a.add(json(x));
            return a;
        }
        throw new IllegalArgumentException(
            "Not a JSON value: " + tree.getClass()
                .getName());
    }

    /** {@link #json} of a map: a JSON object. */
    public static JsonObject object(final Map<String, Object> tree) {
        return json(tree).getAsJsonObject();
    }

    /** Gson's tree as the core's. */
    @Nullable
    public static Object tree(@Nullable final JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonObject()) return map(e.getAsJsonObject());
        if (e.isJsonArray()) {
            final List<Object> out = new ArrayList<>();
            for (final JsonElement x : e.getAsJsonArray()) out.add(tree(x));
            return out;
        }
        final JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean();
        // The number as Gson holds it (its text, or the box it was given), so it prints back as it was.
        if (p.isNumber()) return p.getAsNumber();
        return p.getAsString();
    }

    /** {@link #tree} of an object: an ordered map. */
    public static Map<String, Object> map(final JsonObject o) {
        final Map<String, Object> out = new LinkedHashMap<>();
        for (final Map.Entry<String, JsonElement> e : o.entrySet()) out.put(e.getKey(), tree(e.getValue()));
        return out;
    }
}
