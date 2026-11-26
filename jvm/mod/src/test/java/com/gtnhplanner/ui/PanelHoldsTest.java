package com.gtnhplanner.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A panel that never heard a button's release still holds it, and sends every later drag to the widget pressed
 * before: the overview's scroll bar pressed after a click on the board never moved. After each release the planner
 * lets go of that button in every panel. That ModularUI's fields are still where it reads them shows in the game
 * ({@code tools/dev/scrollbar-drag.py overview --prime ...}): the tests have no game to load ModularUI's panels in.
 */
class PanelHoldsTest {

    @Test
    void aReleaseEndsAHoldOnThatButtonOnly() {
        assertTrue(PanelHolds.stale(true, 0, 0));
        assertFalse(PanelHolds.stale(true, 1, 0), "the right button held while the left is let go stays held");
        assertFalse(PanelHolds.stale(false, 0, 0), "nothing held, nothing to let go");
    }
}
