package com.gtnhplanner.pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.ui.popup.BoxFit;

class PickLayoutTest {

    /** Make pattern's and Cancel's label widths in the game font, near enough. */
    private static final int MAKE_W = 66, CANCEL_W = 34;

    @Test
    void threeRowsFitAndDoNotScroll() {
        final PickLayout l = new PickLayout(new boolean[] { false }, new int[] { 3 });
        final BoxFit.Frame f = l.frame(1080);
        assertEquals(3 * PickLayout.ROW, f.view());
        assertEquals(0, f.maxScroll());
        assertFalse(f.scrolls());
        // Exactly the box it always was: padding and title, the rows, the footer.
        assertEquals(PickLayout.PAD + PickLayout.TITLE_H + 3 * PickLayout.ROW + PickLayout.FOOT_H, f.height());
        assertArrayEquals(new int[] { 0, 2 }, l.rowAtBox(f, 0, 100, PickLayout.HEADER + 2 * PickLayout.ROW + 5));
    }

    @Test
    void fortyRowsOnAShortScreenScrollBetweenTitleAndKeys() {
        // A shared machine: four recipes with heads, ten products each.
        final PickLayout l = new PickLayout(new boolean[] { true, true, true, true }, new int[] { 10, 10, 10, 10 });
        final int roomH = 240;
        final BoxFit.Frame f = l.frame(roomH);
        assertTrue(f.scrolls());
        assertEquals(roomH - 2 * BoxFit.MARGIN, f.height(), "the box takes the room it has, no more");
        assertEquals(4 * PickLayout.HEAD_H + 40 * PickLayout.ROW - f.view(), f.maxScroll());

        // The keys are inside the box, under the rows' window.
        for (final int[] key : new int[][] { PickLayout.inBox(f, PickLayout.makeRect(MAKE_W)),
            PickLayout.inBox(f, PickLayout.cancelRect(CANCEL_W, MAKE_W)) }) {
            assertTrue(key[0] >= 0 && key[0] + key[2] <= PickLayout.W, "inside the box's width");
            assertTrue(key[1] >= f.bodyBottom() && key[1] + key[3] <= f.height(), "inside the box, below the rows");
        }

        // At the furthest scroll the last row shows whole and a click on it reaches it.
        final int lastTop = l.rowTop(3, 9);
        assertTrue(f.shows(lastTop, PickLayout.ROW, f.maxScroll()));
        assertFalse(f.shows(lastTop, PickLayout.ROW, 0));
        final int boxY = f.bodyTop() + lastTop - f.maxScroll() + PickLayout.ROW / 2;
        assertArrayEquals(new int[] { 3, 9 }, l.rowAtBox(f, f.maxScroll(), 100, boxY));
        assertEquals(f.maxScroll(), f.reveal(lastTop, PickLayout.ROW, 0));

        // A click on the keys, or on the title, never reaches a row hidden behind them.
        final int[] make = PickLayout.inBox(f, PickLayout.makeRect(MAKE_W));
        for (int scroll = 0; scroll <= f.maxScroll(); scroll += 7) {
            assertNull(l.rowAtBox(f, scroll, make[0] + 2, make[1] + 2));
            assertNull(l.rowAtBox(f, scroll, 100, f.footerTop()));
            assertNull(l.rowAtBox(f, scroll, 100, f.bodyTop() - 1));
        }
        // The first row is under the title at no scroll.
        assertArrayEquals(new int[] { 0, 0 }, l.rowAtBox(f, 0, 100, f.bodyTop() + PickLayout.HEAD_H + 1));
    }

    @Test
    void headsAreNotRows() {
        final PickLayout l = new PickLayout(new boolean[] { true, true }, new int[] { 2, 2 });
        assertNull(l.rowAt(100, 0));
        assertArrayEquals(new int[] { 0, 1 }, l.rowAt(100, PickLayout.HEAD_H + PickLayout.ROW));
        assertNull(l.rowAt(100, PickLayout.HEAD_H + 2 * PickLayout.ROW));
        assertArrayEquals(new int[] { 1, 0 }, l.rowAt(100, 2 * PickLayout.HEAD_H + 2 * PickLayout.ROW));
        assertNull(l.rowAt(PickLayout.PAD - 1, PickLayout.HEAD_H), "off the row's left edge");
    }
}
