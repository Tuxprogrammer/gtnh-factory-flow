package com.gtnhplanner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.client.GuiHelper;
import com.gtnhplanner.conformance.Conformance;

/**
 * What the recipe overlay's own number text (GuiHelper.formatRate and formatCount, the mod's second formatter family
 * beside core-view's Fmt) answers for the value list shared with the website (conformance/format/values.json), pinned
 * to conformance/format/mod-recipe-overlay-out.json. FmtConformanceTest does the same for Fmt; compare.mjs builds
 * COMPARISON.md from both. Rewrite the file with -PwriteVectors.
 */
class RecipeOverlayFormatTest {

    private static JsonObject values;

    @BeforeAll
    static void load() throws Exception {
        try (Reader r = new InputStreamReader(Conformance.open("format/values.json"), StandardCharsets.UTF_8)) {
            values = new JsonParser().parse(r)
                .getAsJsonObject();
        }
    }

    @Test
    void theOverlayAnswersAsTheFileSays() throws Exception {
        if (System.getProperty("gtnh.writeVectors") != null) return;
        assertEquals(Conformance.text("format/mod-recipe-overlay-out.json"), golden());
    }

    /** Rewrites conformance/format/mod-recipe-overlay-out.json: run with -PwriteVectors (mod/addon.gradle). */
    @Test
    void writeVectors() throws Exception {
        if (System.getProperty("gtnh.writeVectors") == null) return;
        Files.write(Conformance.path("format/mod-recipe-overlay-out.json"), golden().getBytes(StandardCharsets.UTF_8));
    }

    private static String golden() {
        final List<String> lines = new ArrayList<>();
        for (final JsonElement e : values.getAsJsonArray("rows")) {
            final JsonObject row = e.getAsJsonObject();
            final JsonElement mod = row.get("mod");
            if (mod.isJsonNull() || !mod.getAsString()
                .startsWith("GuiHelper.")) continue;
            final String id = row.get("id")
                .getAsString();
            final StringBuilder out = new StringBuilder("    ").append(quote(id))
                .append(": [");
            boolean first = true;
            for (final JsonElement g : row.getAsJsonArray("inputs")) {
                for (final JsonElement in : values.getAsJsonObject("groups")
                    .getAsJsonArray(g.getAsString())) {
                    if (!first) out.append(',');
                    first = false;
                    out.append(quote(answer(id, num(in))));
                }
            }
            lines.add(
                out.append(']')
                    .toString());
        }
        return "{\n  \"version\": 1,\n  \"rows\": {\n" + String.join(",\n", lines) + "\n  }\n}\n";
    }

    private static String answer(final String id, final double n) {
        try {
            switch (id) {
                case "compact.recipeOverlay":
                    // The overlay takes a float: the value is narrowed the way its callers narrow it.
                    return GuiHelper.formatRate((float) n);
                case "machines.recipeOverlay":
                    return GuiHelper.formatCount(n);
                default:
                    throw new IllegalArgumentException("no overlay formatter for row " + id);
            }
        } catch (final IllegalArgumentException e) {
            throw e;
        } catch (final RuntimeException e) {
            return "throws " + e.getClass()
                .getSimpleName();
        }
    }

    private static double num(final JsonElement e) {
        if (e.getAsJsonPrimitive()
            .isString()) {
            switch (e.getAsString()) {
                case "NaN":
                    return Double.NaN;
                case "Infinity":
                    return Double.POSITIVE_INFINITY;
                case "-Infinity":
                    return Double.NEGATIVE_INFINITY;
                default:
                    throw new IllegalArgumentException("not a number: " + e);
            }
        }
        return e.getAsDouble();
    }

    private static String quote(final String s) {
        final StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\')
                .append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"')
            .toString();
    }
}
