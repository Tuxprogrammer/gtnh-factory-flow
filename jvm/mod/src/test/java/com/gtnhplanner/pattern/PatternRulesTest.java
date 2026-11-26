package com.gtnhplanner.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * "Pattern this" without the game: which keys of a recipe as Not Enough Energistics packs it a terminal has a slot for,
 * and the sentence a batch ends with.
 */
class PatternRulesTest {

    @Test
    void keysNameTheirSlotUnderTheirOwnPrefixOnly() {
        assertEquals(0, PatternRules.slot("#0", PatternRules.IN));
        assertEquals(31, PatternRules.slot("#31", PatternRules.IN));
        assertEquals(2, PatternRules.slot("Outputs2", PatternRules.OUT));
        assertEquals(-1, PatternRules.slot("#", PatternRules.IN), "a prefix alone names no slot");
        assertEquals(-1, PatternRules.slot("#-1", PatternRules.IN));
        assertEquals(-1, PatternRules.slot("#1a", PatternRules.IN));
        assertEquals(-1, PatternRules.slot("Outputs0", PatternRules.IN), "an output key is not an input");
        assertEquals(-1, PatternRules.slot("#12345", PatternRules.IN), "past four digits is refused, not overflowed");
    }

    @Test
    void stacksPastTheTerminalsSlotsAreCounted() {
        final List<String> nine = List.of("#0", "#1", "#2", "#3", "#4", "#5", "#6", "#7", "#8");
        assertEquals(0, PatternRules.beyond(nine, PatternRules.IN, 9));
        assertEquals(1, PatternRules.beyond(List.of("#0", "#9"), PatternRules.IN, 9));
        assertEquals(2, PatternRules.beyond(List.of("Outputs0", "Outputs3", "Outputs4"), PatternRules.OUT, 3));
        assertEquals(0, PatternRules.beyond(List.of("Outputs31"), PatternRules.OUT, 32));
    }

    @Test
    void aBatchSaysWhatWasMadeAndWhyTheRestWasNot() {
        assertEquals("Made a pattern", PatternRules.summary(PatternRules.NONE.plus(PatternRules.MADE, 0)));
        final PatternRules.Tally two = PatternRules.NONE.plus(PatternRules.MADE, 0)
            .plus(PatternRules.MADE, 0);
        assertEquals("Made 2 patterns", PatternRules.summary(two));
        assertTrue(two.clean());
        assertEquals(
            "No blank patterns in your inventory",
            PatternRules.summary(PatternRules.NONE.plus(PatternRules.NO_BLANK, 0)));
        assertEquals(
            "Made a pattern. Out of blank patterns for 1 more",
            PatternRules.summary(
                PatternRules.NONE.plus(PatternRules.MADE, 0)
                    .plus(PatternRules.NO_BLANK, 0)));
        assertEquals(
            "This recipe cannot be patterned",
            PatternRules.summary(PatternRules.NONE.plus(PatternRules.CANNOT, 0)));
        assertEquals(
            "Made a pattern. 1 recipe cannot be patterned",
            PatternRules.summary(
                PatternRules.NONE.plus(PatternRules.MADE, 0)
                    .plus(PatternRules.CANNOT, 0)));
        assertEquals(
            "This server cannot make patterns",
            PatternRules.summary(PatternRules.NONE.plus(PatternRules.NO_SERVER, 0)));
        final PatternRules.Tally cut = PatternRules.NONE.plus(PatternRules.MADE, 2);
        assertFalse(cut.clean(), "a pattern with stacks left out is not a clean result");
        assertEquals("Made a pattern. 2 stacks did not fit the terminal and were left out", PatternRules.summary(cut));
        assertEquals("Nothing to pattern", PatternRules.summary(PatternRules.NONE));
    }

    @Test
    void aPatternGoesToAnEmptySlotOrNowhere() {
        assertEquals(4, PatternRules.slotFor(4, 0, 5), "a free slot, the blank's stack kept");
        assertEquals(-1, PatternRules.slotFor(-1, 0, 5), "a full inventory in survival: nowhere");
        assertEquals(7, PatternRules.slotFor(-1, 7, 1), "the last blank of its stack leaves its slot for the pattern");
        assertEquals(7, PatternRules.slotFor(3, 7, 1), "the blank's own slot even with others free");
        assertEquals(-1, PatternRules.slotFor(-1, -1, 0), "a full inventory in creative: nowhere, nothing made");
        assertEquals(2, PatternRules.slotFor(2, -1, 0), "creative with a free slot");
    }

    @Test
    void roomCountsFreeSlotsAndTheSlotsBlanksLeave() {
        final int[] none = {};
        assertEquals(new PatternRules.Room(27, false), PatternRules.room(27, none, true, 55), "creative: free slots");
        assertEquals(new PatternRules.Room(0, false), PatternRules.room(0, none, true, 55), "creative, full: none");
        assertEquals(new PatternRules.Room(3, false), PatternRules.room(3, none, true, 3), "no more than needed");
        assertEquals(
            new PatternRules.Room(1, true),
            PatternRules.room(0, new int[] { 1 }, false, 5),
            "survival, full but for one blank alone: its slot takes the pattern, then no blank is left");
        assertEquals(
            new PatternRules.Room(0, false),
            PatternRules.room(0, new int[] { 16 }, false, 5),
            "survival, full, a stack of blanks: taking one frees nothing");
        assertEquals(
            new PatternRules.Room(4, true),
            PatternRules.room(30, new int[] { 4 }, false, 55),
            "survival with room for more: bounded by the blanks");
        assertEquals(
            new PatternRules.Room(2, false),
            PatternRules.room(1, new int[] { 1, 5 }, false, 10),
            "a stack used up frees its slot; then the room runs out first");
        assertEquals(
            new PatternRules.Room(4, true),
            PatternRules.room(2, new int[] { 3, 1 }, false, 10),
            "both stacks used up free their slots; then the blanks run out");
    }

    @Test
    void noRoomSaysWhatWasMadeAndWhatWasNot() {
        assertEquals(
            "No free inventory slots. Nothing was made",
            PatternRules.summary(PatternRules.NONE.plus(PatternRules.NO_ROOM, 0)));
        PatternRules.Tally t = PatternRules.NONE;
        for (int i = 0; i < 2; i++) t = t.plus(PatternRules.MADE, 0);
        for (int i = 0; i < 3; i++) t = t.plus(PatternRules.NO_ROOM, 0);
        assertFalse(t.clean());
        assertEquals(5, t.total());
        assertEquals("Made 2 patterns. No room in your inventory for 3 more", PatternRules.summary(t));
    }

    @Test
    void anOutcomeThisSideDoesNotKnowIsNeverMade() {
        final PatternRules.Tally t = PatternRules.NONE.plus((byte) 9, 0)
            .plus((byte) -1, 0);
        assertEquals(0, t.made());
        assertEquals(2, t.cannot());
        assertFalse(t.clean());
    }
}
