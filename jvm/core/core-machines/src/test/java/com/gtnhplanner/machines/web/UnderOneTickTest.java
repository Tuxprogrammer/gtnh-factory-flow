package com.gtnhplanner.machines.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.conformance.Conformance;

/**
 * conformance/machines/subtick-parallels.json: what the pack's own OverclockCalculator and ParallelHelper's power lines
 * give for very short recipes. The game's multiplier on every multiblock case, then whole compressor cards (the Large
 * Electric Compressor and the Black Hole Compressor on one hatch) as the planner works them: the same crafts a second.
 */
class UnderOneTickTest {

    private static JsonArray cases() throws Exception {
        try (Reader r = new InputStreamReader(
            Conformance.open("machines/subtick-parallels.json"),
            StandardCharsets.UTF_8)) {
            return new JsonParser().parse(r)
                .getAsJsonObject()
                .getAsJsonArray("cases");
        }
    }

    @Test
    void theMultiplierIsTheGamesOnEveryMultiblockCase() throws Exception {
        int checked = 0;
        for (final JsonElement e : cases()) {
            final JsonObject c = e.getAsJsonObject();
            final JsonObject in = c.getAsJsonObject("in");
            final JsonObject game = c.getAsJsonObject("game");
            if (!in.get("multiblock")
                .getAsBoolean()
                || !"ok".equals(
                    game.get("status")
                        .getAsString()))
                continue;
            final JsonObject tail = c.getAsJsonObject("tail");
            final double got = UnderOneTick.multiplier(
                tail.get("duration")
                    .getAsDouble(),
                tail.get("overclocks")
                    .getAsInt(),
                tail.get("heatOverclocksAvailable")
                    .getAsInt(),
                in.get("durationDecreasePerOC")
                    .getAsDouble());
            assertEquals(
                game.get("multiplier")
                    .getAsDouble(),
                got,
                0,
                c.get("name")
                    .getAsString());
            checked++;
        }
        assertTrue(checked >= 50, "cases checked: " + checked);
    }

    @Test
    void aOneTickCompressorRecipeRunsWhatTheGameRuns() {
        // The game's 1-tick Large Electric Compressor at LV runs 8 a tick (multiplier 4 on 2 parallels), not 4.
        final NodeMath.Result r = NodeMath.compute(compressor("Large Electric Compressor", 1), hatch("LV"));
        assertEquals(2, r.machineParallels(), 0);
        assertEquals(8, r.machineParallels() / r.overclock()
            .durationTicks(), 1e-9);
    }

    @Test
    void compressorCardsOnOneHatchMatchTheGameCaseByCase() throws Exception {
        int checked = 0;
        for (final JsonElement e : cases()) {
            final JsonObject c = e.getAsJsonObject();
            final JsonObject in = c.getAsJsonObject("in");
            final String family = c.get("family")
                .getAsString();
            final String machine = "Large Electric Compressor".equals(family) ? family
                : "Black Hole Compressor".equals(family) ? "Pseudostable Black Hole Containment Field" : null;
            if (machine == null || in.get("amps")
                .getAsLong() != 1 || in.get("recipeEut")
                    .getAsLong() != 2)
                continue;
            final JsonObject game = c.getAsJsonObject("game");
            final NodeMath.Result r = NodeMath.compute(
                compressor(
                    machine,
                    in.get("recipeTicks")
                        .getAsInt()),
                hatch(
                    in.get("tier")
                        .getAsString()));
            final String name = c.get("name")
                .getAsString();
            assertEquals(
                in.get("maxParallel")
                    .getAsDouble(),
                r.machineParallels(),
                0,
                name);
            final double gamePerTick = game.get("parallels")
                .getAsDouble()
                / game.get("durationTicks")
                    .getAsDouble();
            assertEquals(gamePerTick, r.machineParallels() / r.overclock()
                .durationTicks(), 1e-9 * gamePerTick, name);
            checked++;
        }
        assertEquals(23, checked);
    }

    private static Web.Recipe compressor(final String machine, final int ticks) {
        final Web.Recipe r = new Web.Recipe();
        r.id = "compressor:test";
        r.name = "test";
        r.kind = "gregtech_machine";
        r.machineType = machine;
        r.durationTicks = ticks;
        r.eut = 2;
        r.minimumTier = "ULV";
        r.specialValue = 0.0;
        r.source = new Web.Source();
        r.source.recipeMap = "gt.recipe.compressor";
        r.inputs = List.of();
        r.outputs = List.of();
        return r;
    }

    private static Web.Node hatch(final String tier) {
        final Web.Node n = new Web.Node();
        n.overclockTier = tier;
        n.hatchVoltageTier = tier;
        n.hatchAmps = 1.0;
        n.powerInputMode = "amps";
        n.parallel = 1.0;
        n.machineCount = 1.0;
        return n;
    }
}
