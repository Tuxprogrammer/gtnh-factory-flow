package com.gtnhplanner.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** One click-versus-drag rule for the planner: a slow click that wobbles still clicks, at every GUI scale. */
class ClickSlopTest {

    @Test
    void aTwoPixelWobbleEachWayIsStillAClickAtEveryScale() {
        for (int scale = 1; scale <= 6; scale++) {
            // 2 screen pixels each way: under one GUI pixel above scale 2.
            final int d = Math.max(1, 2 / scale);
            assertFalse(ClickSlop.moved(d, d, scale), "scale " + scale);
            assertFalse(ClickSlop.moved(-d, d, scale), "scale " + scale);
        }
    }

    @Test
    void theSlopNeverShrinksInScreenPixelsAsTheScaleGrows() {
        int last = 0;
        for (int scale = 1; scale <= 8; scale++) {
            final int screen = ClickSlop.guiPixels(scale) * scale;
            assertTrue(screen >= ClickSlop.SCREEN, "scale " + scale + ": " + screen);
            assertTrue(screen >= last, "scale " + scale + ": " + screen + " after " + last);
            last = screen;
        }
        assertEquals(8, ClickSlop.guiPixels(1));
        assertEquals(6, ClickSlop.guiPixels(2));
        assertEquals(6, ClickSlop.guiPixels(4));
    }

    @Test
    void aPressThatGoesPastTheSlopAlongEitherAxisIsADrag() {
        assertTrue(ClickSlop.moved(8, 0, 1));
        assertTrue(ClickSlop.moved(0, -8, 1));
        assertFalse(ClickSlop.moved(7, 7, 1));
        assertTrue(ClickSlop.moved(6, 0, 4));
        assertFalse(ClickSlop.moved(5, 5, 4));
    }
}
