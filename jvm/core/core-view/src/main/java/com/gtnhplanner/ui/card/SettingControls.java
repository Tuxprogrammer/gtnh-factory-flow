package com.gtnhplanner.ui.card;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.NumberText;
import com.gtnhplanner.core.view.card.CardModel;
import com.gtnhplanner.power.PowerSetting;
import com.gtnhplanner.power.PowerSource;

/**
 * A card's settings as the settings sheet lists them and its chips show them: on a recipe card the machine's
 * settings (the coil among them on a heat recipe), grouped; on a power card its settings and then its readings. The
 * amps, tier and machine count are left out: the card has its own controls for them. What a machine's settings are
 * and what they hold is the host's to read (its machine config, its modelled machines, its coils): a {@link Host}.
 */
public final class SettingControls {

    /** How a setting is changed: switched, stepped or typed, picked from a list, or not at all. */
    public enum Type {
        TOGGLE,
        NUMBER,
        CHOICE,
        COIL,
        READING
    }

    /**
     * A setting: its key ({@code machine_heat} for the coil, {@code power:<id>} on a power card,
     * {@code reading:<label>}
     * for a reading), its group's heading, label and value as shown, its icon's key (the coil; see {@link CardModel}),
     * whether it stops the recipe (a coil too cold), whether another setting makes it moot, and whether it shows on the
     * card until pins are set.
     */
    public record Control(String key, String group, String label, String value, @Nullable String icon, boolean warn,
        boolean enabled, Type type, boolean pinDefault) {}

    /**
     * What a host reads of a card's machine for its settings. The mod's reads its node's machine config, its modelled
     * machines (MachineModels, CropCards), GregTech's coils and the custom rate card ({@code ui.card.CardSettings}).
     */
    public interface Host {

        /**
         * The machine's own settings for its recipe, in its profile's order, each with the type it is changed by
         * ({@code null} for one the card does not offer) and its value as shown; null when the machine has no settings
         * profile at all (nothing is listed then, not even a modelled machine's).
         */
        @Nullable
        List<Setting> settings(CardModel m);

        /**
         * A modelled machine's own setup (the Tree Growth Simulator's tools and genes, the vat's glass and hatch, a
         * crop's sections), in order; empty for any other machine.
         */
        List<ModelSetting> modelSettings(CardModel m);

        /** The machine runs by its own model (its generic speed, parallel and overclock knobs are not offered). */
        boolean modelled(CardModel m);

        /** The coil a heat recipe's machine has at {@code heat} K; null when no coil is that hot. */
        @Nullable
        Coil coil(int heat);

        /** The heading of a crop card's farm units, after which its upgrade slots are listed. */
        String farmUnits();

        /** A crop card's upgrade slots as shown ("3 / 4"). */
        String cropSlots(CardModel m);

        /** A power card's caption for a setting when the host names it its own way (the custom rate's unit); else null. */
        @Nullable
        String caption(CardModel m, PowerSetting setting);
    }

    /** One of a machine's settings: its key, label, the type it is changed by (null: not offered) and value. */
    public record Setting(String key, String label, @Nullable Type type, String value) {}

    /**
     * A modelled machine's setting: key (without {@link #MACHINE_PREFIX}), its group's heading, label, value as shown,
     * its icon's key, whether it stops the machine, whether it is a number (and its range) or a choice, and whether it
     * shows on the card until pins are set.
     */
    public record ModelSetting(String key, String group, String label, String value, @Nullable String icon,
        boolean warn, boolean number, double min, double max, boolean pinDefault) {}

    /** A heating coil: its name, its heat in K, its icon's key. */
    public record Coil(String name, int heat, @Nullable String icon) {}

    /** A modelled machine's setting (MachineModels): {@code machine:<the website's key>}. */
    public static final String MACHINE_PREFIX = "machine:";

    /** The card's own controls: never listed. */
    private static final Set<String> OWN = Set.of("voltage", "amp", "machines");
    private static final Set<String> OVERCLOCKING = Set.of(
        "perfect_oc",
        "laser_oc",
        "no_overclock",
        "max_overclocks",
        "max_regular_oc",
        "max_tier_skips",
        "unlimited_skips",
        "eut_discount",
        "eut_increase_per_oc",
        "duration_decrease_per_oc");
    private static final Set<String> HEAT = Set.of("heat_oc", "recipe_heat", "heat_discount", "heat_discount_mult");
    private static final String[] GROUPS = { "MACHINE", "OVERCLOCKING", "HEAT" };

    private SettingControls() {}

    /** Every setting of the card, in the sheet's order. */
    public static List<Control> of(final CardModel m, final Host host) {
        return m.isPower() ? power(m, host) : recipe(m, host);
    }

    /** Whether the card's coil stops its recipe: none picked, or too cold. */
    public static boolean coilTooCold(final CardModel m, final Host host) {
        if (!m.usesHeat()) return false;
        // The website's maths: only a heat machine's recipe asks heat of the coil; elsewhere (the Multi Smelter, the
        // cracker, the pyrolyse oven) the coil buys parallels, speed or a discount and stops nothing.
        if (m.machineMath() != null) return com.gtnhplanner.machines.web.Heat.tooCold(m.coilHeat(), m.recipeHeat());
        final Coil coil = m.coilHeat() > 0 ? host.coil(m.coilHeat()) : null;
        return coil == null || m.recipeHeat() > coil.heat();
    }

