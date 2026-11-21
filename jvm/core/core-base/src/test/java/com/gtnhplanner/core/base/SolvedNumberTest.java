package com.gtnhplanner.core.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.gtnhplanner.conformance.Conformance;

/**
 * SolvedNumber.clean against conformance/core-base/solved-number/solved-number-vectors.json, bit for bit: 2/3, 1/3 and
 * 20/17 with the engine's noise come back as the nearest doubles to those ratios, 4 near misses come back as 4, and a
 * value legitimately at x.xx4999 or with no small ratio stays. The web runner replays the same rows on both targets.
 */
class SolvedNumberTest {

    private static double bits(final String hex) {
        return Double.longBitsToDouble(Long.parseUnsignedLong(hex, 16));
    }

    private static String hex(final double d) {
        final String h = Long.toHexString(Double.doubleToRawLongBits(d));
        return "0000000000000000".substring(h.length()) + h;
    }

    @Test
    void everyVectorCleansToItsAnswerToTheLastBit() throws Exception {
        final JsonArray rows;
        try (Reader r = new InputStreamReader(
            Conformance.open("core-base/solved-number/solved-number-vectors.json"),
            StandardCharsets.UTF_8)) {
            rows = new JsonParser().parse(r)
                .getAsJsonObject()
                .getAsJsonArray("rows");
        }
        assertTrue(rows.size() >= 20);
        for (final JsonElement e : rows) {
            final JsonArray row = e.getAsJsonArray();
            final double got = SolvedNumber.clean(bits(row.get(0).getAsString()));
            final String want = row.get(1).getAsString();
            if (Double.isNaN(bits(want))) assertTrue(Double.isNaN(got), row.toString());
            else assertEquals(want, hex(got), row.toString());
        }
    }

    @Test
    void theNamedRatiosComeBackAsTheirNearestDoubles() {
        assertEquals(2.0 / 3, SolvedNumber.clean(0.66666666666666496));
        assertEquals(1.0 / 3, SolvedNumber.clean(0.33333333333333248));
        assertEquals(20.0 / 17, SolvedNumber.clean(20.0 / 17 * (1 + 3e-15)));
        assertEquals(4.0, SolvedNumber.clean(4.0));
        assertEquals(4.0, SolvedNumber.clean(3.9999999999999996));
        assertEquals(0.624999, SolvedNumber.clean(0.624999));
    }

    @Test
    void theTitaniumReactorsAmpsPrintOnTheRightSideOfTheHalf() {
        // 480 EU/t at HV (512 V) for 2/3 of the time is 0.625 A: rounds up. As the engine left it, it printed 0.62.
        assertEquals("0.62", Format.powerValue(480 * 0.66666666666666496 / 512));
        assertEquals("0.63", Format.powerValue(480 * SolvedNumber.clean(0.66666666666666496) / 512));
        // A figure that really is under the half stays under it.
        assertEquals("0.62", Format.powerValue(SolvedNumber.clean(0.624999)));
    }
}
