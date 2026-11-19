package com.gtnhplanner.core.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.gtnhplanner.conformance.Conformance;

/**
 * {@link Format} against the website's own answers (conformance/format/website-out.json, written by the website's
 * vitest from values.json): every formatter the core has, on every value of every row, character for character. The
 * Node runner (tools/web-core/format.mjs) holds the compiled core to the same file on both web targets.
 * <p>
 * A row with no formatter here must be listed in {@link #WEBSITE_ONLY}; an unknown row fails, so a row added to
 * values.json cannot go unchecked.
 */
class FormatGoldenTest {

    /** Percent, multiplier and duration text is still written by hand on each side; the golden pins the website's. */
    private static final Set<String> WEBSITE_ONLY = new HashSet<>(
        Arrays.asList("percent.whole", "percent.supply", "times"));

    private static JsonObject read(final String file) throws Exception {
        try (Reader r = new InputStreamReader(Conformance.open(file), StandardCharsets.UTF_8)) {
            return new JsonParser().parse(r)
                .getAsJsonObject();
        }
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

    private static String answer(final JsonObject row, final JsonElement in) {
        final String id = row.get("id")
            .getAsString();
        if (id.startsWith("rate.") && row.has("unit")) {
            return Format.slotRate(
                num(in),
                RateUnit.valueOf(
                    row.get("unit")
                        .getAsString()
                        .toUpperCase(Locale.ROOT)),
                row.get("kind")
                    .getAsString());
        }
        switch (id) {
            case "compact":
            case "compact.recipeOverlay":
            case "compact.edges":
                return Format.compact(num(in));
            case "brief":
            case "stable":
                return Format.compactStable(num(in));
            case "power":
            case "power.edges":
                return Format.powerValue(num(in));
            case "power.stable":
                return Format.powerValue(num(in), true);
            case "rate.plain":
            case "rate.plain.d0":
            case "rate.plain.d3":
                return Format.rate(
                    num(in),
                    row.get("digits")
                        .getAsInt());
            case "thousands.text":
                return Format.thousands(in.getAsString());
            case "thousands.number":
                return Format.thousands(num(in));
            case "trim":
                return Format.trimZeros(in.getAsString());
            case "machines":
            case "machines.edges":
            case "machines.recipeOverlay":
                return Format.solvedMachines(num(in));
            case "machines.list":
            case "machines.list.edges":
                return Format.machineListCount(num(in));
            case "percent.share":
            case "percent.share.edges":
                return Format.ratioShare(num(in));
            case "euEach": {
                final JsonArray pair = in.getAsJsonArray();
                final double r = Format.energyPerUnit(num(pair.get(0)), num(pair.get(1)));
                return Double.isNaN(r) ? "none" : NumberText.of(r);
            }
            case "euEach.suffix":
                return Format.energyPerUnitSuffix(in.getAsString());
            default:
                throw new IllegalArgumentException("values.json has a row Format has no answer for: " + id);
        }
    }

    @Test
    void formatAnswersAsTheWebsiteDoes() throws Exception {
        final JsonObject values = read("format/values.json");
        final JsonObject golden = read("format/website-out.json").getAsJsonObject("rows");
        final List<String> wrong = new ArrayList<>();
        int checked = 0;
        int rows = 0;
        for (final JsonElement e : values.getAsJsonArray("rows")) {
            final JsonObject row = e.getAsJsonObject();
            final String id = row.get("id")
                .getAsString();
            if (WEBSITE_ONLY.contains(id)) continue;
            rows++;
            final JsonArray expected = golden.getAsJsonArray(id);
            int i = 0;
            for (final JsonElement g : row.getAsJsonArray("inputs")) {
                for (final JsonElement in : values.getAsJsonObject("groups")
                    .getAsJsonArray(g.getAsString())) {
                    final String want = expected.get(i++)
                        .getAsString();
                    final String got = answer(row, in);
                    checked++;
                    if (!want.equals(got)) wrong.add(id + " " + in + ": " + got + " (website " + want + ")");
                }
            }
            assertEquals(expected.size(), i, id + ": answers and inputs line up");
        }
        assertTrue(rows >= 30 && checked >= 2000, "only " + checked + " answers over " + rows + " rows were checked");
        assertTrue(wrong.isEmpty(), wrong.size() + " of " + checked + " differ from the website, first: "
            + wrong.subList(0, Math.min(15, wrong.size())));
    }
}
