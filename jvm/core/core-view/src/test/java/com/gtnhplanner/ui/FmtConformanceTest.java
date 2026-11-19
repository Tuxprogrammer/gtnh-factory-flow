package com.gtnhplanner.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.gtnhplanner.conformance.Conformance;
import com.gtnhplanner.core.base.NumberText;
import com.gtnhplanner.ui.theme.Fmt;
import com.gtnhplanner.ui.theme.Fmt.RateUnit;

/**
 * What {@link Fmt} answers for the value list shared with the website (conformance/format/values.json), pinned to
 * conformance/format/mod-out.json so a change to any formatter shows up as a change to that file. The website's
 * answers are pinned the same way by src/test-support/format-conformance.test.ts; tools/conformance/format/compare.mjs
 * builds COMPARISON.md from both. The formatters are not meant to agree everywhere: COMPARISON.md lists where they
 * do not. Rewrite the file with -PwriteVectors, then run compare.mjs and node tools/conformance/manifest.mjs --write.
 */
class FmtConformanceTest {

    private static JsonObject values;

    @BeforeAll
    static void load() throws Exception {
        try (Reader r = new InputStreamReader(Conformance.open("format/values.json"), StandardCharsets.UTF_8)) {
            values = new JsonParser().parse(r)
                .getAsJsonObject();
        }
    }

    @Test
    void fmtAnswersAsModOutSays() throws Exception {
        if (System.getProperty("gtnh.writeVectors") != null) return;
        assertEquals(Conformance.text("format/mod-out.json"), golden());
    }

    /** Rewrites conformance/format/mod-out.json: run with -PwriteVectors. */
    @Test
    void writeVectors() throws Exception {
        if (System.getProperty("gtnh.writeVectors") == null) return;
        Files.write(Conformance.path("format/mod-out.json"), golden().getBytes(StandardCharsets.UTF_8));
    }

    private static String golden() {
        final List<String> lines = new ArrayList<>();
        for (final JsonElement e : values.getAsJsonArray("rows")) {
            final JsonObject row = e.getAsJsonObject();
            final JsonElement mod = row.get("mod");
            if (mod.isJsonNull() || !mod.getAsString()
                .startsWith("Fmt.")) continue;
            final StringBuilder out = new StringBuilder("    ")
                .append(quote(row.get("id").getAsString()))
                .append(": [");
            boolean first = true;
            for (final JsonElement in : inputs(row)) {
                if (!first) out.append(',');
                first = false;
                out.append(quote(answer(row, in)));
            }
            lines.add(out.append(']')
                .toString());
        }
        return "{\n  \"version\": 1,\n  \"rows\": {\n" + String.join(",\n", lines) + "\n  }\n}\n";
    }

    private static List<JsonElement> inputs(final JsonObject row) {
        final List<JsonElement> out = new ArrayList<>();
        for (final JsonElement g : row.getAsJsonArray("inputs")) {
            final JsonArray list = values.getAsJsonObject("groups")
                .getAsJsonArray(g.getAsString());
            for (final JsonElement v : list) out.add(v);
        }
        return out;
    }

    private static double num(final JsonElement e) {
        final JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isString()) {
            switch (p.getAsString()) {
                case "NaN":
                    return Double.NaN;
                case "Infinity":
                    return Double.POSITIVE_INFINITY;
                case "-Infinity":
                    return Double.NEGATIVE_INFINITY;
                default:
                    throw new IllegalArgumentException("not a number: " + p);
            }
        }
        return p.getAsDouble();
    }

    /** What Fmt answers, or "throws <exception>" where it throws: NaN and the infinities reach BigDecimal in some of its formatters. */
    private static String answer(final JsonObject row, final JsonElement in) {
        try {
            return format(row, in);
        } catch (final RuntimeException e) {
            return "throws " + e.getClass()
                .getSimpleName();
        }
    }

    private static String format(final JsonObject row, final JsonElement in) {
        final String id = row.get("id")
            .getAsString();
        if (id.startsWith("rate.")) {
            final RateUnit unit = RateUnit.valueOf(
                row.get("unit")
                    .getAsString()
                    .toUpperCase(Locale.ROOT));
            return Fmt.rate(
                num(in),
                unit,
                row.get("kind")
                    .getAsString()
                    .equals("fluid"));
        }
        switch (id) {
            case "compact":
                return Fmt.compact(num(in));
            case "brief":
                return Fmt.brief(num(in));
            case "power":
                return Fmt.power(num(in));
            case "machines":
                return Fmt.machines(num(in));
            case "euEach": {
                final JsonArray pair = in.getAsJsonArray();
                final double r = Fmt.euEach(num(pair.get(0)), num(pair.get(1)));
                return Double.isNaN(r) ? "none" : NumberText.of(r);
            }
            case "euEach.suffix":
                return Fmt.euEachSuffix(
                    in.getAsString()
                        .equals("fluid"));
            default:
                throw new IllegalArgumentException("no Fmt formatter for row " + id);
        }
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
