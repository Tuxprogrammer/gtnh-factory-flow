package com.gtnhplanner.ui.card;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.base.RateUnit;
import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.core.view.TextMeasure;
import com.gtnhplanner.core.view.card.CardInput;
import com.gtnhplanner.core.view.card.CardView;
import com.gtnhplanner.core.view.card.CardViews;
import com.gtnhplanner.core.view.special.SpecialInput;
import com.gtnhplanner.core.view.special.SpecialView;
import com.gtnhplanner.core.view.special.SpecialViews;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.power.CustomRate;
import com.gtnhplanner.power.FluidTank;

/**
 * A holder card in the mod draws its own parts from the shared special rules (core-view SpecialViews): its facts as
 * the rules' input, and the rules' strip back as the card's own setting controls, so a chip still presses its setting.
 */
class SpecialInputsTest {

    /** Six px a character: no game font in a test. */
    private static final TextMeasure SIX = (style, text) -> 6.0 * text.length();

    private static CardModel holder(final Node n) {
        return CardModel.of(n, null, (output, index) -> true);
    }

    /**
     * A custom rate card at {@code perSecond}, holding {@code resource} (null for none). Its port is the game's to
     * make,
     * so a test card holds the resource with no port: a resource this game lacks, which the card draws as empty.
     */
    private static Node customRate(final String resource, final boolean supply, final double perSecond) {
        com.gtnhplanner.harness.GtnhFlowLoader.ensureDefaultMachineProfile();
        final Node n = new Node(Uuid.randomUUID(), 0, 0);
        n.powerSource = CustomRate.ID;
        n.machineName = CustomRate.SOURCE.name();
        n.powerSettings.put(CustomRate.RATE, Double.toString(perSecond));
        if (resource != null) CustomRate.hold(n, resource, supply);
        else n.powerSettings.put(CustomRate.MODE, supply ? CustomRate.SUPPLY : CustomRate.REQUEST);
        return n;
    }

    @Test
    void anEmptyCustomRateIsItsDialAndTwoSockets() {
        final SpecialInput in = SpecialInputs.holder(holder(customRate(null, false, 3)), new Graph());
        assertEquals(SpecialInput.Kind.CUSTOM_RATE, in.kind());
        assertTrue(
            in.holder()
                .empty());
        assertEquals(
            new SpecialInput.Dial(false, 3, "item", true, true),
            in.holder()
                .dial());
    }

    @Test
    void aCustomRateHoldingAFluidCountsItsDialInLitresAndEuInEu() {
        assertEquals(
            new SpecialInput.Dial(true, 144, "fluid", true, true),
            SpecialInputs.holder(holder(customRate("fluid:water", true, 144)), new Graph())
                .holder()
                .dial());
        // EU is dialled a tick and moves twenty times that a second.
        assertEquals(
            new SpecialInput.Dial(false, 2560, "power", true, true),
            SpecialInputs.holder(holder(customRate(com.gtnhplanner.power.Energy.KEY, false, 128)), new Graph())
                .holder()
                .dial());
    }

    @Test
    void aFluidTankHasNoDial() {
        com.gtnhplanner.harness.GtnhFlowLoader.ensureDefaultMachineProfile();
        final Node n = new Node(Uuid.randomUUID(), 0, 0);
        n.powerSource = FluidTank.ID;
        final SpecialInput in = SpecialInputs.holder(holder(n), new Graph());
        assertEquals(SpecialInput.Kind.TANK, in.kind());
        assertNull(
            in.holder()
                .dial());
    }

    @Test
    void theRulesStripIsTheCardsOwnControlsInTheBoardsUnit() {
        final CardModel m = holder(customRate("fluid:water", true, 144));
        final SpecialInput in = SpecialInputs.holder(m, new Graph());
        final CardInput.Dials dials = new CardInput.Dials(RateUnit.MINUTE, "eu", true);
        final CardView base = CardViews.card(card(), dials, SIX, CardViews.Host.MOD);
        final SpecialView v = SpecialViews.special(in, card(), base, dials, SIX, CardViews.Host.MOD);
        final List<SettingControls.Control> chips = SpecialInputs.controls(v, m);
        assertEquals(2, chips.size());
        assertEquals(
            "power:" + CustomRate.MODE,
            chips.get(0)
                .key());
        assertEquals(
            "power:" + CustomRate.RATE,
            chips.get(1)
                .key());
        assertEquals(
            "8640",
            chips.get(1)
                .value());
        assertEquals(
            60.0,
            v.chips()
                .get(1)
                .edit()
                .scale());
    }

    /** A custom rate card's facts as the card view takes them: no rows, Exactly one. */
    private static CardInput card() {
        return new CardInput(
            "n",
            "Custom Rate",
            false,
            null,
            false,
            false,
            null,
            null,
            1,
            1,
            List.of(),
            null,
            null,
            null,
            null,
            List.of(),
            List.of(),
            new CardInput.Count(com.gtnhplanner.data.flowchart.PinRule.EXACT, 1.0, 1, null, 1, true, false),
            null,
            null,
            List.of(),
            null,
            List.of(),
            null,
            java.util.Map.of(),
            false,
            new CardInput.Menu(false, false, false, false, true, false, false));
    }
}
