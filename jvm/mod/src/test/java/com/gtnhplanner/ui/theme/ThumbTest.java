package com.gtnhplanner.ui.theme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The arithmetic every scroll bar in the planner shares: a thumb taken anywhere follows the mouse pixel for pixel,
 * stops at both ends, and a press on the track centres it there. Whether a real mouse reaches it in the game is
 * {@code tools/dev/scrollbar-drag.py}'s check.
 */
class ThumbTest {

    /** Lists of many lengths in views of many heights, the track not starting at zero. */
    private static void eachLayout(final java.util.function.BiConsumer<Thumb, int[]> check) {
        for (final int visible : new int[] { 40, 120, 233, 500 }) {
            for (final int content : new int[] { visible + 1, visible + 37, visible * 2, visible * 9, 20_000 }) {
                check.accept(new Thumb(14).layout(18, visible, content), new int[] { 18, visible, content });
            }
        }
    }

    @Test
    void aThumbTakenAnywhereFollowsTheMousePixelForPixel() {
        eachLayout((t, l) -> {
            final int start = l[0], visible = l[1], travel = visible - t.size();
            for (final int grabAt : new int[] { 0, t.size() / 2, t.size() - 1 }) {
                final int pressed = start + grabAt;
                t.press(pressed, 0);
                for (int d = 0; d <= travel; d++) {
                    final int scroll = t.to(pressed + d);
                    // Where the scroll puts the thumb is where the mouse put it, give or take the rounding of a
                    // scroll that moves more slowly than the thumb (content barely longer than the view).
                    final int slack = t.max() >= travel ? 0 : 1;
                    final int off = Math.abs(t.at(scroll) - (start + d));
                    assertTrue(
                        off <= slack,
                        "visible " + visible + " content " + l[2] + ": moved " + d + ", off " + off);
                }
                t.release();
            }
        });
    }

    @Test
    void itStopsAtBothEnds() {
        eachLayout((t, l) -> {
            final int start = l[0], visible = l[1];
            t.press(start + 1, 0);
            assertEquals(t.max(), t.to(start + 10 * visible), "dragged far past the end");
            assertEquals(
                start + visible,
                t.at(t.to(start + 10 * visible)) + t.size(),
                "the thumb's end on the track's");
            assertEquals(0, t.to(start - 10 * visible), "dragged far past the start");
            assertEquals(start, t.at(0));
            assertEquals(t.max(), t.clamp(t.max() + 500));
            assertEquals(0, t.clamp(-500));
        });
    }

    @Test
    void aPressOnTheTrackCentresTheThumbOnTheMouse() {
        final Thumb t = new Thumb(14).layout(0, 200, 1000);
        assertFalse(t.onThumb(150, 0));
        final int scroll = t.press(150, 0);
        assertTrue(Math.abs(t.at(scroll) + t.size() / 2 - 150) <= 1, "thumb centred on the press");
        assertTrue(t.dragging());
        assertTrue(t.release());
        assertFalse(t.dragging());
        assertFalse(t.release(), "a second release is not a drag ending");
    }

    @Test
    void aPressOnTheThumbDoesNotMoveIt() {
        final Thumb t = new Thumb(14).layout(10, 300, 900);
        final int scroll = 250, at = t.at(scroll);
        assertTrue(t.onThumb(at + 5, scroll));
        assertEquals(at, t.at(t.press(at + 5, scroll)));
    }

    @Test
    void itsLengthIsTheViewsShareNeverUnderTheLeast() {
        assertEquals(
            100,
            new Thumb(14).layout(0, 200, 400)
                .size());
        assertEquals(
            14,
            new Thumb(14).layout(0, 200, 100_000)
                .size());
        assertEquals(
            16,
            new Thumb(16).layout(0, 200, 100_000)
                .size());
    }

    @Test
    void whenEverythingFitsThereIsNothingToDrag() {
        final Thumb t = new Thumb(14).layout(5, 200, 150);
        assertFalse(t.scrolls());
        assertEquals(0, t.max());
        assertEquals(5, t.at(40));
        assertEquals(0, t.press(100, 0));
        assertEquals(0, t.to(180));
    }
}
