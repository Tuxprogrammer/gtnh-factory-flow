package com.gtnhplanner.machines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.conformance.Conformance;
import com.gtnhplanner.core.machines.GameRates;

/**
 * Bees against the website's own bee functions (passive-production.ts, machine-effects.ts, bee-maths.ts), written by
 * jvm/mod/tools/dev/bees: a card in every housing on every speed gene, climate, frame set, aura, Alveary part, Mega
 * Apiary jelly and tier, and Industrial Apiary upgrade, at the pack's bee yield of 1 and 4. The website's output
 * multiplier on a base chance must be what the model makes of it: in Forestry's housings the expected items a bee
 * cycle (two rolls for a product, one for a specialty, each at most certain, times the bee yield) over the chance; in
 * the Industrial Apiary the uncapped rolls times the share of its acceleration a queen's life allows; in the Mega
 * Apiary the website's older scaling. The Industrial Apiary's acceleration must also give the same duration.
 */
class BeesGoldensTest {

    private static final String FILE = "machines/bee-goldens.jsonl.gz";

    /**
     * A pure-bred bee with one product and one specialty, as the goldens' recipe has them; its lifespan the website's
     * Normal gene (40 cycles), which it uses when the recipe carries none.
     */
    private static final Bees.Species SPECIES = new Bees.Species(
        "gregtech.bee.speciesPulsatingiron",
        "Pulsating Iron",
        0.6f,
        40,
        false,
        List.of(
            new Bees.Product("forestry:beecombs@7", "Parched Comb", 1, 0.3f, false),
            new Bees.Product("gregtech:gt.comb@18", "Pulsating Iron Comb", 1, 0.15f, true)));

    @AfterEach
    void noRates() {
        GameRates.install(GameRates.NONE);
    }

    @Test
    void matchesTheWebsite() throws Exception {
        final InputStream raw = Conformance.open(FILE);
        int cases = 0;
        final Map<String, Integer> byHousing = new HashMap<>();
        final List<String> mismatches = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(
            new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null;) {
                if (line.isEmpty()) continue;
                final JsonObject c = new JsonParser().parse(line)
                    .getAsJsonObject();
                cases++;
                byHousing.merge(
                    c.get("housing")
                        .getAsString(),
                    1,
                    Integer::sum);
                check(c, mismatches);
            }
        }
        assertTrue(cases > 20000, "cases: " + cases);
        assertEquals(Bees.HOUSINGS.size(), byHousing.size(), "housings: " + byHousing);
        assertEquals(
            List.of(),
            mismatches.subList(0, Math.min(20, mismatches.size())),
            mismatches.size() + " mismatches");
    }

    private static void check(final JsonObject c, final List<String> mismatches) {
        final Map<String, Object> settings = new HashMap<>();
        for (final Map.Entry<String, JsonElement> e : c.getAsJsonObject("tiers")
            .entrySet())
            settings.put(
                e.getKey(),
                e.getValue()
                    .getAsString());
        final String housing = c.get("housing")
            .getAsString();
        settings.put(Bees.HOUSING, housing);
        if (c.has("tier")) settings.put(
            Bees.VOLTAGE,
            c.get("tier")
                .getAsString());
        GameRates.install(new GameRates(c.has("beesYield") ? c.get("beesYield").getAsDouble() : 1, 1, 1));
        final Bees.Setup s = Bees.setup(settings, SPECIES);
        final String at = housing + " " + c.getAsJsonObject("tiers") + (c.has("beesYield") ? " yield " + c.get("beesYield") : "");
        if (!s.housing()
            .key()
            .equals(housing)) mismatches.add(at + ": housing " + s.housing());

        // A term of nothing (a Gentle and two Necrotic frames in an Apiary) is a hair either side of 0 in floats and in
        // doubles: no bees on either side, but not the same hair.
        if (s.housing() != Bees.MEGA_APIARY && Math.abs(Bees.term(s)) < 1e-5) return;
        final Bees.Product product = SPECIES.products()
            .get(0), special = SPECIES.products()
                .get(1);
        final double productMultiplier, specialtyMultiplier;
        if (s.housing() == Bees.MEGA_APIARY) {
            productMultiplier = specialtyMultiplier = Bees.megaMultiplier(s);
        } else if (s.housing()
            .industrial()) {
            // Per bee cycle, as the website counts it: the rolls uncapped, times the share of the acceleration the
            // queen's life allows (a short life stops at 100 ticks and makes up only whole multiples).
            final Bees.Run run = Bees.run(s, SPECIES);
            final double share = (double) run.divisor() * run.factor() / run.acceleration();
            final float p = Bees.productionModifier(s), t = s.housing()
                .t(),
                speed = s.speed()
                    .value();
            productMultiplier = s.climate()
                .works()
                    ? (Bees.finalChance(product.chance(), speed, p, t)
                        + Bees.finalChance(product.chance() / 2f, speed, p, t)) / product.chance() * share
                    : 0;
            specialtyMultiplier = Bees.jubilant(s, SPECIES)
                ? Bees.finalChance(special.chance(), speed, p, t) / special.chance() * share
                : 0;
        } else {
            final Map<String, Double> cycle = Bees.perCycle(s, SPECIES);
            productMultiplier = cycle.get(product.id()) / product.chance();
            specialtyMultiplier = cycle.get(special.id()) / special.chance();
        }
        near(at + " product", c.get("product"), productMultiplier, mismatches);
        near(at + " specialty", c.get("specialty"), specialtyMultiplier, mismatches);

        // The Industrial Apiary's acceleration: the website's duration multiplier is one over it.
        final double duration = s.housing()
            .industrial()
                ? 1.0 / (1 << s.acceleration()
                    .speed())
                : 1;
        near(at + " duration", c.get("duration"), duration, mismatches);
    }

    private static void near(final String what, final JsonElement expected, final double actual,
        final List<String> mismatches) {
        final double want = expected.getAsDouble();
        // Floats on the game's side (0.3f, 17.199268f), doubles on the website's.
        if (Math.abs(want - actual) > 1e-6 * Math.max(1, Math.abs(want)))
            mismatches.add(what + ": website " + want + ", model " + actual);
    }
}
