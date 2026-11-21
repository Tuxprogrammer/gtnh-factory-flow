package com.gtnhplanner.machines.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.data.RecipeContext;
import com.gtnhplanner.data.effect.EffectResult;
import com.gtnhplanner.data.properties.RecipeProperty;
import com.gtnhplanner.machines.CropsNh;

/**
 * A crop card hands the solver its harvest time exactly, as the website and the core's crop readout do: an Industrial
 * Farm that harvests every 512 / 4.5 = 113.78 ticks makes 20 / 113.78 harvests a second, not 20 / 114.
 */
class CropCardsStepTest {

    /** Lazulia, as CropsNH has it: 2800 growth points at 112 a cycle of 256 ticks is 6400 ticks at the reference. */
    private static final CropsNh.Stats LAZULIA = new CropsNh.Stats(
        7,
        2800,
        0.6983373,
        256,
        1,
        false,
        -1,
        "Needs a block of lapis under it to grow",
        List.of(
            new CropsNh.Drop("gregtech:gt.metaitem.01@2526", 1, 6667),
            new CropsNh.Drop("minecraft:dye@4", 1, 3333)));

    private static EffectResult run(final Map<String, Object> settings) {
        final Map<RecipeProperty<?>, Object> props = new HashMap<>();
        props.put(CropCards.STATS, CropCards.write(LAZULIA));
        final int baseline = (int) CropsNh.harvestTicks(LAZULIA, CropsNh.REFERENCE);
        return new CropCards.Step().apply(new EffectResult(baseline, 0, 1), settings, new RecipeContext(props));
    }

    @Test
    void anIndustrialFarmKeepsItsFractionalHarvestTime() {
        // An HV seed bed with two growth units and a fertilizer unit: (1 + 2) x 1.5 = 4.5 times the pace.
        final Map<String, Object> farm = new HashMap<>();
        farm.put(CropsNh.HARVESTER, CropsNh.FARM);
        farm.put(CropsNh.SEED_BED_TIER, "4");
        farm.put(CropsNh.GROWTH_UNITS, "2");
        farm.put(CropsNh.FERTILIZER_UNITS, "1");
        final EffectResult effect = run(farm);

        final double exact = CropsNh.harvestTicks(LAZULIA, CropsNh.REFERENCE)
            * CropsNh.durationMultiplier(LAZULIA, farm);
        assertEquals(6400 / 4.5, exact, 1e-9);
        assertNotEquals(Math.rint(exact), exact);
        assertEquals(exact, effect.duration(), 0);
        // Harvests a second per crop, the core's 1 / (cycles x cycle seconds / speed).
        assertEquals(1 / (25 * 256 / 20.0 / 4.5), 20 / effect.duration(), 1e-12);
        assertEquals(0, effect.energyPerT());
        assertEquals(1, effect.throughputFactor());
        // The whole-tick figure stays what the card shows.
        assertEquals(1422, effect.durationTicks());
    }

    @Test
    void aCropManagerKeepsItsTimeToo() {
        final Map<String, Object> manager = new HashMap<>();
        manager.put(CropsNh.HARVESTER, CropsNh.MANAGER);
        manager.put(CropsNh.MANAGER_TIER, "1");
        final EffectResult effect = run(manager);
        final double exact = CropsNh.harvestTicks(LAZULIA, CropsNh.REFERENCE)
            * CropsNh.durationMultiplier(LAZULIA, manager);
        assertEquals(exact, effect.duration(), 0);
    }
}
