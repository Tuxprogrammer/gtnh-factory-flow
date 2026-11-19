package com.gtnhplanner.ui.card;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.gtnhplanner.data.MachineConfig;
import com.gtnhplanner.data.RecipeContext;
import com.gtnhplanner.data.SettingDef;
import com.gtnhplanner.machines.game.CropCards;
import com.gtnhplanner.machines.game.MachineModels;
import com.gtnhplanner.power.CustomRate;
import com.gtnhplanner.power.PowerSetting;
import com.gtnhplanner.ui.gt.GtCoils;

/**
 * What core-view's {@link SettingControls} reads of a card's machine, read from the game: the node's machine config
 * and settings profile, the modelled machines (MachineModels, CropCards), GregTech's coils and the custom rate card.
 * One per card, as the rules ask about it; and the mod's ways in to those rules for a {@link CardModel}.
 */
public final class CardSettings implements SettingControls.Host {

    private final CardModel card;

    private CardSettings(final CardModel card) {
        this.card = card;
    }

    /** Every setting of the card, in the sheet's order. */
    public static List<SettingControls.Control> of(final CardModel m) {
        return SettingControls.of(m.core, new CardSettings(m));
    }

    /** The settings the card shows as chips, as the plan has them pinned. */
    public static List<SettingControls.Control> pinned(final com.gtnhplanner.data.flowchart.Graph plan,
        final CardModel m) {
        final List<SettingControls.Control> out = new ArrayList<>();
        for (final SettingControls.Control c : of(m)) if (SettingPins.pinned(plan, m, c)) out.add(c);
        return out;
    }

    /** Whether the card's coil stops its recipe: none picked, or too cold. */
    public static boolean coilTooCold(final CardModel m) {
        return SettingControls.coilTooCold(m.core, new CardSettings(m));
    }

    /** A recipe setting's definition, by key; null when the machine has no such setting. */
    @Nullable
    public static SettingDef<?> def(final CardModel m, final String key) {
        final MachineConfig cfg = m.node.machineConfig;
        if (cfg == null || cfg.getProfile() == null) return null;
        for (final SettingDef<?> d : cfg.getProfile()
            .visibleSettings(new RecipeContext(m.node.properties), cfg.settings)) if (d.key.equals(key)) return d;
        return null;
    }

    @Override
    @Nullable
    public List<SettingControls.Setting> settings(final com.gtnhplanner.core.view.card.CardModel m) {
        final MachineConfig cfg = card.node.machineConfig;
        if (cfg == null || cfg.getProfile() == null) return null;
        final List<SettingControls.Setting> out = new ArrayList<>();
        for (final SettingDef<?> d : cfg.getProfile()
            .visibleSettings(new RecipeContext(card.node.properties), cfg.settings)) {
            final SettingControls.Type type = d.type == Boolean.class ? SettingControls.Type.TOGGLE
                : d.type == Integer.class ? SettingControls.Type.NUMBER
                    : d.options != null && !d.options.isEmpty() ? SettingControls.Type.CHOICE : null;
            final String value = type == null ? "" : switch (type) {
                case TOGGLE -> CardDefaults.boolSetting(cfg, d.key) ? "On" : "Off";
                case NUMBER -> Integer.toString(CardDefaults.intSetting(cfg, d.key));
                default -> CardDefaults.stringSetting(cfg, d.key);
            };
            out.add(new SettingControls.Setting(d.key, d.label, type, value));
        }
        return out;
    }

    @Override
    public List<SettingControls.ModelSetting> modelSettings(final com.gtnhplanner.core.view.card.CardModel m) {
        final boolean crop = m.crop();
        final List<SettingControls.ModelSetting> out = new ArrayList<>();
        for (final MachineModels.Setting s : MachineModels.settings(card.node)) out.add(
            new SettingControls.ModelSetting(
                s.key(),
                crop ? CropCards.group(s.key()) : "SETTINGS",
                s.label(),
                s.value(),
                IconKeys.of(s.icon()),
                s.warn(),
                s.number(),
                s.min(),
                s.max(),
                crop ? CropCards.pinnedByDefault(s.key())
                    : com.gtnhplanner.machines.game.WebSettings.COIL.equals(s.key())));
        return out;
    }

    @Override
    public boolean modelled(final com.gtnhplanner.core.view.card.CardModel m) {
        return MachineModels.isModelled(card.node);
    }

    @Override
    @Nullable
    public SettingControls.Coil coil(final int heat) {
        final GtCoils.Coil coil = GtCoils.forHeat(heat);
        return coil == null ? null : new SettingControls.Coil(coil.name(), coil.heat(), IconKeys.of(coil.stack()));
    }

    @Override
    public String farmUnits() {
        return CropCards.FARM_UNITS;
    }

    @Override
    public String cropSlots(final com.gtnhplanner.core.view.card.CardModel m) {
        return CropCards.slots(card.node);
    }

    @Override
    @Nullable
    public String caption(final com.gtnhplanner.core.view.card.CardModel m, final PowerSetting setting) {
        return CustomRate.is(card.node) && setting == CustomRate.RATE_SETTING
            ? setting.label() + " (" + CustomRate.unit(CustomRate.resource(card.node)) + ")"
            : null;
    }
}
