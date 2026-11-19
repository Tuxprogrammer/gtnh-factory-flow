package com.gtnhplanner.ui.card;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.view.card.CardModel;
import com.gtnhplanner.core.view.card.Cards;
import com.gtnhplanner.power.PowerModel;

/**
 * The card's geometry without the game: the widths are the test's ({@link Cards#SIX}), so every number here is worked
 * out by hand from CardLayout's constants. The mod's own cards are pinned by its suite and the before/after render.
 */
class CardLayoutTest {

    private static SettingControls.Control chip(final String label, final String value, final boolean icon) {
        return new SettingControls.Control(
            "k:" + label,
            "MACHINE",
            label,
            value,
            icon ? "item:gregtech:gt.blockcasings5:0" : null,
            false,
            true,
            SettingControls.Type.CHOICE,
            false);
    }

    @Test
    void portsStackWithTheirExtraRowsAndThePowerRowFollowsTheInputs() {
        final CardModel m = Cards.recipe(Cards.ports(2, false, 0), Cards.ports(1, true), 30);
        final CardLayout l = new CardLayout(List.of(m), List.of(), 7, false, Cards.SIX);
        final int rails = CardLayout.RAILS_Y, row = CardLayout.ROW;
        assertEquals(rails, l.rowY(false, 0));
        // Input 0 has an extra row under it, so input 1 starts two rows down.
        assertEquals(rails + 2 * row, l.rowY(false, 1));
        assertTrue(l.hasExtra(0, false, 0));
        assertFalse(l.hasExtra(0, false, 1));
        assertEquals(2 * row, l.rowH(0, false, 0));
        // Three input rows, then the power row (the card draws EU).
        assertEquals(rails + 3 * row, l.powerRowY);
        assertEquals(rails + 4 * row, l.railsEnd);
        assertEquals(rails + row / 2, l.anchorY(false, 0));
        assertEquals(7, l.pinsVersion);
        assertEquals(-1, l.footY);
    }

    @Test
    void aCardThatDrawsNothingHasNoPowerRowButACropCardDoes() {
        final CardModel none = Cards.recipe(Cards.ports(1, false), Cards.ports(1, true), 0);
        assertEquals(-1, new CardLayout(List.of(none), List.of(), 0, false, Cards.SIX).powerRowY);
        final CardModel crop = Cards.card(Cards.ports(1, false), Cards.ports(1, true), 0, true, false, 0, 0, null);
        assertEquals(
            CardLayout.RAILS_Y + CardLayout.ROW,
            new CardLayout(List.of(crop), List.of(), 0, false, Cards.SIX).powerRowY);
    }

    @Test
    void chipsWrapToANewRowAtTheCardsEdge() {
        // Each chip: 5 + 6 * label + 4 + 6 * value + 5; with an icon 17 more.
        assertEquals(5 + 24 + 4 + 12 + 5, CardLayout.chipW(Cards.SIX, "Coil", "On", false));
        assertEquals(5 + 17 + 24 + 4 + 12 + 5, CardLayout.chipW(Cards.SIX, "Coil", "On", true));
        final List<SettingControls.Control> chips = List.of(
            chip("A long label here", "value", true),
            chip("Another long label", "more", false),
            chip("Third", "x", false));
        final CardModel m = Cards.recipe(Cards.ports(1, false), Cards.ports(1, true), 0);
        final CardLayout l = new CardLayout(List.of(m), chips, 0, true, Cards.SIX);
        final int w0 = CardLayout.chipW(Cards.SIX, chips.get(0)), w1 = CardLayout.chipW(Cards.SIX, chips.get(1));
        final int y = l.stripY + 4;
        assertArrayEquals(new int[] { CardLayout.PAD, y, w0, CardLayout.CHIP }, l.chipRect(0));
        // The second does not fit beside the first (card width 320): a new row.
        assertTrue(CardLayout.PAD + w0 + 4 + w1 > CardLayout.W - CardLayout.PAD);
        assertArrayEquals(new int[] { CardLayout.PAD, y + CardLayout.CHIP_ROW, w1, CardLayout.CHIP }, l.chipRect(1));
        assertArrayEquals(
            new int[] { CardLayout.PAD + w1 + 4, y + CardLayout.CHIP_ROW, CardLayout.chipW(Cards.SIX, chips.get(2)),
                CardLayout.CHIP },
            l.chipRect(2));
        assertEquals(1, l.chipAt(CardLayout.PAD + 1, y + CardLayout.CHIP_ROW + 1));
        assertEquals(-1, l.chipAt(1, 1));
        // The foot follows the strip: its last row, its chips' height and a margin.
        final int stripEnd = y + CardLayout.CHIP_ROW + CardLayout.CHIP + 4;
        assertEquals(stripEnd + 2 + 1, l.footY);
        assertEquals(l.footY + CardLayout.FOOT_H + 3, l.height);
    }

    @Test
    void aPowerCardsWarningsWrapByTheHostsMeasure() {
        final PowerModel model = new PowerModel(
            100,
            List.of(),
            List.of(),
            List.of(),
            List.of("This warning is long enough to need two lines on the card at six pixels a letter."));
        final CardModel m = Cards.card(
            List.of(),
            Cards.ports(1, true),
            0,
            false,
            false,
            0,
            0,
            new CardModel.Power("test", null, model, List.of(), Map.of()));
        final CardLayout l = new CardLayout(List.of(m), List.of(), 0, false, Cards.SIX);
        final int room = CardLayout.W - 2 * CardLayout.PAD - 4;
        assertEquals(2, l.warningLines.size());
        for (final String line : l.warningLines) assertTrue(Cards.SIX.width(null, line) <= room, line);
        assertEquals(l.warningsY + 2 * 9 + 4 + 4, l.height);
    }
}
