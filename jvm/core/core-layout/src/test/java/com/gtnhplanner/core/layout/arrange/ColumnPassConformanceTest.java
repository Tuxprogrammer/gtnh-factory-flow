package com.gtnhplanner.core.layout.arrange;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhplanner.core.layout.RouterConformanceAccess;
import com.gtnhplanner.core.layout.RouterTuning;

/**
 * The plain column pass alone (the website's {@code arrangeBoardOnce(input, false)}, which neither searches nor asks
 * the judge) against each reference board's {@code plain} candidate: the column pass stands on its own, whatever the
 * optimiser and the free placement do.
 */
class ColumnPassConformanceTest {

    @TestFactory
    List<DynamicTest> thePlainColumnPassLaysEveryReferenceBoardOutTheSame() throws IOException {
        final JsonArray boards = ArrangeConformanceTest.read()
            .getAsJsonArray("boards");
        assertTrue(boards.size() > 0);
        final List<DynamicTest> out = new ArrayList<>();
        for (final JsonElement e : boards) {
            final JsonObject b = e.getAsJsonObject();
            final String name = b.get("name")
                .getAsString();
            out.add(DynamicTest.dynamicTest("board " + name + " plain", () -> {
                final RouterTuning tuning = RouterConformanceAccess.tuning(
                    b.getAsJsonObject("judgeInput")
                        .get("tuning"));
                final Arrange.Input input = ArrangeConformanceTest.input(b, null, tuning);
                final ColumnArrange st = new ColumnArrange();
                st.arrangePrices = ColumnArrange.arrangePrices(tuning);
                st.arrangeAir = Air.makeAirTerm(input.cards(), input.wires(), st.arrangePrices.islandAir());
                final ArrangeResult got = st.arrangeBoardOnce(input, false);
                final ArrangeConformanceTest compare = new ArrangeConformanceTest();
                compare.sameResult(name + " plain", b.getAsJsonObject("plain"), got);
                final List<String> differences = compare.differences();
                if (!differences.isEmpty()) {
                    final StringBuilder s = new StringBuilder(name + ": " + differences.size() + " differences:\n");
                    for (int i = 0; i < Math.min(20, differences.size()); i++) s.append("  ")
                        .append(differences.get(i))
                        .append('\n');
                    fail(s.toString());
                }
            }));
        }
        return out;
    }
}
