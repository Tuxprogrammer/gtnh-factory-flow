package com.gtnhplanner.cloud.wire;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * The shape language of {@code conformance/wire/shapes.json} (described in its {@code $language} entry), checked in
 * Java. {@code src/test-support/wire-shape.ts} is the same checker for the website's side; both run the same table of
 * cases (WireShapeTest, wire-shape.test.ts), so a shape means one thing on both ends.
 *
 * <p>
 * A mismatch is a list of "path: what was wrong" lines, so a failing exchange says where.
 */
public final class WireShape {

    private static final Set<String> PRIMITIVES = Set
        .of("string", "number", "integer", "boolean", "null", "object", "array", "any");

    private WireShape() {}

    private static boolean primitive(final String name, @Nullable final JsonElement v) {
        final boolean present = v != null;
        switch (name) {
            case "string":
                return present && v.isJsonPrimitive() && v.getAsJsonPrimitive()
                    .isString();
            case "number":
                return present && v.isJsonPrimitive() && v.getAsJsonPrimitive()
                    .isNumber();
            case "integer":
                return present && v.isJsonPrimitive() && v.getAsJsonPrimitive()
                    .isNumber()
                    && v.getAsBigDecimal()
                        .stripTrailingZeros()
                        .scale() <= 0;
            case "boolean":
                return present && v.isJsonPrimitive() && v.getAsJsonPrimitive()
                    .isBoolean();
            case "null":
                return present && v.isJsonNull();
            case "object":
                return present && v.isJsonObject();
            case "array":
                return present && v.isJsonArray();
            case "any":
                return true;
            default:
                throw new IllegalArgumentException("unknown primitive " + name);
        }
    }

    /** The problems with {@code value} against {@code shape} (empty: it matches). Shape names resolve in {@code shapes}. */
    public static List<String> problems(final JsonObject shapes, final JsonElement shape,
        @Nullable final JsonElement value) {
        return problems(shapes, shape, value, "$");
    }

    private static List<String> problems(final JsonObject shapes, final JsonElement shape,
        @Nullable final JsonElement value, final String at) {
        if (shape.isJsonPrimitive()) {
            final String[] options = shape.getAsString()
                .split("\\|");
            List<String> first = null;
            for (String option : options) {
                option = option.trim();
                final List<String> found;
                if (option.startsWith("$")) {
                    final JsonElement named = shapes.get(option.substring(1));
                    if (named == null) throw new IllegalArgumentException("unknown shape " + option);
                    found = problems(shapes, named, value, at);
                } else if (PRIMITIVES.contains(option)) {
                    found = primitive(option, value) ? List.of()
                        : List.of(at + ": expected " + option + ", got " + describe(value));
                } else {
                    throw new IllegalArgumentException("unknown shape word " + option);
                }
                if (found.isEmpty()) return List.of();
                if (first == null) first = found;
            }
            return options.length == 1 ? first
                : List.of(at + ": matches none of " + shape.getAsString() + " (got " + describe(value) + ")");
        }
        if (shape.isJsonArray()) {
            final JsonArray one = shape.getAsJsonArray();
            if (one.size() != 1) throw new IllegalArgumentException("an array shape holds one element shape");
            if (value == null || !value.isJsonArray()) return List.of(at + ": expected array, got " + describe(value));
            final List<String> out = new ArrayList<>();
            int i = 0;
            for (final JsonElement item : value.getAsJsonArray()) {
                out.addAll(problems(shapes, one.get(0), item, at + "[" + i++ + "]"));
            }
            return out;
        }
        if (shape.isJsonObject()) {
            final JsonObject s = shape.getAsJsonObject();
            if (s.entrySet().size() == 1 && s.has("=")) {
                return s.get("=")
                    .equals(value) ? List.of()
                        : List.of(at + ": expected " + s.get("=") + ", got " + describe(value));
            }
            if (s.entrySet().size() == 1 && s.has("in")) {
                for (final JsonElement candidate : s.getAsJsonArray("in")) if (candidate.equals(value)) return List.of();
                return List.of(at + ": expected one of " + s.get("in") + ", got " + describe(value));
            }
            if (value == null || !value.isJsonObject()) return List.of(at + ": expected object, got " + describe(value));
            final List<String> out = new ArrayList<>();
            for (final Map.Entry<String, JsonElement> e : s.entrySet()) {
                final boolean optional = e.getKey()
                    .endsWith("?");
                final String name = optional ? e.getKey()
                    .substring(0, e.getKey().length() - 1) : e.getKey();
                if (!value.getAsJsonObject()
                    .has(name)) {
                    if (!optional) out.add(at + "." + name + ": missing");
                    continue;
                }
                out.addAll(
                    problems(
                        shapes,
                        e.getValue(),
                        value.getAsJsonObject()
                            .get(name),
                        at + "." + name));
            }
            return out;
        }
        throw new IllegalArgumentException("a shape is a string, an array or an object, got " + describe(shape));
    }

    private static String describe(@Nullable final JsonElement v) {
        if (v == null) return "undefined";
        final String text = v.toString();
        return text.length() > 80 ? text.substring(0, 77) + "..." : text;
    }

    /** The value at a dotted path ({@code design.name}, {@code plans.0.id}), or null when absent. */
    @Nullable
    public static JsonElement valueAt(@Nullable final JsonElement value, final String dotted) {
        JsonElement at = value;
        for (final String part : dotted.split("\\.")) {
            if (at == null) return null;
            if (at.isJsonArray()) {
                try {
                    final int i = Integer.parseInt(part);
                    at = i >= 0 && i < at.getAsJsonArray()
                        .size() ? at.getAsJsonArray()
                            .get(i) : null;
                } catch (final NumberFormatException e) {
                    return null;
                }
            } else if (at.isJsonObject()) {
                at = at.getAsJsonObject()
                    .get(part);
            } else {
                return null;
            }
        }
        return at;
    }

    /** Problems with {@code value} not holding each {@code expected} path/value pair. */
    public static List<String> expectProblems(@Nullable final JsonElement value, final JsonObject expected) {
        final List<String> out = new ArrayList<>();
        for (final Map.Entry<String, JsonElement> e : expected.entrySet()) {
            final JsonElement got = valueAt(value, e.getKey());
            if (!e.getValue()
                .equals(got)) {
                out.add(e.getKey() + ": expected " + e.getValue() + ", got " + describe(got));
            }
        }
        return out;
    }

    /** A JSON null, for a caller building values. */
    public static JsonElement jsonNull() {
        return JsonNull.INSTANCE;
    }

    /** A string value. */
    public static JsonElement text(final String s) {
        return new JsonPrimitive(s);
    }
}
