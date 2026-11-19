package com.gtnhplanner.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.conformance.Conformance;
import com.gtnhplanner.core.base.JsonTree;
import com.gtnhplanner.core.power.PowerCatalog;

/**
 * conformance/power/rocket-exact.json holds the Large Rocket Engine's answers to the last bit, as the JVM computes them
 * (the game's own arithmetic: its output falls off by cube roots, through core-base's ExactMath.cbrt, fdlibm, the bits
 * of StrictMath.cbrt on every target). The goldens elsewhere agree to 1e-9; this file holds the last bit, which the
 * compiled core on both web targets now matches (src/lib/power/power-core.test.ts). Each row: [fuel key, fuel rate, boost, EU/t bits, air bits], the bits being
 * Double.doubleToLongBits in hex. Rewritten by -PwriteVectors (core-power's build.gradle.kts).
 */
class RocketExactTest {

    private static final String FILE = "power/rocket-exact.json";

    /** One row's answers on this JVM. */
    private static String[] answer(final String fuel, final int rate, final boolean boost) {
        final Map<String, String> settings = new LinkedHashMap<>();
        settings.put("fuel", fuel);
        settings.put("throttle", Integer.toString(rate));
        settings.put("boost", Boolean.toString(boost));
        final PowerModel m = PowerCatalog.get("large-rocket-engine").compute(settings);
        final PowerModel.Flow air = m.inputs().stream().filter(f -> f.name().equals("Air")).findFirst().orElseThrow();
        return new String[] {Long.toHexString(Double.doubleToLongBits(m.euPerTick())),
            Long.toHexString(Double.doubleToLongBits(air.perSecond()))};
    }

    /** Every fuel from 100 to 4000 L/s in steps of 150 (past both knees for most), with and without the boost. */
    private static List<Object[]> rows() {
        final List<Object[]> rows = new ArrayList<>();
        final PowerSetting.Select fuel = (PowerSetting.Select) PowerCatalog.get("large-rocket-engine").setting("fuel");
        for (final PowerSetting.Option option : fuel.options()) {
            for (int rate = 100; rate <= 4000; rate += 150) {
                for (final boolean boost : new boolean[] {false, true}) {
                    rows.add(new Object[] {option.key(), rate, boost});
                }
            }
        }
        return rows;
    }

    private static String render() {
        final StringBuilder out = new StringBuilder("{\n \"about\": \"The Large Rocket Engine to the last bit, as the JVM"
            + " computes it (RocketExactTest writes and checks this file). rows: [fuel key, fuel rate, boost, EU/t bits,"
            + " air per second bits], the bits Double.doubleToLongBits in hex.\",\n \"rows\": [");
        boolean first = true;
        for (final Object[] row : rows()) {
            final String[] a = answer((String) row[0], (Integer) row[1], (Boolean) row[2]);
            out.append(first ? "\n  " : ",\n  ");
            first = false;
            out.append('[').append(quote((String) row[0])).append(", ").append(row[1]).append(", ").append(row[2])
                .append(", \"").append(a[0]).append("\", \"").append(a[1]).append("\"]");
        }
        return out.append("\n ]\n}\n").toString();
    }

    private static String quote(final String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Test
    void theJvmGivesTheFileBackToTheLastBit() throws Exception {
        final Map<String, Object> file = JsonTree.object(JsonTree.parse(Conformance.text(FILE)));
        final List<Object> rows = JsonTree.array(file.get("rows"));
        assertTrue(rows.size() >= 200, "the file is incomplete");
        final List<String> diffs = new ArrayList<>();
        for (final Object r : rows) {
            final List<Object> row = JsonTree.array(r);
            final String[] a = answer((String) row.get(0), ((Number) row.get(1)).intValue(), (Boolean) row.get(2));
            if (!a[0].equals(row.get(3)) || !a[1].equals(row.get(4))) diffs.add(row + " is " + a[0] + " " + a[1]);
        }
        assertEquals(List.of(), diffs.subList(0, Math.min(diffs.size(), 10)), diffs.size() + " differ");
        assertEquals(render(), Conformance.text(FILE), FILE + " is out of date: run writeVectors");
    }

    /** Rewrites conformance/power/rocket-exact.json from this JVM: run with -PwriteVectors. */
    @Test
    void writeVectors() throws Exception {
        if (System.getProperty("gtnh.writeVectors") == null) return;
        Files.writeString(Conformance.path(FILE), render());
    }
}
