package com.gtnhplanner.ui.card;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.gtnhplanner.core.view.special.SpecialInput;
import com.gtnhplanner.core.view.special.SpecialView;
import com.gtnhplanner.core.view.special.SpecialViews;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.power.CustomRate;
import com.gtnhplanner.power.Energy;
import com.gtnhplanner.power.FluidTank;
import com.gtnhplanner.power.Holders;
import com.gtnhplanner.power.PowerSetting;

/**
 * The mod's side of the shared special card rules: a special card's kind's facts as core-view's {@link SpecialInput},
 * from its node, and the special view's strip as the mod's setting controls (so a chip still presses and steps the
 * card's setting as it did).
 */
public final class SpecialInputs {

    private SpecialInputs() {}

    /** A card's kind's facts for the shared special rules: a holder's or a generator's; null on any other card. */
    @Nullable
    public static SpecialInput of(final CardModel m, @Nullable final Graph plan) {
        final SpecialInput holder = holder(m, plan);
        return holder != null ? holder : generator(m, plan);
    }

    /**
     * A generator's facts (a power card that is no holder): its source's name, its tier select (the head's chip),
     * every other setting as {@link PowerTiles} reads it, its readings, its model's warnings, and which of them the plan
     * pins ({@link SettingPins}: {@code power:<id>}, {@code reading:<label>}). Null on any other card.
     */
    @Nullable
    public static SpecialInput generator(final CardModel m, @Nullable final Graph plan) {
        if (!m.isPower() || Holders.is(m.node)) return null;
        final com.gtnhplanner.core.view.card.CardModel.Power power = m.core.power();
        final com.gtnhplanner.power.PowerSource source = power.source();
        final java.util.Map<String, String> values = power.settings();
        final java.util.Set<String> pinned = new java.util.HashSet<>();
        for (final SettingControls.Control c : CardSettings.of(m)) if (SettingPins.pinned(plan, m, c)) pinned.add(c.key());
        final List<SpecialInput.Setting> settings = new ArrayList<>();
        final List<SpecialInput.Reading> readings = new ArrayList<>();
        for (final PowerTiles.Tile t : PowerTiles.of(m.core)) {
            if (!t.isSetting()) {
                readings.add(
                    new SpecialInput.Reading(
                        t.stat()
                            .label(),
                        t.stat()
                            .value(),
                        pinned.contains(
                            SpecialViews.READING + t.stat()
                                .label())));
                continue;
            }
            final PowerSetting s = t.setting();
            final boolean enabled = source != null && PowerTiles.enabled(source, s, values);
            final boolean pin = pinned.contains("power:" + s.id());
            settings.add(switch (s) {
                case PowerSetting.Select sel -> new SpecialInput.Setting(s.id(), s.label(),
                    SpecialInput.SettingKind.CHOICE, PowerTiles.shown(s, values), 0, null, false, enabled, pin);
                case PowerSetting.Number n -> new SpecialInput.Setting(s.id(), s.label(),
                    SpecialInput.SettingKind.NUMBER, "", n.value(values.get(s.id())), n.unit(), false, enabled, pin);
                case PowerSetting.Toggle tg -> new SpecialInput.Setting(s.id(), s.label(),
                    SpecialInput.SettingKind.TOGGLE, "", 0, null, tg.value(values.get(s.id())), enabled, pin);
            });
        }
        final PowerSetting.Select tierSelect = PowerTiles.tierSetting(source);
        final SpecialInput.GeneratorTier tier = tierSelect == null ? null
            : new SpecialInput.GeneratorTier(
                tierSelect.value(values.get(tierSelect.id())),
                tierSelect.options()
                    .size() <= 1);
        final List<String> warnings = power.model() == null ? List.of()
            : power.model()
                .warnings();
        return new SpecialInput(
            SpecialInput.Kind.POWER,
            null,
            new SpecialInput.Generator(m.core.machineName(), tier, settings, readings, warnings));
    }

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
            // A setting's control is keyed power:<id>, a reading's reading:<label> (the chip's own id).
            final String id = chip.chip()
                .id();
            final String key = id.startsWith(SpecialViews.READING) ? id : "power:" + id;
            for (final SettingControls.Control c : all) {
                if (!c.key()
                    .equals(key)) continue;
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
                        c.enabled() && !chip.off(),
                        c.type(),
                        c.pinDefault()));
            }
        }
        return out;
    }
}
