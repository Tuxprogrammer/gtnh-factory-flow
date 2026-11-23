package com.gtnhplanner.client.dave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class DaveContextTest {

    private static DaveContext.Plan plan(final int selected, final int labelLength) {
        final List<DaveContext.Card> sel = new ArrayList<>();
        for (int i = 0; i < selected; i++) sel.add(
            new DaveContext.Card(
                "00000000-0000-0000-0000-00000000000" + (i % 10),
                "gtnhplanner:" + "r".repeat(labelLength),
                "Electric Blast Furnace " + "x".repeat(labelLength)));
        return new DaveContext.Plan("plan-1", "Steel line", "2.9.1", 12, true, "HV", sel);
    }

    private static JsonObject read(final String json) {
        return new JsonParser().parse(json)
            .getAsJsonObject();
    }

    @Test
    void namesThePlanAndItsSelection() {
        final JsonObject o = read(DaveContext.json(plan(2, 3), false));
        assertEquals(
            "plan-1",
            o.get("planId")
                .getAsString());
        assertEquals(
            "Steel line",
            o.get("planName")
                .getAsString());
        assertEquals(
            "2.9.1",
            o.get("dataset")
                .getAsString());
        assertEquals(
            12,
            o.get("cardCount")
                .getAsInt());
        assertTrue(
            o.get("live")
                .getAsBoolean());
        assertEquals(
            "HV",
            o.get("tier")
                .getAsString());
        assertEquals(
            2,
            o.getAsJsonArray("selection")
                .size());
        final JsonObject first = o.getAsJsonArray("selection")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            "gtnhplanner:rrr",
            first.get("recipeId")
                .getAsString());
        assertEquals(
            "Electric Blast Furnace xxx",
            first.get("label")
                .getAsString());
        assertFalse(o.has("attachment"), "the plan went with it");
    }

    @Test
    void theSelectionIsCappedAtEight() {
        assertEquals(
            8,
            read(DaveContext.json(plan(30, 3), false)).getAsJsonArray("selection")
                .size());
    }

    @Test
    void stayWellUnderFourThousandCharactersHoweverLongTheNames() {
        final List<DaveContext.Card> sel = new ArrayList<>();
        for (int i = 0; i < 20; i++)
            sel.add(new DaveContext.Card("id-".repeat(100), "r".repeat(5000), "é".repeat(5000)));
        final DaveContext.Plan p = new DaveContext.Plan(
            "p".repeat(1000),
            "n".repeat(10_000),
            "d".repeat(500),
            5,
            false,
            "UV",
            sel);
        final String json = DaveContext.json(p, true);
        assertTrue(json.length() <= DaveContext.MAX_CHARS, json.length() + " characters");
        assertTrue(
            read(json).getAsJsonArray("selection")
                .size() > 0,
            "cut, not emptied");
        // Characters JSON writes escaped still keep it under the cap: some cards go instead.
        final List<DaveContext.Card> quoted = new ArrayList<>();
        for (int i = 0; i < 8; i++) quoted.add(new DaveContext.Card("<>", "<".repeat(120), "<".repeat(80)));
        final String escaped = DaveContext.json(new DaveContext.Plan("p", "n", "d", 1, false, "", quoted), false);
        assertTrue(escaped.length() <= DaveContext.MAX_CHARS, escaped.length() + " characters");
    }

    @Test
    void aPlanLeftOutIsFlagged() {
        assertEquals(
            "too-large",
            read(DaveContext.json(plan(1, 3), true)).get("attachment")
                .getAsString());
    }

    @Test
    void theTierIsTheHighestCardTier() {
        assertEquals("IV", DaveContext.topTier(List.of("LV", "IV", "HV", "NONE")));
        assertEquals("LuV", DaveContext.topTier(List.of("LuV", "EV")));
        assertEquals("", DaveContext.topTier(List.of("NONE", "")));
    }
}
