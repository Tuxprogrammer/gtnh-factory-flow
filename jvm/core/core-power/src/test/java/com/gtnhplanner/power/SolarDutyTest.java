package com.gtnhplanner.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.power.PowerCatalog;

/** The website's Solar Boiler and Solar Tower duty cases (fork issue 171). */
class SolarDutyTest {

    private static PowerModel compute(final String id, final Map<String, String> settings) {
        final PowerSource source = PowerCatalog.get(id);
        assertNotNull(source);
        return source.compute(settings);
    }

    @Test
    void scalesTheSolarBoilersSteamAndWaterByDuty() {
        assertEquals(360.0, compute("solar-boiler", Map.of("model", "steel")).outputs().get(0).perSecond(), 0);
        final PowerModel half = compute("solar-boiler", Map.of("model", "steel", "duty", "50"));
        assertEquals(180.0, half.outputs().get(0).perSecond(), 0);
        assertEquals(180.0 / 160, half.inputs().get(0).perSecond(), 1e-12);
    }

    @Test
    void scalesTheSolarTowersSaltByDuty() {
        assertEquals(883.0, compute("solar-tower", Map.of("rings", "5")).outputs().get(0).perSecond(), 0);
        assertEquals(441.5, compute("solar-tower", Map.of("rings", "5", "duty", "50")).outputs().get(0).perSecond(), 0);
    }
}
