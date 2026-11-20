package com.gtnhplanner.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.power.PowerCatalog;

/**
 * The website's plasma-fuels.test.ts: the list is what RecipeMaps.plasmaFuels holds (ProcessingCell's cellPlasma for
 * materials built with addPlasma(), plus GT++ RecipeGenPlasma), and the turbines make 0 EU from any other plasma.
 */
class PlasmaFuelsTest {

    private static PowerSetting.Select fuel(final String sourceId) {
        final PowerSource source = PowerCatalog.get(sourceId);
        assertNotNull(source, sourceId);
        return (PowerSetting.Select) source.setting("fuel");
    }

    private static double euPerLiter(final String name) {
        for (final PowerData.Fuel row : PowerData.get().plasmas) {
            if (row.name.equals(name)) {
                return row.euPerLiter;
            }
        }
        throw new AssertionError("no plasma " + name);
    }

    @Test
    void offersThePlasmasTheGameBurnsAndNoneItMakesNothingFrom() {
        for (final String id : new String[] { "large-plasma-generator", "xl-turbo-plasma-turbine",
            "plasma-generator" }) {
            final PowerSetting.Select select = fuel(id);
            for (final String burns : new String[] { "Selenium Plasma", "Uranium 233 Plasma", "Plutonium-238 Plasma" }) {
                assertNotNull(select.option(burns), id + " " + burns);
            }
            for (final String dead : new String[] { "Creon Plasma", "Chrome Plasma", "Naquadah Plasma", "Osmium Plasma",
                "Iridium Plasma", "Platinum Plasma", "Plutonium-239 Plasma" }) {
                assertFalse(
                    select.options()
                        .stream()
                        .anyMatch(option -> option.key()
                            .equals(dead)),
                    id + " " + dead);
            }
        }
    }

    @Test
    void pricesAPlasmaWithNoHandWrittenValueAt1024EuPerLitrePerUnitOfMass() {
        assertEquals(79 * 1024, euPerLiter("Selenium Plasma"));
        assertEquals(98 * 1024, euPerLiter("Technetium Plasma"));
        assertEquals(238 * 1024, euPerLiter("Plutonium-238 Plasma"));
        final PowerSource source = PowerCatalog.get("plasma-generator");
        assertNotNull(source);
        final double selenium = source.compute(Map.of("fuel", "Selenium Plasma"))
            .inputs()
            .get(0)
            .perSecond();
        final double helium = source.compute(Map.of("fuel", "Helium Plasma"))
            .inputs()
            .get(0)
            .perSecond();
        assertEquals(helium * euPerLiter("Helium Plasma"), selenium * 79 * 1024, 1e-6);
        assertTrue(selenium > 0);
    }
}
