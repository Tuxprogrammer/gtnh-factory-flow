package com.gtnhplanner.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.power.PowerCatalog;

/**
 * The website's steam-makers.test.ts case on the large boilers' liquid burn times: LargeBoilerFuelBackend.addRecipe
 * works in whole ticks, then each boiler's runtimeBoost (bronze x2, steel x1, titanium x3/10, tungstensteel x15/100).
 */
class LargeBoilerLiquidTest {

    private static double litresPerSecond(final String boiler, final String liquid) {
        final PowerSource source = PowerCatalog.get(boiler);
        assertNotNull(source, boiler);
        return source.compute(Map.of("liquidFuel", liquid, "solidFuel", "None"))
            .inputs()
            .get(0)
            .perSecond();
    }

    @Test
    void burnsALiquidForTheRecipeDurationTimesTheBoilersBoost() {
        assertEquals(1000 / 23.2, litresPerSecond("large-bronze-boiler", "Propane"), 1e-9);
        assertEquals(1000 / 11.6, litresPerSecond("large-steel-boiler", "Propane"), 1e-9);
        assertEquals(1000 / 14.95, litresPerSecond("large-steel-boiler", "Butane"), 1e-9);
        assertEquals(1000 / 17.15, litresPerSecond("large-titanium-boiler", "Ethanol Gasoline"), 1e-9);
        assertEquals(1000 / 8.55, litresPerSecond("large-tungstensteel-boiler", "Ethanol Gasoline"), 1e-9);
    }
}
