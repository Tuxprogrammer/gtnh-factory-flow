package com.gtnhplanner.machines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.gtnhplanner.conformance.Conformance;
import com.gtnhplanner.core.base.JsonTree;
import com.gtnhplanner.core.machines.GameRates;

/**
 * Bees against the website's own bee functions (passive-production.ts, machine-effects.ts, bee-maths.ts), written by
 * jvm/mod/tools/dev/bees: a card in every housing on every speed gene, climate, frame set, aura, Alveary part, Mega
 * Apiary jelly and tier, and Industrial Apiary upgrade, at the pack's bee yield of 1 and 4. The website's output
 * multiplier on a base chance must be what the model makes of it: in Forestry's housings the expected items a bee
 * cycle (two rolls for a product, one for a specialty, each at most certain, times the bee yield) over the chance; in
 * the Industrial Apiary the uncapped rolls times the share of its acceleration a queen's life allows; in the Mega
 * Apiary the website's older scaling. The Industrial Apiary's acceleration must also give the same duration. The
 * model's answer is {@link BeeReadout}'s, the one the compiled core gives in the browser, judged by
 * {@link GoldenCompare#bee}, the rule tools/web-core/machines.mjs applies to both browser targets.
 */
class BeesGoldensTest {

    private static final String FILE = "machines/bee-goldens.jsonl.gz";

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
                final Map<String, Object> c = JsonTree.object(JsonTree.parse(line));
                cases++;
                byHousing.merge(JsonTree.str(c, "housing"), 1, Integer::sum);
                final Map<String, Object> card = new LinkedHashMap<>();
                for (final String key : List.of("housing", "tiers", "tier", "beesYield")) card.put(key, c.get(key));
                final String diff = GoldenCompare
                    .bee(c, JsonTree.object(JsonTree.parse(BeeReadout.read(JsonTree.write(card)))));
                if (diff != null) mismatches
                    .add(c.get("housing") + " " + JsonTree.write(c.get("tiers")) + " yield " + c.get("beesYield") + ": " + diff);
            }
        }
        assertTrue(cases > 20000, "cases: " + cases);
        assertEquals(Bees.HOUSINGS.size(), byHousing.size(), "housings: " + byHousing);
        assertEquals(
            List.of(),
            mismatches.subList(0, Math.min(20, mismatches.size())),
            mismatches.size() + " mismatches");
    }
}
