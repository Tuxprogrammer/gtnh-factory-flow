package com.gtnhplanner.machines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 * CropsNh against the website's own crop functions: every crop in its 2.9 dataset (179) on a spread of settings under
 * both harvesters, at the pack's IC2 crop yield of 1 and 4, written by tools/audits/export-mod-crop-goldens.ts from the
 * website's passive-production.ts (growth, harvest time, drops, the harvester's setup, machines, power, and the
 * solver's multipliers as machine-effects.ts takes them). The model's answer is {@link CropReadout}'s, the one the
 * compiled core gives in the browser, judged by {@link GoldenCompare#crop}, the rule tools/web-core/machines.mjs applies
 * to both browser targets.
 */
class CropsNhGoldensTest {

    private static final String FILE = "machines/crop-goldens.jsonl.gz";

    @Test
    void matchesTheWebsite() throws Exception {
        final InputStream raw = Conformance.open(FILE);
        int cases = 0;
        final List<String> mismatches = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(
            new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null;) {
                if (line.isEmpty()) continue;
                final Map<String, Object> c = JsonTree.object(JsonTree.parse(line));
                cases++;
                final Map<String, Object> card = new LinkedHashMap<>();
                for (final String key : List.of("stats", "handler", "tiers", "crops", "gameRates")) card
                    .put(key, c.get(key));
                final String diff = GoldenCompare.crop(
                    JsonTree.object(c.get("expected")),
                    JsonTree.object(JsonTree.parse(CropReadout.read(JsonTree.write(card)))));
                if (diff != null) mismatches.add(
                    c.get("crop") + " " + c.get("handler") + " " + JsonTree.write(c.get("tiers")) + " x" + c.get("crops") + ": " + diff);
            }
        }
        assertTrue(cases > 4000, "cases: " + cases);
        assertEquals(
            List.of(),
            mismatches.subList(0, Math.min(20, mismatches.size())),
            mismatches.size() + " mismatches");
    }

    @AfterEach
    void noRates() {
        GameRates.install(GameRates.NONE);
    }
}
