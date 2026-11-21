package com.gtnhplanner.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The overview's width handle against its scroll bar: the handle is laid over the board after the overview, so where
 * the two meet a press goes to the handle, which only follows the mouse sideways. A press on the thumb that went to the
 * handle held the thumb still however far the mouse was pulled down.
 */
class RailHandleTest {

    @Test
    void theWidthHandleLeavesTheWholeScrollBarToTheOverview() {
        for (int w = 120; w <= 400; w++) {
            final int handleLeft = RailHandle.leftOf(w), barLeft = OverviewRail.scrollBarLeft(w);
            final int[] thumb = OverviewRail.thumbX(w);
            assertTrue(handleLeft >= w, "the handle starts at the overview's edge, not inside it, at width " + w);
            assertTrue(barLeft <= thumb[0] && thumb[1] < w, "the bar takes presses on the whole thumb and past it");
            assertTrue(w - thumb[1] >= 2, "the bar takes presses right of the thumb up to the edge");
        }
    }

    @Test
    void theWidthHandleStillReachesOverTheBoard() {
        assertTrue(RailHandle.OUTSIDE >= 7, "the board side keeps a strip easy to catch");
    }
}
