package com.gtnhplanner.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.power.PowerCatalog;
import com.gtnhplanner.power.PowerModel.Alternative;

/** The website's steam-makers.test.ts case on the GT++ Advanced Boiler's wood and coal dust fuels (fork issue 171). */
class AdvancedBoilerWoodTest {

    private static PowerModel boiler(final String fuel) {
        final PowerSource source = PowerCatalog.get("advanced-boiler");
        assertNotNull(source);
        return source.compute(Map.of("solidFuel", fuel));
    }

    @Test
    void burnsWoodAndCoalDustsAtTheirFurnaceValues() {
        // MTEAdvancedBoilerBase.getBurnTime is GameRegistry.getFuelValue, else TileEntityFurnace.getItemBurnTime.
        // Seconds per item: (floor(bt/10)/2 + floor(bt/500)) x 41/20; a blaze rod keeps vanilla's 2400 here.
        final Object[][] seconds = { { "Oak Log", 30.75 }, { "Oak Planks", 30.75 }, { "Stick", 10.25 },
            { "Oak Sapling", 10.25 }, { "Wood Pulp", 10.25 }, { "Coal Dust", 170.15 }, { "Lignite Coal Dust", 127.1 },
            { "Blaze Rod", 254.2 } };
        for (final Object[] row : seconds) {
            final String fuel = (String) row[0];
            final PowerModel model = boiler(fuel);
            assertEquals(fuel, model.inputs().get(0).name());
            assertEquals(1 / (Double) row[1], model.inputs().get(0).perSecond(), 1e-12, fuel);
        }
    }

    @Test
    void theLogSlotTakesAnyVanillaLog() {
        assertEquals(
            List.of(
                new Alternative("item", "minecraft:log@32767", "Log"),
                new Alternative("item", "minecraft:log2@32767", "Log")),
            boiler("Oak Log").inputs().get(0).alternatives());
        assertEquals(List.of(), boiler("Coal").inputs().get(0).alternatives());
    }
}
