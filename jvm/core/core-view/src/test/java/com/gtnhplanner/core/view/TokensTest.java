package com.gtnhplanner.core.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** The generated class says what tokens.json says, and every mix has the bytes the website's generator expects. */
class TokensTest {

    private static JsonObject read(final String file) throws IOException {
        try (Reader r = Files.newBufferedReader(Paths.get("tokens", file), StandardCharsets.UTF_8)) {
            return new JsonParser().parse(r)
                .getAsJsonObject();
        }
    }

    private static int argb(final String hex) {
        return 0xFF000000 | Integer.parseInt(hex.substring(1), 16);
    }

    @Test
    void palette() throws Exception {
        final JsonObject p = read("tokens.json").getAsJsonObject("colors")
            .getAsJsonObject("palette");
        assertEquals(argb(p.get("surface").getAsString()), Tokens.Palette.SURFACE);
        assertEquals(argb(p.get("wireNo").getAsString()), Tokens.Palette.WIRE_NO);
        assertEquals(argb(p.get("promptOff").getAsString()), Tokens.Palette.PROMPT_OFF);
        assertEquals(0xFFFFD257, Tokens.Palette.GOLD);
    }

    @Test
    void tiers() throws Exception {
        final JsonArray tiers = read("tokens.json").getAsJsonArray("tiers");
        assertEquals(tiers.size(), Tokens.Tiers.ALL.length);
        for (int i = 0; i < tiers.size(); i++) {
            final JsonObject t = tiers.get(i)
                .getAsJsonObject();
            final Tokens.Tiers.Tier got = Tokens.Tiers.ALL[i];
            assertEquals(
                t.get("name")
                    .getAsString(),
                got.name());
            assertEquals(
                argb(
                    t.get("background")
                        .getAsString()),
                got.background());
            assertEquals(
                t.get("underline")
                    .getAsBoolean(),
                got.underline());
        }
        assertEquals(0xFF111111, Tokens.Tiers.ALL[2].text());
    }

    @Test
    void mixesMatchTheTableTheWebsiteIsHeldTo() throws Exception {
        final JsonObject expected = read("mixes.expected.json");
        assertEquals(expected.entrySet().size(), 36);
        for (final Map.Entry<String, JsonElement> e : expected.entrySet()) {
            final String[] key = e.getKey()
                .split("\\.");
            final String name = (key[0] + "_" + key[1].replaceAll("([a-z0-9])([A-Z])", "$1_$2")).toUpperCase();
            final int got = (int) Tokens.KindMixes.class.getField(name)
                .get(null);
            assertEquals(argb(e.getValue().getAsString()), got, e.getKey());
        }
    }

    @Test
    void geometry() throws Exception {
        final JsonObject g = read("tokens.json").getAsJsonObject("geometry");
        assertEquals(
            g.getAsJsonObject("grid")
                .get("BOARD_GRID")
                .getAsInt(),
            Tokens.Grid.BOARD_GRID);
        assertEquals(8, Tokens.Wire.LANE_FRACTIONS.length);
        assertEquals(0.3125, Tokens.Wire.LANE_FRACTIONS[1], 0);
        assertEquals(1.0, Tokens.Wire.LANE_FRACTIONS[7], 0);
        assertEquals(42, Tokens.Card.PORT_ROW_HEIGHT_MOD);
        assertEquals(40, Tokens.Card.PORT_ROW_HEIGHT_WEB);
        assertTrue(Tokens.Size.STORAGE_NODE_WIDTH == Tokens.Card.RAIL_WIDTH + 4);
        assertEquals(1.9, Tokens.Pulse.GLOW, 0);
    }
}
