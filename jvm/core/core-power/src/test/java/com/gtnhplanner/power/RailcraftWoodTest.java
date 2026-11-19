package com.gtnhplanner.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.power.PowerCatalog;
import com.gtnhplanner.power.PowerModel.Alternative;
import com.gtnhplanner.power.PowerModel.Flow;

/** The website's railcraft.test.ts cases on the boiler's wood and coal dust fuels (fork issue 120). */
class RailcraftWoodTest {

    private static PowerModel boiler(final String fuel) {
        final PowerSource source = PowerCatalog.get("railcraft-boiler");
        assertNotNull(source);
        return source.compute(Map.of("fuel", fuel));
    }

    @Test
    void burnsWoodAndCoalDustsAtTheirFurnaceValues() {
        // TileEntityFurnace.getItemBurnTime: a wooden block 300, a stick or sapling 100. GTProxy.getBurnTime's ore
        // dictionary table: dustCoal and dustCharcoal 1600, dustLignite 1200, dustWood 100. 324 fuel units a second.
        final Object[][] fuels = { { "Oak Log", 300 }, { "Oak Planks", 300 }, { "Stick", 100 }, { "Oak Sapling", 100 },
            { "Wood Pulp", 100 }, { "Coal Dust", 1600 }, { "Charcoal Dust", 1600 }, { "Lignite Coal Dust", 1200 } };
        for (final Object[] row : fuels) {
            final String fuel = (String) row[0];
            final PowerModel model = boiler(fuel);
            assertEquals(324.0 / (Integer) row[1], model.inputs().get(0).perSecond(), 1e-10, fuel);
            assertEquals(fuel, model.inputs().get(0).name());
            assertEquals("Solid Fueled", model.stat("Firebox"));
        }
        assertEquals("minecraft:log", PowerResources.resolve("Oak Log").id);
        assertEquals("minecraft:planks", PowerResources.resolve("Oak Planks").id);
        assertEquals("gregtech:gt.metaitem.01@2535", PowerResources.resolve("Coal Dust").id);
    }

    @Test
    void logPlanksAndSaplingSlotsTakeAnyVanillaWood() {
        final Flow log = boiler("Oak Log").inputs().get(0);
        assertEquals(
            List.of(
                new Alternative("item", "minecraft:log@32767", "Log"),
                new Alternative("item", "minecraft:log2@32767", "Log")),
            log.alternatives());
        assertEquals(
            List.of(new Alternative("item", "minecraft:planks@32767", "Planks")),
            boiler("Oak Planks").inputs().get(0).alternatives());
        assertEquals(
            List.of(new Alternative("item", "minecraft:sapling@32767", "Sapling")),
            boiler("Oak Sapling").inputs().get(0).alternatives());
        assertEquals(List.of(), boiler("Coal Dust").inputs().get(0).alternatives());
        assertEquals(List.of(), boiler("Stick").inputs().get(0).alternatives());
    }

    @Test
    void theFuelListIsSortedByBurnTimeAndLabelsTheWoods() {
        final PowerSetting.Select fuel = (PowerSetting.Select) PowerCatalog.get("railcraft-boiler").setting("fuel");
        assertEquals("Log (any wood)", fuel.option("Oak Log").label());
        assertEquals("Planks (any wood)", fuel.option("Oak Planks").label());
        assertEquals("Sapling (any)", fuel.option("Oak Sapling").label());
        assertEquals("Coal", fuel.defaultKey());
    }
}
