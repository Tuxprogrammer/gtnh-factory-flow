package com.gtnhplanner.core.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.power.PowerSource;

/**
 * The website's turbine-flow.test.ts (fork issue 64's chain) on the source-level functions: an XL Turbo HP Steam Turbine
 * and an XL Turbo Steam Turbine, large Ichorium rotors, fed 264,900 L/t of their 460,800 L/t optimal. The expected EU
 * are the website's, which it checks against the game's float steps in fluidIntoPower.
 */
class TurbineFlowTest {

    private static final Map<String, String> HP = Map
        .of("rotor", "Ichorium", "size", "Large", "grade", "SH Steam", "flowMode", "optimal", "fitting", "tight");
    private static final Map<String, String> LP = Map.of("rotor", "Ichorium", "size", "Large");
    private static final double SHARE = 264_900d / 460_800d;

    private static PowerSource source(final String id) {
        final PowerSource source = PowerCatalog.get(id);
        assertNotNull(source, id);
        return source;
    }

    @Test
    void oneXlHpSteamTurbineAtFiftySevenPercentMakesLessThanThePlanCounts() {
        final TurbineFlow.Check check = TurbineFlow
            .turbineFlowCheck(source("xl-turbo-hp-steam-turbine"), HP, SHARE, 1);
        assertNotNull(check);
        assertEquals(1, check.turbines());
        assertEquals(264_900, check.flow());
        assertEquals(460_800, check.setFlow());
        assertEquals(460_800, check.optimal());
        assertEquals("L/t", check.unit());
        assertEquals(342_636, check.euMade());
        assertEquals(596_025, Math.round(check.euCounted()));
    }

    @Test
    void itsExhaustInTheXlSteamTurbine() {
        final TurbineFlow.Check check = TurbineFlow.turbineFlowCheck(source("xl-turbo-steam-turbine"), LP, SHARE, 1);
        assertNotNull(check);
        assertEquals(171_317, check.euMade());
        assertEquals(298_013, Math.round(check.euCounted()));
    }

    @Test
    void aFractionalCountSharesOverWholeTurbines() {
        assertEquals(3, TurbineFlow.turbinesFor(2.4, null));
        final TurbineFlow.Check check = TurbineFlow
            .turbineFlowCheck(source("xl-turbo-hp-steam-turbine"), HP, 2.4, TurbineFlow.turbinesFor(2.4, null));
        assertNotNull(check);
        assertEquals(3, check.turbines());
        assertEquals(368_640, check.flow());
        assertTrue(check.euMade() < check.euCounted());
    }

    @Test
    void anExactlyCardSharesWhatRunsOverItsCount() {
        assertEquals(3, TurbineFlow.turbinesFor(2, 3d));
        assertEquals(
            307_200,
            TurbineFlow.turbineFlowCheck(source("xl-turbo-hp-steam-turbine"), HP, 2, 3)
                .flow());
    }

    @Test
    void saysNothingWhenEachTurbineGetsItsSetFlow() {
        final PowerSource hp = source("xl-turbo-hp-steam-turbine");
        assertNull(TurbineFlow.turbineFlowCheck(hp, HP, 1, 1));
        assertNull(TurbineFlow.turbineFlowCheck(hp, HP, 3, 3));
        assertNull(TurbineFlow.turbineFlowCheck(hp, HP, 0, 1));
        assertNull(TurbineFlow.turbineFlowCheck(source("railcraft-boiler"), Map.of(), 2.4, 3));
        assertFalse(TurbineFlow.mayTurbineFlow(hp, 2, 2));
        assertTrue(TurbineFlow.mayTurbineFlow(hp, 2.4, 3));
        assertFalse(TurbineFlow.mayTurbineFlow(null, 2.4, 3));
    }

    @Test
    void aCustomFlowAboveOptimalMakesMoreThanThePlanCountsWhenFedCloserToOptimal() {
        final Map<String, String> over = Map
            .of("rotor", "Ichorium", "size", "Large", "grade", "SH Steam", "flowMode", "custom", "customFlow", "576000", "fitting", "tight");
        final TurbineFlow.Check check = TurbineFlow
            .turbineFlowCheck(source("xl-turbo-hp-steam-turbine"), over, 0.8, 1);
        assertNotNull(check);
        assertEquals(460_800, check.flow());
        assertEquals(1_036_800, check.euMade());
        assertTrue(check.euMade() > check.euCounted());
    }

    @Test
    void followsTheGamesOffOptimalLossForGasAndPlasmaTurbinesToo() {
        for (final String id : new String[] { "xl-turbo-gas-turbine", "large-gas-turbine", "xl-turbo-plasma-turbine",
            "large-plasma-generator" }) {
            final Map<String, String> settings = Map.of("rotor", "Ichorium", "size", "Large");
            final PowerSource source = source(id);
            final double full = source.compute(settings)
                .euPerTick();
            final TurbineFlow.Check check = TurbineFlow.turbineFlowCheck(source, settings, 0.5, 1);
            assertNotNull(check, id);
            final double share = check.flow() / check.optimal();
            assertEquals(share * share, check.euMade() / full, 0.005, id);
            assertEquals(full * 0.5, check.euCounted(), 0.5, id);
        }
    }
}
