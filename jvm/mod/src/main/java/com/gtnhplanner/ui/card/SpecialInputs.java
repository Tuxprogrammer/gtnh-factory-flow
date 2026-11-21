package com.gtnhplanner.ui.card;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.gtnhplanner.core.view.special.SpecialInput;
import com.gtnhplanner.core.view.special.SpecialView;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.power.CustomRate;
import com.gtnhplanner.power.Energy;
import com.gtnhplanner.power.FluidTank;
import com.gtnhplanner.power.Holders;

/**
 * The mod's side of the shared special card rules: a special card's kind's facts as core-view's {@link SpecialInput},
 * from its node, and the special view's strip as the mod's setting controls (so a chip still presses and steps the
 * card's setting as it did).
 */
public final class SpecialInputs {

    private SpecialInputs() {}

    /**
     * A holder's facts (a custom rate card or a fluid tank); null on any other card. It is empty while it has no port
     * (it holds nothing, or a resource this game lacks), as the card draws it ({@code RecipeCard.emptyHolder}).
     */
    @Nullable
    public static SpecialInput holder(final CardModel m, @Nullable final Graph plan) {
        if (!Holders.is(m.node)) return null;
        final boolean empty = m.inputs.isEmpty() && m.outputs.isEmpty();
        if (FluidTank.is(m.node)) return new SpecialInput(SpecialInput.Kind.TANK, new SpecialInput.Holder(empty, null));
        final String key = CustomRate.resource(m.node);
        final String kind = key == null ? "item"
            : key.startsWith("fluid:") ? "fluid" : Energy.KEY.equals(key) ? "power" : "item";
        boolean mode = true, rate = true;
        for (final SettingControls.Control c : CardSettings.of(m)) {
            if (c.key()
                .equals("power:" + CustomRate.MODE)) mode = SettingPins.pinned(plan, m, c);
            if (c.key()
                .equals("power:" + CustomRate.RATE)) rate = SettingPins.pinned(plan, m, c);
        }
        return new SpecialInput(
            SpecialInput.Kind.CUSTOM_RATE,
            new SpecialInput.Holder(
                empty,
                new SpecialInput.Dial(CustomRate.supply(m.node), CustomRate.perSecond(m.node), kind, mode, rate)));
    }

    /**
     * The special view's chips as the card's setting controls, in the view's order: each the card's own control of
     * that setting ({@code power:<id>}) with the view's words. A chip with no such control is left out.
     */
    public static List<SettingControls.Control> controls(final SpecialView special, final CardModel m) {
        final List<SettingControls.Control> all = CardSettings.of(m);
        final List<SettingControls.Control> out = new ArrayList<>();
        for (final SpecialView.Chip chip : special.chips()) {
            for (final SettingControls.Control c : all) {
                if (!c.key()
                    .equals(
                        "power:" + chip.chip()
                            .id()))
                    continue;
                out.add(
                    new SettingControls.Control(
                        c.key(),
                        c.group(),
                        chip.chip()
                            .label(),
                        chip.chip()
                            .value(),
                        c.icon(),
                        c.warn(),
                        c.enabled(),
                        c.type(),
                        c.pinDefault()));
            }
        }
        return out;
    }
}
