package com.gtnhplanner.core.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * NumberText and JsMath against JavaScript's own answers (number-text-vectors.json, written by Node with
 * gen-vectors.mjs), so the core spells and reads numbers exactly as the website does.
 */
class NumberTextTest {

    private static JsonObject vectors;

    @BeforeAll
    static void load() throws Exception {
        try (Reader r = new InputStreamReader(
            NumberTextTest.class.getResourceAsStream("/number-text/number-text-vectors.json"),
            StandardCharsets.UTF_8)) {
            vectors = new JsonParser().parse(r)
                .getAsJsonObject();
        }
    }

    private static double bits(final String hex) {
        return Double.longBitsToDouble(Long.parseUnsignedLong(hex, 16));
    }

    private static String hex(final double d) {
        final String h = Long.toHexString(Double.doubleToRawLongBits(d));
        return "0000000000000000".substring(h.length()) + h;
    }

    private static void assertNoDifferences(final String what, final int count, final List<String> wrong) {
        assertTrue(count > 0, what + ": no vectors read");
        assertTrue(wrong.isEmpty(), what + ": " + wrong.size() + " of " + count + " differ from JavaScript, first: "
            + wrong.subList(0, Math.min(10, wrong.size())));
    }

    @Test
    void spellsEveryVectorAsStringOfNDoes() {
        final JsonArray of = vectors.getAsJsonArray("of");
        final List<String> wrong = new ArrayList<>();
        for (final JsonElement e : of) {
            final JsonArray v = e.getAsJsonArray();
            final double x = bits(v.get(0)
                .getAsString());
            final String js = v.get(1)
                .getAsString();
            final String json = v.get(2)
                .getAsString();
            if (!NumberText.of(x)
                .equals(js)) wrong.add(v.get(0) + " -> " + NumberText.of(x) + " (JavaScript " + js + ")");
            if (!NumberText.json(x)
                .equals(json)) wrong.add(v.get(0) + " json -> " + NumberText.json(x) + " (JavaScript " + json + ")");
        }
        assertNoDifferences("String(n)", of.size(), wrong);
    }

    @Test
    void roundsEveryVectorAsToFixedDoes() {
        final JsonArray fixed = vectors.getAsJsonArray("fixed");
        final List<String> wrong = new ArrayList<>();
        for (final JsonElement e : fixed) {
            final JsonArray v = e.getAsJsonArray();
            final double x = bits(v.get(0)
                .getAsString());
            final int digits = v.get(1)
                .getAsInt();
            final String js = v.get(2)
                .getAsString();
            final String mine = NumberText.fixed(x, digits);
            if (!mine.equals(js)) wrong.add(v.get(0) + " toFixed(" + digits + ") -> " + mine + " (JavaScript " + js + ")");
        }
        assertNoDifferences("toFixed", fixed.size(), wrong);
    }

    @Test
    void readsEveryVectorAsNumberOfSDoes() {
        final JsonArray parse = vectors.getAsJsonArray("parse");
        final List<String> wrong = new ArrayList<>();
        for (final JsonElement e : parse) {
            final JsonArray v = e.getAsJsonArray();
            final String text = v.get(0)
                .getAsString();
            final String js = v.get(1)
                .getAsString();
            final String mine = hex(NumberText.parse(text));
            // NaN has many bit patterns; JavaScript's and Java's canonical one agree, but compare NaN as NaN.
            final boolean same = mine.equals(js) || Double.isNaN(bits(js)) && Double.isNaN(bits(mine));
            if (!same) wrong.add("'" + text + "' -> " + mine + " (JavaScript " + js + ")");
        }
        assertNoDifferences("Number(s)", parse.size(), wrong);
    }

    @Test
    void roundsEveryVectorAsMathRoundDoes() {
        final JsonArray round = vectors.getAsJsonArray("round");
        final List<String> wrong = new ArrayList<>();
        for (final JsonElement e : round) {
            final JsonArray v = e.getAsJsonArray();
            final double x = bits(v.get(0)
                .getAsString());
            final String js = v.get(1)
                .getAsString();
            final String mine = hex(JsMath.round(x));
            final boolean same = mine.equals(js) || Double.isNaN(bits(js)) && Double.isNaN(bits(mine));
            if (!same) wrong.add(v.get(0) + " -> " + mine + " (JavaScript " + js + ")");
        }
        assertNoDifferences("Math.round", round.size(), wrong);
    }

    @Test
    void everySpellingReadsBackAsTheSameDouble() {
        final Random r = new Random(20261119);
        for (int i = 0; i < 200_000; i++) {
            final double x = Double.longBitsToDouble(r.nextLong());
            if (!Double.isFinite(x)) continue;
            final String s = NumberText.of(x);
            assertEquals(Double.doubleToLongBits(x == 0 ? 0 : x), Double.doubleToLongBits(NumberText.parse(s)), s);
            assertEquals(x == 0 ? 0 : x, Double.parseDouble(s), s);
        }
    }

    @Test
    void namedCases() {
        assertEquals("10000000000", NumberText.of(1e10));
        assertEquals("0.1", NumberText.of(0.1));
        assertEquals("0.30000000000000004", NumberText.of(0.1 + 0.2));
        assertEquals("1e+21", NumberText.of(1e21));
        assertEquals("1.5e-7", NumberText.of(1.5e-7));
        assertEquals("0.000001", NumberText.of(1e-6));
        assertEquals("5e-324", NumberText.of(Double.MIN_VALUE));
        assertEquals("-2.5", NumberText.of(-2.5));
        assertEquals("0", NumberText.of(-0.0));
        assertEquals("null", NumberText.json(Double.NaN));
        assertEquals("0.1", NumberText.fixed(0.15, 1));
        assertEquals("3", NumberText.fixed(2.5, 0));
        assertEquals("-0.00", NumberText.fixed(-0.0001, 2));
        assertEquals(-2, JsMath.round(-2.5));
        assertThrows(IllegalArgumentException.class, () -> NumberText.fixed(1, 101));
    }
}
