package com.gtnhplanner.core.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.conformance.Conformance;

/** The live-drag rule, and its numbers against the website's copy until the website calls this one. */
class LiveDragTest {

    @Test
    void beatsComeAtMostEverySolveMsAndTheLateOneWaits() {
        final LiveDrag drag = new LiveDrag();
        assertEquals(LiveDrag.Beat.ROUTE, drag.frame(10, true, 1000));
        assertEquals(LiveDrag.Beat.WAIT, drag.frame(10, true, 1020));
        assertEquals(1050, drag.dueAt());
        assertEquals(LiveDrag.Beat.WAIT, drag.frame(10, true, 1049.9));
        assertEquals(LiveDrag.Beat.ROUTE, drag.frame(10, true, 1050));
    }

    @Test
    void wiresHoldPastTheEdgeLimitAndForADragThatMovesNothingTheyRouteRound() {
        final LiveDrag drag = new LiveDrag();
        assertTrue(drag.follows(LiveDrag.ROUTE_EDGE_LIMIT, true));
        assertFalse(drag.follows(LiveDrag.ROUTE_EDGE_LIMIT + 1, true));
        assertFalse(drag.follows(3, false));
        assertEquals(LiveDrag.Beat.HOLD, drag.frame(LiveDrag.ROUTE_EDGE_LIMIT + 1, true, 0));
    }

    @Test
    void aBeatOverBudgetHoldsThatSizeAndLargerUntilWellBelowIt() {
        final LiveDrag drag = new LiveDrag();
        drag.meter(100, LiveDrag.BUDGET_MS);
        assertTrue(drag.follows(100, true), "at the budget is not over it");
        drag.meter(100, LiveDrag.BUDGET_MS + 1);
        assertFalse(drag.follows(100, true));
        assertFalse(drag.follows(80, true));
        assertTrue(drag.follows(79, true));
        drag.meter(200, 500);
        assertFalse(drag.follows(80, true), "the smallest slow size counts");
    }

    private static double constant(final String source, final String name) {
        final Matcher m = Pattern.compile("const " + name + " = ([0-9.]+);")
            .matcher(source);
        assertTrue(m.find(), name + " not found in FactoryFlow.tsx");
        return Double.parseDouble(m.group(1));
    }

    @Test
    void theNumbersAreTheWebsites() throws IOException {
        final Path flow = Conformance.root()
            .getParent()
            .resolve("src/components/flow/FactoryFlow.tsx");
        final String source = Files.readString(flow, StandardCharsets.UTF_8);
        assertEquals(LiveDrag.ROUTE_EDGE_LIMIT, constant(source, "LIVE_DRAG_ROUTE_EDGE_LIMIT"));
        assertEquals(LiveDrag.SOLVE_MS, constant(source, "LIVE_DRAG_SOLVE_MS"));
        assertEquals(LiveDrag.BUDGET_MS, constant(source, "LIVE_DRAG_BUDGET_MS"));
        assertTrue(
            source.contains("edgeCount >= liveDragSlowAtEdgeCountRef.current * " + 0.8),
            "the slow margin is the website's");
    }
}
