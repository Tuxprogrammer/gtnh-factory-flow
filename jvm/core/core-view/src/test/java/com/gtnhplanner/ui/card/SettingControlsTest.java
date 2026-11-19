package com.gtnhplanner.ui.card;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.view.card.CardModel;
import com.gtnhplanner.core.view.card.Cards;
import com.gtnhplanner.power.PowerGroup;
import com.gtnhplanner.power.PowerModel;
import com.gtnhplanner.power.PowerSetting;
import com.gtnhplanner.power.PowerSource;

/** A card's settings from a host the test writes: what the core decides (order, groups, the coil, captions). */
class SettingControlsTest {

    /** A host with a machine's generic settings, a model's, and the coils at 1801 and 2701 K. */
    private static final class Host implements SettingControls.Host {

        List<SettingControls.Setting> settings = new ArrayList<>();
        List<SettingControls.ModelSetting> model = new ArrayList<>();
        boolean modelled;

        @Override
        @Nullable
        public List<SettingControls.Setting> settings(final CardModel m) {
            return settings;
        }

        @Override
        public List<SettingControls.ModelSetting> modelSettings(final CardModel m) {
            return model;
        }

        @Override
        public boolean modelled(final CardModel m) {
            return modelled;
        }

        @Override
        @Nullable
        public SettingControls.Coil coil(final int heat) {
            return heat <= 1801 ? new SettingControls.Coil("Cupronickel Coil Block", 1801, "item:coil:0")
                : heat <= 2701 ? new SettingControls.Coil("Kanthal Coil Block", 2701, "item:coil:1") : null;
        }

        @Override
        public String farmUnits() {
            return "FARM UNITS";
        }

        @Override
        public String cropSlots(final CardModel m) {
            return "3 / 4";
        }

        @Override
        @Nullable
        public String caption(final CardModel m, final PowerSetting setting) {
            return "rate".equals(setting.id()) ? "Rate (L/s)" : null;
        }
    }

    private static List<String> keys(final List<SettingControls.Control> controls) {
        final List<String> out = new ArrayList<>();
        for (final SettingControls.Control c : controls) out.add(c.group() + "/" + c.key());
        return out;
    }

    @Test
    void aHeatRecipeListsItsCoilFirstThenTheMachinesSettingsByGroup() {
        final Host host = new Host();
        host.settings.add(new SettingControls.Setting("voltage", "Voltage", SettingControls.Type.CHOICE, "LV"));
        host.settings.add(new SettingControls.Setting("perfect_oc", "Perfect", SettingControls.Type.TOGGLE, "Off"));
        host.settings.add(new SettingControls.Setting("parallels", "Parallels", SettingControls.Type.NUMBER, "4"));
        host.settings.add(new SettingControls.Setting("heat_oc", "Heat OC", SettingControls.Type.TOGGLE, "On"));
        host.settings.add(new SettingControls.Setting("odd", "Odd", null, ""));
        final CardModel m = Cards.card(List.of(), List.of(), 30, false, true, 1801, 2000, null);
        final List<SettingControls.Control> all = SettingControls.of(m, host);
        assertEquals(
            List.of("MACHINE/machine_heat", "MACHINE/parallels", "OVERCLOCKING/perfect_oc", "HEAT/heat_oc"),
            keys(all));
        final SettingControls.Control coil = all.get(0);
        assertEquals("Cupronickel", coil.value());
        assertEquals("item:coil:0", coil.icon());
        // 1801 K under a 2000 K recipe: the coil stops it.
        assertTrue(coil.warn());
        assertTrue(coil.pinDefault());
        assertTrue(SettingControls.coilTooCold(m, host));
        final CardModel hot = Cards.card(List.of(), List.of(), 30, false, true, 2701, 2000, null);
        assertFalse(SettingControls.coilTooCold(hot, host));
    }

    @Test
    void aMachineWithNoProfileListsNothingAndAModelledOneOnlyItsOwn() {
        final Host none = new Host();
        none.settings = null;
        none.model.add(model("tool", "SETTINGS", false, 0, 0));
        assertTrue(SettingControls.of(Cards.recipe(List.of(), List.of(), 0), none).isEmpty());
        final Host modelled = new Host();
        modelled.modelled = true;
        modelled.settings.add(new SettingControls.Setting("parallels", "Parallels", SettingControls.Type.NUMBER, "4"));
        modelled.model.add(model("tool", "SETTINGS", false, 0, 0));
        assertEquals(
            List.of("SETTINGS/machine:tool"),
            keys(SettingControls.of(Cards.recipe(List.of(), List.of(), 0), modelled)));
    }

    @Test
    void aCropCardsFarmUnitsFollowItsSlotsAndANumberWithNowhereToGoIsGreyed() {
        final Host host = new Host();
        host.model.add(model("harvester", "MACHINE", false, 0, 0));
        host.model.add(model("growth", "FARM UNITS", true, 0, 0));
        host.model.add(model("gain", "FARM UNITS", true, 0, 2));
        final CardModel crop = Cards.card(List.of(), List.of(), 0, true, false, 0, 0, null);
        final List<SettingControls.Control> all = SettingControls.of(crop, host);
        assertEquals(
            List.of(
                "MACHINE/machine:harvester",
                "FARM UNITS/reading:slots",
                "FARM UNITS/machine:growth",
                "FARM UNITS/machine:gain"),
            keys(all));
        assertEquals("3 / 4", all.get(1).value());
        // growth: a number from 0 to 0 at 0, nowhere to go; gain: 0 to 2.
        assertFalse(all.get(2).enabled());
        assertTrue(all.get(3).enabled());
    }

    @Test
    void aPowerCardListsItsSettingsThenItsReadingsWithTheHostsCaption() {
        final PowerSetting.Number rate = new PowerSetting.Number("rate", "Rate", 0, 100, 1, 10);
        final PowerSetting.Toggle on = new PowerSetting.Toggle("on", "Running", true, null);
        final PowerSource source = new PowerSource(
            "test",
            "Test",
            PowerGroup.OTHER,
            null,
            "",
            List.of(rate, on),
            reader -> null);
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("rate", "25");
        final CardModel m = Cards.card(
            List.of(),
            List.of(),
            0,
            false,
            false,
            0,
            0,
            new CardModel.Power("test", source, null, List.of(new PowerModel.Stat("Efficiency", "80%")), values));
        final List<SettingControls.Control> all = SettingControls.of(m, new Host());
        assertEquals(
            List.of("SETTINGS/power:rate", "SETTINGS/power:on", "READINGS/reading:Efficiency"),
            keys(all));
        assertEquals("Rate (L/s)", all.get(0).label());
        assertEquals("25", all.get(0).value());
        assertEquals("Running", all.get(1).label());
        assertEquals("On", all.get(1).value());
        assertEquals("80%", all.get(2).value());
        assertNull(all.get(2).icon());
    }

    @Test
    void aCoilsShortNameDropsItsBlockWord() {
        assertEquals("Cupronickel", SettingControls.shortCoilName("Cupronickel Coil Block"));
        assertEquals("Awakened Draconium", SettingControls.shortCoilName("Awakened Draconium Coil"));
        assertEquals("Infinity", SettingControls.shortCoilName("Infinity"));
    }

    private static SettingControls.ModelSetting model(final String key, final String group, final boolean number,
        final double min, final double max) {
        return new SettingControls.ModelSetting(key, group, key, "0", null, false, number, min, max, true);
    }
}
