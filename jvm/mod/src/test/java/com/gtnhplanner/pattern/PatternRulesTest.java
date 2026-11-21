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
}
