package com.gtnhplanner.core.view.corpus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.conformance.Conformance;
import com.gtnhplanner.core.base.Uuid;

/**
 * The view records against the shape table the declarations are made from ({@code conformance/view/records.json},
 * written by {@code tools/web-core/gen-records.mjs} from the Java sources). The table is a text parse of the sources;
 * this test says what the compiler made of the same records, component by component, so a record the parser read
 * wrongly, or one changed without the declarations being regenerated, fails here.
 */
class ViewRecordsTest {

    private static JsonObject table() throws IOException {
        try (InputStream in = Conformance.open("view/records.json")) {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            in.transferTo(bytes);
            return new JsonParser().parse(bytes.toString(StandardCharsets.UTF_8))
                .getAsJsonObject();
        }
    }

    /** The flat name the table uses: the class's name below its package with the nesting dollars dropped. */
    static String flat(final Class<?> c) {
        final String name = c.getName();
        return name.substring(c.getPackageName()
            .length() + 1)
            .replace("$", "");
    }

    /** A Java type as the table spells it: string, number, boolean, a flat name, T[] or Record<string, T>. */
    static String shape(final Type type) {
        if (type instanceof ParameterizedType p) {
            final Type[] args = p.getActualTypeArguments();
            if (p.getRawType() == List.class) return shape(args[0]) + "[]";
            if (p.getRawType() == Map.class) {
                assertEquals(String.class, args[0], "a map on the wire has String keys");
                return "Record<string, " + shape(args[1]) + ">";
            }
            throw new AssertionError("not a wire type: " + type);
        }
        final Class<?> c = (Class<?>) type;
        if (c == int.class || c == Integer.class || c == double.class || c == Double.class) return "number";
        if (c == boolean.class || c == Boolean.class) return "boolean";
        if (c == String.class || c == Uuid.class) return "string";
        if (c.isEnum() || c.isRecord()) return flat(c);
        throw new AssertionError("not a wire type: " + type);
    }

    @Test
    void everyRecordOfTheTableIsWhatTheCompilerSays() throws Exception {
        final JsonObject records = table().getAsJsonObject("records");
        assertTrue(records.entrySet()
            .size() >= 20, "the shape table lost records");
        for (final Map.Entry<String, JsonElement> entry : records.entrySet()) {
            final JsonObject rec = entry.getValue()
                .getAsJsonObject();
            final Class<?> c = Class.forName(
                rec.get("class")
                    .getAsString());
            assertTrue(c.isRecord(), c + " is not a record");
            assertEquals(entry.getKey(), flat(c));
            final List<String> compiled = new ArrayList<>();
            for (final RecordComponent part : c.getRecordComponents()) {
                compiled.add(
                    part.getName() + ":" + shape(part.getGenericType()) + (RecordReader.isOptional(part) ? "?" : ""));
            }
            final List<String> parsed = new ArrayList<>();
            for (final JsonElement e : rec.getAsJsonArray("components")) {
                final JsonArray pair = e.getAsJsonArray();
                parsed.add(
                    pair.get(0)
                        .getAsString() + ":"
                        + pair.get(1)
                            .getAsString());
            }
            assertEquals(compiled, parsed, entry.getKey());
        }
    }

    @Test
    void everyEnumOfTheTableIsWhatTheCompilerSays() throws Exception {
        for (final Map.Entry<String, JsonElement> entry : table().getAsJsonObject("enums")
            .entrySet()) {
            final JsonObject en = entry.getValue()
                .getAsJsonObject();
            final Class<?> c = Class.forName(
                en.get("class")
                    .getAsString());
            assertTrue(c.isEnum(), c + " is not an enum");
            assertEquals(entry.getKey(), flat(c));
            final List<String> compiled = new ArrayList<>();
            for (final Object k : c.getEnumConstants()) compiled.add(((Enum<?>) k).name());
            final List<String> parsed = new ArrayList<>();
            for (final JsonElement e : en.getAsJsonArray("values")) parsed.add(e.getAsString());
            assertEquals(compiled, parsed, entry.getKey());
        }
    }

    @Test
    void theTableNamesNoTypeItDoesNotHold() throws Exception {
        final JsonObject t = table();
        final JsonObject records = t.getAsJsonObject("records");
        final JsonObject enums = t.getAsJsonObject("enums");
        for (final Map.Entry<String, JsonElement> entry : records.entrySet()) {
            for (final JsonElement e : entry.getValue()
                .getAsJsonObject()
                .getAsJsonArray("components")) {
                String type = e.getAsJsonArray()
                    .get(1)
                    .getAsString();
                if (type.endsWith("?")) type = type.substring(0, type.length() - 1);
                if (type.startsWith("Record<string, ")) type = type.substring(15, type.length() - 1);
                while (type.endsWith("[]")) type = type.substring(0, type.length() - 2);
                final boolean scalar = type.equals("string") || type.equals("number") || type.equals("boolean");
                assertTrue(scalar || records.has(type) || enums.has(type), entry.getKey() + " names " + type);
            }
        }
        assertFalse(records.has("Json"), "the writer is not a record");
    }
}
