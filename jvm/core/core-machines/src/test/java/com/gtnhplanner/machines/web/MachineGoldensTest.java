package com.gtnhplanner.machines.web;

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
import com.gtnhplanner.machines.GoldenCompare;

/**
 * The website's own functions run over real dataset recipes and a spread of nodes (machine-goldens.jsonl.gz, written
 * by its {@code tools/audits/export-mod-machine-goldens.ts}; the command and dataset are in conformance/manifest.json),
 * at the pack's rate-mod factors each case carries: the port must give every number the same, the runtime
 * variants the recipes carry included (the game computes its own in play, machines/game/RuntimeVariants). The numbers
 * come from {@link MachineReadout}, the text readout the compiled core answers in the browser, and are judged by
 * {@link GoldenCompare}, the rules tools/web-core/machines.mjs applies to both browser targets. The Tree Growth
 * Simulator and Bacterial Vat are checked against nothing here: the mod's own models in machines/ are matched to the
 * website on their own.
 */
class MachineGoldensTest {

    private static final String FILE = "machines/machine-goldens.jsonl.gz";

    private final List<String> failures = new ArrayList<>();
    private final Map<String, Integer> failuresByGroup = new LinkedHashMap<>();
    private int cases, skipped;

    @Test
    void portMatchesTheWebsite() throws Exception {
        // GOLDENS_IN=<file.jsonl.gz>[,<file>...] checks other golden files instead (a fresh generator run), each one's
        // failing records written beside it as <file>.fail.jsonl. Gradle does not see the environment: run with --rerun.
        final String other = System.getenv("GOLDENS_IN");
        if (other != null && !other.isEmpty()) {
            final StringBuilder all = new StringBuilder();
            int failed = 0;
            for (final String path : other.split(",")) {
                final MachineGoldensTest run = new MachineGoldensTest();
                try (java.io.PrintWriter dump = new java.io.PrintWriter(
                    new java.io.OutputStreamWriter(
                        new java.io.FileOutputStream(path + ".fail.jsonl"),
                        StandardCharsets.UTF_8))) {
                    run.dump = dump;
                    all.append(path)
                        .append(": ")
                        .append(run.check(new java.io.FileInputStream(path)))
                        .append("\n");
                }
                failed += run.failures.size();
            }
            System.out.println(all);
            assertTrue(failed == 0, all.toString());
            return;
        }
        final String report = check(Conformance.open(FILE));
        System.out.println(report);
        assertTrue(failures.isEmpty(), report);
        // Nothing left out: the file's cases are all read, and only the mod's own models are set aside.
        assertTrue(cases > 40000, "cases: " + cases);
        assertTrue(skipped < cases / 4, "skipped: " + skipped);
    }

    /** Where each failing case goes as one JSON line, when set. */
    private java.io.PrintWriter dump;

    private String check(final InputStream raw) throws Exception {
        final MachineReadout readout = new MachineReadout();
        try (BufferedReader in = new BufferedReader(
            new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isEmpty()) continue;
                final Map<String, Object> c = JsonTree.object(JsonTree.parse(line));
                final String recipeId = JsonTree.str(c, "recipeId");
                GameRates.install(rates(c));
                if (c.containsKey("recipe")) readout.addRecipe(recipeId, JsonTree.write(c.get("recipe")));
                final String group = JsonTree.str(c, "group");
                final int before = failures.size();
                check(c, readout, recipeId, group);
                if (dump != null && failures.size() > before) {
                    final Map<String, Object> o = new LinkedHashMap<>();
                    o.put("case", c.get("case"));
                    o.put("group", group);
                    o.put("variant", c.get("variant"));
                    o.put("recipeId", recipeId);
                    o.put("node", c.get("node"));
                    o.put("diffs", failures.get(failures.size() - 1));
                    dump.println(JsonTree.write(o));
                }
            }
        }
        final StringBuilder report = new StringBuilder(
            cases + " cases, " + failures.size() + " mismatches (" + skipped + " left to the mod's own models)");
        for (final Map.Entry<String, Integer> e : failuresByGroup.entrySet()) report.append("\n  ")
            .append(e.getKey())
            .append(": ")
            .append(e.getValue());
        if (dump == null) for (int i = 0; i < Math.min(40, failures.size()); i++) report.append("\n")
            .append(failures.get(i));
        return report.toString();
    }

    /** The pack's rate-mod factors the case was computed with: its gameRates, each 1 where it has none. */
    private static GameRates rates(final Map<String, Object> c) {
        final Map<String, Object> r = JsonTree.object(c.get("gameRates"));
        return new GameRates(factor(r, "beesYield"), factor(r, "ic2CropsYield"), factor(r, "recipeDurationDivisor"));
    }

    private static double factor(final Map<String, Object> r, final String key) {
        final Double v = JsonTree.boxed(r, key);
        return v != null ? v : 1;
    }

    @AfterEach
    void noRates() {
        GameRates.install(GameRates.NONE);
    }

    private void check(final Map<String, Object> c, final MachineReadout readout, final String recipeId,
        final String group) {
        final String where = "#" + c.get("case") + " " + group + " [" + c.get("variant") + "]";
        String diff;
        try {
            final String text = readout.read(recipeId, JsonTree.write(c.get("node")));
            if (text == null) {
                skipped++;
                return;
            }
            diff = GoldenCompare
                .machine(JsonTree.object(c.get("expected")), JsonTree.object(JsonTree.parse(text)));
        } catch (final RuntimeException | StackOverflowError e) {
            diff = "threw " + e;
        }
        cases++;
        if (diff != null) {
            failures.add(where + ": " + diff);
            failuresByGroup.merge(group, 1, Integer::sum);
        }
    }
}
