package com.gtnhplanner.ui.popup;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class BoxFitTest {

    private static final int[] ROOM = { 10, 20, 640, 360 };

    @Test
    void aBoxThatFitsKeepsItsSizeAndPlace() {
        assertArrayEquals(new int[] { 200, 100 }, BoxFit.size(ROOM, 200, 100));
        assertArrayEquals(new int[] { 50, 60 }, BoxFit.place(ROOM, 50, 60, 200, 100));
    }

    @Test
    void aBoxTooBigIsCutToTheRoomLessTheMargin() {
        assertArrayEquals(new int[] { 636, 356 }, BoxFit.size(ROOM, 2000, 3000));
    }

    @Test
    void aBoxIsNeverPlacedPartlyOffTheRoom() {
        assertArrayEquals(
            new int[] { 10 + 640 - 200 - 2, 20 + 360 - 100 - 2 },
            BoxFit.place(ROOM, 9999, 9999, 200, 100));
        assertArrayEquals(new int[] { 12, 22 }, BoxFit.place(ROOM, -50, -50, 200, 100));
    }

    @Test
    void aFrameThatFitsDoesNotScroll() {
        final BoxFit.Frame f = BoxFit.frame(360, 20, 100, 30);
        assertEquals(150, f.height());
        assertEquals(100, f.view());
        assertFalse(f.scrolls());
        assertEquals(0, f.clamp(50));
    }

    @Test
    void aTallFrameKeepsItsHeaderAndFooterAndScrollsItsBody() {
        final BoxFit.Frame f = BoxFit.frame(360, 20, 5000, 30);
        assertEquals(356, f.height());
        assertEquals(306, f.view());
        assertEquals(5000 - 306, f.maxScroll());
        assertEquals(326, f.footerTop());
        assertEquals(-1, f.contentY(f.footerTop(), 0));
        assertEquals(-1, f.contentY(19, 0));
        assertEquals(40, f.contentY(30, 30));
    }

    @Test
    void aRoomTooShortForHeaderAndFooterShowsNoBody() {
        final BoxFit.Frame f = BoxFit.frame(40, 20, 100, 30);
        assertEquals(0, f.view());
        assertEquals(50, f.height());
    }

    /** Whether a box at {@code at}, {@code w} by {@code h}, covers the point. */
    private static boolean covers(final int[] at, final int w, final int h, final int x, final int y) {
        return x >= at[0] && x < at[0] + w && y >= at[1] && y < at[1] + h;
    }

    @Test
    void aBoxOpensUnderThePointWhenThereIsRoom() {
        assertArrayEquals(new int[] { 100, 104 }, BoxFit.beside(ROOM, 100, 100, 200, 100));
    }

    @Test
    void aBoxOpensOverThePointNearTheRoomsFoot() {
        final int[] at = BoxFit.beside(ROOM, 100, 350, 200, 100);
        assertArrayEquals(new int[] { 100, 350 - 4 - 100 }, at);
    }

    @Test
    void onAShortRoomTheBoxGoesBesideThePointNeverUnderThePointer() {
        // The spare box on a 150 px screen: 202 by 124 in a room 560 by 150. Neither over nor under has room.
        final int[] room = { 0, 0, 560, 150 };
        for (int y = 0; y < 150; y += 7) for (int x = 0; x < 560; x += 13) {
            final int[] at = BoxFit.beside(room, x, y, 202, 124);
            assertFalse(covers(at, 202, 124, x, y), "box at " + at[0] + "," + at[1] + " covers " + x + "," + y);
            assertArrayEquals(at, BoxFit.place(room, at[0], at[1], 202, 124), "box off the room");
        }
    }

    @Test
    void aBoxWithNoRoomOnAnySideStillOpensInTheRoom() {
        final int[] room = { 0, 0, 210, 130 };
        final int[] at = BoxFit.beside(room, 105, 65, 202, 124);
        assertArrayEquals(BoxFit.place(room, 105, 69, 202, 124), at);
    }
}
