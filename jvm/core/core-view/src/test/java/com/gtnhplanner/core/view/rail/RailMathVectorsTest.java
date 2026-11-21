package com.gtnhplanner.core.view.rail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhplanner.core.view.corpus.ViewCorpus;

/**
 * The rail's arithmetic against the website's: the vectors of {@code conformance/view/rail/math.json}, which the
 * website's own functions made (see the corpus README). The files are the test; nothing here restates a rule.
 */
class RailMathVectorsTest {

    private static void assertSame(final double[] expected, final double[] actual, final String what) {
        assertEquals(expected.length, actual.length, what);
        for (int i = 0; i < expected.length; i++) assertEquals(expected[i], actual[i], 0.0, what + " [" + i + "]");
    }

    @Test
    void rateBoxAndShareArithmeticMatches() throws IOException {
        final JsonObject m = ViewCorpus.open("rail", "math").vectors();
        for (final JsonElement e : m.getAsJsonArray("parseRateInput")) {
            final JsonObject j = e.getAsJsonObject();
            final Double want = ViewCorpus.num(j, "value");
            final double got = RailMath.parseRateInput(j.get("text").getAsString());
            if (want == null) assertTrue(Double.isNaN(got), "'" + j.get("text").getAsString() + "' is not a number");
            else assertEquals(want, got, 0.0, "'" + j.get("text").getAsString() + "'");
        }
        for (final JsonElement e : m.getAsJsonArray("nudged")) {
            final JsonObject j = e.getAsJsonObject();
            assertSame(ViewCorpus.doubles(j.get("out")),
                RailMath.nudged(ViewCorpus.doubles(j.get("shares")), j.get("index").getAsInt(), j.get("step").getAsDouble()),
                "nudged " + j);
        }
        for (final JsonElement e : m.getAsJsonArray("evenShares")) {
            final JsonObject j = e.getAsJsonObject();
            assertSame(ViewCorpus.doubles(j.get("out")), RailMath.evenShares(j.get("count").getAsInt()), "evenShares " + j);
        }
        for (final JsonElement e : m.getAsJsonArray("allAt")) {
            final JsonObject j = e.getAsJsonObject();
            assertSame(ViewCorpus.doubles(j.get("out")), RailMath.allAt(j.get("count").getAsInt(), j.get("index").getAsInt()),
                "allAt " + j);
        }
        for (final JsonElement e : m.getAsJsonArray("shareAtX")) {
            final JsonObject j = e.getAsJsonObject();
            assertEquals(j.get("out").getAsDouble(),
                RailMath.shareAtX(j.get("x").getAsDouble(), j.get("left").getAsDouble(), j.get("width").getAsDouble()),
                0.0, "shareAtX " + j);
        }
        for (final JsonElement e : m.getAsJsonArray("sameShares")) {
            final JsonObject j = e.getAsJsonObject();
            assertEquals(j.get("out").getAsBoolean(), RailMath.sameShares(ViewCorpus.doubles(j.get("a")), ViewCorpus.doubles(j.get("b"))),
                "sameShares " + j);
        }
        for (final JsonElement e : m.getAsJsonArray("wheelStep")) {
            final JsonObject j = e.getAsJsonObject();
            assertEquals(j.get("out").getAsInt(),
                RailMath.wheelStep(j.get("ctrl").getAsBoolean(), j.get("shift").getAsBoolean()));
        }
        for (final JsonElement e : m.getAsJsonArray("steppedRate")) {
            final JsonObject j = e.getAsJsonObject();
            assertEquals(j.get("out").getAsDouble(),
                RailMath.steppedRate(j.get("shown").getAsDouble(), j.get("direction").getAsInt(),
                    j.get("ctrl").getAsBoolean(), j.get("shift").getAsBoolean()),
                0.0, "steppedRate " + j);
        }
    }

    @Test
    void peakCountAndPowerDialMatch() throws IOException {
        final JsonObject m = ViewCorpus.open("rail", "math").vectors();
        for (final JsonElement e : m.getAsJsonArray("peakCount")) {
            final JsonObject j = e.getAsJsonObject();
            assertEquals(j.get("out").getAsDouble(),
                RailMath.peakCount(j.get("solved").getAsDouble(), ViewCorpus.num(j, "set"),
                    j.get("keepSolved").getAsBoolean()),
                0.0, "peakCount " + j);
        }
        for (final JsonElement e : m.getAsJsonArray("dial")) {
            final JsonObject j = e.getAsJsonObject();
            final String unit = j.get("unit").getAsString();
            final String tier = ViewCorpus.str(j, "ownTier");
            assertEquals(ViewCorpus.num(j, "divisor"), RailMath.divisor(unit, tier), "divisor " + j);
            assertEquals(j.get("value").getAsDouble(), RailMath.dialValue(j.get("euT").getAsDouble(), unit, tier),
                0.0, "value " + j);
            assertEquals(j.get("suffix").getAsString(), RailMath.dialSuffix(unit, tier), "suffix " + j);
        }
    }

}
