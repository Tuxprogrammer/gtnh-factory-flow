package com.gtnhplanner;

import java.security.SecureRandom;
import java.util.Locale;

import net.minecraft.util.StatCollector;

import com.gtnhplanner.core.base.CoreSettings;
import com.gtnhplanner.core.base.Log;
import com.gtnhplanner.core.base.Text;
import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.flowchart.balancer.SolverThreads;
import com.gtnhplanner.nei.NEIPlanConfig;

import codechicken.nei.NEIClientConfig;

/**
 * The mod's side of the core's seams: log4j for {@link Log}, the Forge config and NEI's settings for
 * {@link CoreSettings} (read on every call, so a changed setting applies at once), Minecraft's language files for
 * {@link Text}, a secure generator for new ids, and the solver's worker threads ({@link SolverThreads}). Installed when
 * the mod class loads, and before every test run (the test sources' launcher listener), so core code reads the same
 * things it read when it called these directly.
 */
public final class CoreHost {

    private CoreHost() {}

    private static boolean installed;

    public static synchronized void install() {
        if (installed) return;
        installed = true;
        Log.install(new Log() {

            @Override
            public void debug(final String message, final Object... args) {
                GtnhPlanner.LOG.debug(message, args);
            }

            @Override
            public void info(final String message, final Object... args) {
                GtnhPlanner.LOG.info(message, args);
            }

            @Override
            public void warn(final String message, final Object... args) {
                GtnhPlanner.LOG.warn(message, args);
            }

            @Override
            public void error(final String message, final Object... args) {
                GtnhPlanner.LOG.error(message, args);
            }
        });
        CoreSettings.install(new CoreSettings() {

            @Override
            public int solverEffort() {
                return Config.solverEffort();
            }

            @Override
            public boolean isFreeIngredient(final String displayName) {
                return Config.isFreeIngredient(displayName);
            }

            @Override
            public boolean burnableOverride() {
                return NEIClientConfig.getSetting(NEIPlanConfig.ConfigBurnableOverride.KEY)
                    .getIntValue(NEIPlanConfig.ConfigBurnableOverride.OFF) == NEIPlanConfig.ConfigBurnableOverride.ON;
            }
        });
        Text.install(new Text() {

            @Override
            public String translate(final String key) {
                return StatCollector.translateToLocal(key);
            }

            @Override
            public String format(final String key, final Object... args) {
                final String local = StatCollector.translateToLocal(key);
                if (local.equals(key)) return key;
                return String.format(Locale.ROOT, local, args);
            }
        });
        final SecureRandom random = new SecureRandom();
        Uuid.installRandom(random::nextLong);
        SolverThreads.install();
        com.gtnhplanner.data.flowchart.ModPlanHost.install();
        com.gtnhplanner.core.model.ResourceRegistry.install(GameResources.INSTANCE);
    }
}
