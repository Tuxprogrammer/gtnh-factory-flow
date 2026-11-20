package com.gtnhplanner.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.power.PowerCatalog;

/** The website's reactors.test.ts case on the IC2 fluid reactor's output cap (fork issue 171). */
class Ic2FluidReactorTest {

    @Test
    void heatsAtMostTenThousandLitresOfCoolantASecond() {
        // TileEntityNuclearReactorElectric: one conversion every 20 ticks into a 10,000 L output tank.
        final PowerSource source = PowerCatalog.get("ic2-fluid-reactor");
        assertNotNull(source);
        for (final String rate : new String[] { "10000", "100000" }) {
            final PowerModel model = source.compute(Map.of("design", "custom", "customRate", rate));
            assertEquals(10_000.0, model.outputs().get(0).perSecond(), 0, rate);
        }
    }
}
