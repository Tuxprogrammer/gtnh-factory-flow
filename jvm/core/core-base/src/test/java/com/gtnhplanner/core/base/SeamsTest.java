package com.gtnhplanner.core.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The seams' defaults (a core run with no host) and installing a host's own. */
class SeamsTest {

    @Test
    void logFillsPlaceholdersInOrderAndKeepsTheRest() {
        assertEquals("a 1 b two {}", Log.fill("a {} b {} {}", 1, "two"));
        assertEquals("no args {}", Log.fill("no args {}"));
        assertEquals("{x} {", Log.fill("{x} {", 3));
    }

    @Test
    void defaultsNeedNoHost() {
        assertEquals(CoreSettings.SOLVER_EFFORT_DEFAULT, CoreSettings.DEFAULTS.solverEffort());
        assertFalse(CoreSettings.DEFAULTS.isFreeIngredient("water"));
        assertFalse(CoreSettings.DEFAULTS.burnableOverride());
        assertEquals("gtnhplanner.some.key", Text.KEYS.translate("gtnhplanner.some.key"));
        assertEquals("gtnhplanner.some.key", Text.KEYS.format("gtnhplanner.some.key", 1, "x"));
        Log.STDERR.debug("dropped {}", 1);
        Log.STDERR.warn("a warning with a cause {}", 1, new IllegalStateException("planted"));
    }

    @Test
    void aHostInstallsItsOwn() {
        final Log logBefore = Log.get();
        final Text textBefore = Text.get();
        final CoreSettings settingsBefore = CoreSettings.get();
        try {
            final List<String> lines = new ArrayList<>();
            final Log log = new Log() {

                @Override
                public void debug(final String message, final Object... args) {
                    lines.add("debug " + Log.fill(message, args));
                }

                @Override
                public void info(final String message, final Object... args) {
                    lines.add("info " + Log.fill(message, args));
                }

                @Override
                public void warn(final String message, final Object... args) {
                    lines.add("warn " + Log.fill(message, args));
                }

                @Override
                public void error(final String message, final Object... args) {
                    lines.add("error " + Log.fill(message, args));
                }
            };
            Log.install(log);
            assertSame(log, Log.get());
            Log.get()
                .warn("{} ran", "solve");
            assertEquals(List.of("warn solve ran"), lines);
            Text.install(new Text() {

                @Override
                public String translate(final String key) {
                    return "words for " + key;
                }

                @Override
                public String format(final String key, final Object... args) {
                    return "words for " + key + " with " + args.length;
                }
            });
            assertEquals("words for k", Text.get()
                .translate("k"));
            assertEquals("words for k with 2", Text.get()
                .format("k", 1, 2));
            assertThrows(NullPointerException.class, () -> CoreSettings.install(null));
        } finally {
            Log.install(logBefore);
            Text.install(textBefore);
            CoreSettings.install(settingsBefore);
        }
    }
}
