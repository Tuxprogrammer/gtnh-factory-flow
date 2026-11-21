package com.gtnhplanner.core.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.conformance.Conformance;

/**
 * GameMath.powInt against GTUtility.powInt as the game runs it (conformance/machines/coke-oven-discount.json, written
 * from the game's own two methods on the JVM), bit for bit. The web runner replays the same rows on both targets.
 */
class GameMathTest {

    static JsonObject vectors() throws Exception {
        try (Reader r = new InputStreamReader(
            Conformance.open("machines/coke-oven-discount.json"),
            StandardCharsets.UTF_8)) {
            return new JsonParser().parse(r)
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

    @Test
    void powIntIsTheGamesPowerBySquaringToTheLastBit() throws Exception {
        final JsonArray rows = vectors().getAsJsonArray("powInt");
        assertTrue(rows.size() > 200);
        for (final JsonElement e : rows) {
            final JsonArray row = e.getAsJsonArray();
            final double base = bits(row.get(0).getAsString());
            final int exp = row.get(1).getAsInt();
            assertEquals(row.get(2).getAsString(), hex(GameMath.powInt(base, exp)), base + "^" + exp);
        }
    }

    @Test
    void theCokeOvenDiscountsAtTiersOneToFourteenAreTheGamesDoubles() throws Exception {
        final JsonArray tiers = vectors().getAsJsonArray("cokeOvenDiscount");
        assertEquals(14, tiers.size());
        for (final JsonElement e : tiers) {
            final JsonArray row = e.getAsJsonArray();
            assertEquals(row.get(1).getAsString(), hex(GameMath.powInt(0.98, row.get(0).getAsInt())), row.toString());
        }
    }

    @Test
    void squaringAndFdlibmPowDifferWhereTheDiscountWasWrong() {
        // 0.98^4 by squaring ends ...599999, by pow it is the nearest double to the true power.
        assertTrue(GameMath.powInt(0.98, 4) != ExactMath.pow(0.98, 4));
    }
}
