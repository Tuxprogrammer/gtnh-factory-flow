package com.gtnhplanner.harness;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

import com.gtnhplanner.CoreHost;

/**
 * Installs the mod's side of the core's seams before any test runs, as loading the mod class does in the game, so a
 * test that sets a {@code Config} field sees the solver read it. Registered in
 * {@code META-INF/services/org.junit.platform.launcher.LauncherSessionListener}.
 */
public final class CoreHostSession implements LauncherSessionListener {

    @Override
    public void launcherSessionOpened(final LauncherSession session) {
        CoreHost.install();
    }
}
