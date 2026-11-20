package com.gtnhplanner.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.power.PowerCatalog;

/** The website's singleblocks.test.ts case on the Magic Energy Absorber's warning (fork issue 171). */
class MagicAbsorberTest {

    @Test
    void warnsThatTheGameTakesNoneOfTheListedItems() {
        final PowerSource absorber = PowerCatalog.get("magic-energy-absorber");
        assertNotNull(absorber);
        final PowerModel model = absorber.compute(Map.of());
        assertEquals(
            List.of(
                "The game's Magic Energy Absorber takes none of these items. "
                    + "It runs on a dragon egg above it, ender crystals, enchanted gear and books, and essentia. "
                    + "This card keeps the community sheet's numbers."),
            model.warnings());
        assertTrue(model.euPerTick() > 0);
        final PowerSource converter = PowerCatalog.get("magic-energy-converter");
        assertNotNull(converter);
        assertTrue(converter.compute(Map.of()).warnings().isEmpty());
    }
}