    /** A coil's name without its block word, as its chip shows it ("Cupronickel"). */
    public static String shortCoilName(final String name) {
        for (final String tail : new String[] { " Coil Block", " Coil" }) {
            if (name.endsWith(tail)) return name.substring(0, name.length() - tail.length());
        }
        return name;
    }

    private static List<Control> recipe(final CardModel m, final Host host) {
        final List<Control> out = new ArrayList<>();
        final List<Setting> defs = host.settings(m);
        if (defs == null) return out;
        // A modelled machine's own setup first (the TGS's tools and genes, the vat's glass, hatch and radiation),
        // under the website's keys: "machine:<key>", changed through MachineModels.
        // A crop's settings come in sections (machine, crop, surroundings, the farm's units), most pinned from the
        // start.
        final boolean crop = m.crop();
        // What the recipe decides of the machine, read-only: the Dangote Distillus runs in its recipe's mode.
        final String mode = m.machineMath() == null ? null
            : com.gtnhplanner.machines.web.DangoteDistillus.mode(
                m.machineMath()
                    .effectiveRecipe());
        if (mode != null)
            out.add(new Control("reading:mode", "SETTINGS", "Mode", mode, null, false, true, Type.READING, false));
        for (final ModelSetting s : host.modelSettings(m)) {
            final String group = s.group();
            // The farm's slots, read-only, close the units it shares them between.
            if (crop && host.farmUnits()
                .equals(group)
                && out.stream()
                    .noneMatch(
                        c -> c.group()
                            .equals(group)))
                out.add(
                    new Control(
                        "reading:slots",
                        group,
                        "Upgrade slots",
                        host.cropSlots(m),
                        null,
                        false,
                        true,
                        Type.READING,
                        false));
            out.add(
                new Control(
                    MACHINE_PREFIX + s.key(),
                    group,
                    s.label(),
                    s.value(),
                    s.icon(),
                    s.warn(),
                    // A number with nowhere to go (a farm unit with no slot left, overclocks below ZPM) is greyed.
                    !crop || !s.number() || s.max() > s.min() || NumberText.parse(s.value()) > s.min(),
                    s.number() ? Type.NUMBER : Type.CHOICE,
                    s.pinDefault()));
        }
        // A modelled machine runs by the game's own rules (machines/): the TGS 100 ticks at VP[t], the vat GT's normal
        // overclocks. The generic speed, parallel and overclock knobs could only make numbers the game never does.
        if (host.modelled(m) || m.machineMath() != null) return out;
        for (final String group : GROUPS) {
            // The coil first, at the top of the machine's settings.
            if ("MACHINE".equals(group) && m.usesHeat()) {
                final Coil coil = m.coilHeat() > 0 ? host.coil(m.coilHeat()) : null;
                out.add(
                    new Control(
                        "machine_heat",
                        group,
                        "Coil",
                        coil == null ? "None" : shortCoilName(coil.name()),
                        coil == null ? null : coil.icon(),
                        coilTooCold(m, host),
                        true,
                        Type.COIL,
                        true));
            }
            for (final Setting d : defs) {
                if (OWN.contains(d.key()) || "machine_heat".equals(d.key()) || !group.equals(groupOf(d.key())))
                    continue;
                if (d.type() == null) continue;
                out.add(new Control(d.key(), group, d.label(), d.value(), null, false, true, d.type(), false));
            }
        }
        return out;
    }

    private static String groupOf(final String key) {
        return OVERCLOCKING.contains(key) ? "OVERCLOCKING" : HEAT.contains(key) ? "HEAT" : "MACHINE";
    }

    private static List<Control> power(final CardModel m, final Host host) {
        final List<Control> out = new ArrayList<>();
        final CardModel.Power power = m.power();
        final PowerSource source = power.source();
        // The custom rate card shows its dial on itself: both its settings are pinned until unpinned.
        final boolean custom = m.customRate();
        for (final PowerTiles.Tile t : PowerTiles.of(m)) {
            if (t.isSetting()) {
                final PowerSetting s = t.setting();
                final String own = host.caption(m, s);
                final String caption = own != null ? own : PowerTiles.caption(s);
                final Type type = s instanceof PowerSetting.Toggle ? Type.TOGGLE
                    : s instanceof PowerSetting.Number ? Type.NUMBER : Type.CHOICE;
                out.add(
                    new Control(
                        "power:" + s.id(),
                        "SETTINGS",
                        caption,
                        PowerTiles.shown(s, power.settings()),
                        null,
                        false,
                        source != null && PowerTiles.enabled(source, s, power.settings()),
                        type,
                        custom));
            } else out.add(
                new Control(
                    "reading:" + t.stat()
                        .label(),
                    "READINGS",
                    t.stat()
                        .label(),
                    t.stat()
                        .value(),
                    null,
                    false,
                    true,
                    Type.READING,
                    false));
        }
        return out;
    }
}
