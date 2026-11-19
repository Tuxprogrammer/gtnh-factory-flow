package com.gtnhplanner.core.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * What the game's board asks of the one router beyond the website's own tests (conformance/layout): the sides a wire
 * may meet its box on, drawers met square, the boxes keyed so a move routes again only what it touched, and dots on
 * the website's grid. The old router's tests these stand in for are listed in the layout stream's state note.
 */
class BoxRouterTest {

    private static BoxRouter.Box card(final String key, final int x, final int y) {
        return new BoxRouter.Box(key, x, y, 320, 200, false);
    }

    private static BoxRouter.Wire wire(final String key, final int from, final int to, final int[]... stops) {
        return new BoxRouter.Wire(key, from, to, BoxRouter.ANY, BoxRouter.ANY, 4, List.of(stops));
    }

    @Test
    void aLoopLeavesItsRightSideAndComesBackIntoItsLeft() {
        final List<BoxRouter.Box> boxes = List.of(card("a", 0, 0));
        final BoxRouter.Wire loop = new BoxRouter.Wire("loop", 0, 0, BoxRouter.RIGHT, BoxRouter.LEFT, 4, List.of());
        final List<GridPoint> path = new BoxRouter().route(boxes, List.of(loop), true, null)
            .get("loop")
            .points();
        assertEquals(320, path.get(0)
            .x());
        assertEquals(0, path.get(path.size() - 1)
            .x());
    }

    @Test
    void aDrawerIsMetSquare() {
        final List<BoxRouter.Box> boxes = List.of(card("m", 0, 0), new BoxRouter.Box("d", 900, 600, 116, 48, true));
        final List<GridPoint> path = new BoxRouter().route(boxes, List.of(wire("w", 0, 1)), true, null)
            .get("w")
            .points();
        final GridPoint last = path.get(path.size() - 1), before = path.get(path.size() - 2);
        assertTrue(last.x() == before.x() || last.y() == before.y(), path::toString);
    }

    @Test
    void aBoxWalledInStillGetsItsWire() {
        // A drawer dropped on a card: no way out, yet the wire is drawn (the boxed-in elbow).
        final List<BoxRouter.Box> boxes = List
            .of(new BoxRouter.Box("c", 0, 0, 320, 300, false), new BoxRouter.Box("d", 80, 100, 136, 68, true), card("e", 700, 0));
        final GridRoutedEdge r = new BoxRouter().route(boxes, List.of(wire("sealed", 1, 2)), true, null)
            .get("sealed");
        assertNotNull(r);
        assertTrue(
            r.points()
                .size() >= 2);
    }

    @Test
    void movingABoxRoutesOnlyItsWires() {
        final List<BoxRouter.Box> boxes = new ArrayList<>();
        final List<BoxRouter.Wire> wires = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            boxes.add(card("a" + i, 0, i * 400));
            boxes.add(card("b" + i, 800, i * 400));
            wires.add(wire("w" + i, 2 * i, 2 * i + 1));
        }
        final BoxRouter router = new BoxRouter();
        final Map<String, GridRoutedEdge> before = router.route(boxes, wires, true, null);
        boxes.set(7, card("b3", 840, 1200));
        final IncrementalStats stats = new IncrementalStats();
        final Map<String, GridRoutedEdge> after = router.route(boxes, wires, false, stats);
        assertFalse(stats.fresh);
        assertEquals(1, stats.routed);
        assertEquals(
            before.get("w0")
                .points(),
            after.get("w0")
                .points());
    }

    @Test
    void aDotPastTheBoardStillRoutes() {
        final List<BoxRouter.Box> boxes = List.of(card("a", 0, 0), card("b", 800, 0));
        final BoxRouter router = new BoxRouter();
        router.route(boxes, List.of(wire("far", 0, 1)), true, null);
        final GridRoutedEdge r = router.route(boxes, List.of(wire("far", 0, 1, new int[] { 560, 2000 })), true, null)
            .get("far");
        assertTrue(
            r.points()
                .contains(new GridPoint(560, 2000)),
            () -> r.points()
                .toString());
    }

    @Test
    void pixelsAreWholeAndDropRepeats() {
        final GridRoutedEdge r = new GridRoutedEdge(
            "x",
            List.of(new GridPoint(0, 0), new GridPoint(0.4, 0), new GridPoint(10.5, 3.2)),
            4.0,
            null,
            null,
            null);
        final List<int[]> px = BoxRouter.pixels(r);
        assertEquals(2, px.size());
        assertEquals(11, px.get(1)[0]);
        assertEquals(3, px.get(1)[1]);
    }
}
