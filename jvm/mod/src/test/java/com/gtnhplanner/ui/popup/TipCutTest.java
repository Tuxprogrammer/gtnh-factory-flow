package com.gtnhplanner.ui.popup;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** A tooltip taller than the screen is drawn cut to it (its lines read from the top, then an ellipsis line). */
class TipCutTest {

    @Test
    void aTipThatFitsIsWholeAndATallerOneIsCutToTheScreen() {
        assertEquals(120, Tip.shown(120, 270));
        // 2 pixels clear at the top and at the bottom of a 270 high screen.
        assertEquals(266, Tip.shown(900, 270));
        // Never under one line and its padding, however short the screen.
        assertEquals(20, Tip.shown(900, 10));
    }
}
