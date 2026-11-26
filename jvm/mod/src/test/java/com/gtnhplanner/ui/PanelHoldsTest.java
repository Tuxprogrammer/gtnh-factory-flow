package com.gtnhplanner.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /**
     * The release is read from the input event, because ModularUI gives a release a card or the board took to that
     * draggable and never calls the screen's {@code onMouseRelease}: a click on a card left the board holding the
     * button. Only a button's release counts; a press, a move (button -1) or the wheel lets go of nothing.
     */
    @Test
    void onlyAButtonGoingUpIsARelease() {
        assertEquals(0, PanelHolds.releasedBy(0, false));
        assertEquals(1, PanelHolds.releasedBy(1, false));
        assertEquals(-1, PanelHolds.releasedBy(0, true), "a press");
        assertEquals(-1, PanelHolds.releasedBy(-1, false), "a move or the wheel");
    }

    /** The listener is on the bus: one handler for the input event, run after ModularUI's and on a cancelled event. */
    @Test
    void theListenerHearsCancelledEventsLast() throws NoSuchMethodException {
        final cpw.mods.fml.common.eventhandler.SubscribeEvent on = PanelHolds.class
            .getMethod("onMouse", com.cleanroommc.modularui.api.event.MouseInputEvent.Pre.class)
            .getAnnotation(cpw.mods.fml.common.eventhandler.SubscribeEvent.class);
        assertTrue(on.receiveCanceled(), "ModularUI cancels the event when a draggable takes the release");
        assertEquals(cpw.mods.fml.common.eventhandler.EventPriority.LOWEST, on.priority());
    }
}
