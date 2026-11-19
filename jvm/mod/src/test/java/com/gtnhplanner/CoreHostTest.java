package com.gtnhplanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.base.CoreSettings;
import com.gtnhplanner.core.base.Log;
import com.gtnhplanner.core.base.Text;
import com.gtnhplanner.core.base.Uuid;

/** The mod's seams are installed for every test (harness/CoreHostSession) and read the mod's settings live. */
class CoreHostTest {

    @Test
    void theModsSeamsAreInstalled() {
        assertNotSame(Log.STDERR, Log.get());
        assertNotSame(CoreSettings.DEFAULTS, CoreSettings.get());
        assertNotSame(Text.KEYS, Text.get());
    }

    @Test
    void settingsAreReadLiveFromTheConfig() {
        final int restore = Config.solverEffortPercent;
        try {
            Config.solverEffortPercent = 150;
            assertEquals(
                150,
                CoreSettings.get()
                    .solverEffort());
            Config.solverEffortPercent = 10_000;
            assertEquals(
                Config.SOLVER_EFFORT_MAX,
                CoreSettings.get()
                    .solverEffort());
        } finally {
            Config.solverEffortPercent = restore;
        }
        Config.setFreeIngredients("Heavy Naquadah Fuel");
        try {
            assertTrue(
                CoreSettings.get()
                    .isFreeIngredient("  heavy naquadah fuel "));
        } finally {
            Config.resetFreeIngredients();
        }
    }

    @Test
    void aMissingTranslationReadsAsItsKey() {
        assertEquals(
            "gtnhplanner.no.such.key",
            Text.get()
                .translate("gtnhplanner.no.such.key"));
        assertEquals(
            "gtnhplanner.no.such.key",
            Text.get()
                .format("gtnhplanner.no.such.key", 1));
    }

    @Test
    void newIdsAreRandomVersionFour() {
        assertEquals(
            4,
            Uuid.randomUUID()
                .version());
    }
}
