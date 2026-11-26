package com.gtnhplanner.ui;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.cleanroommc.modularui.screen.ModularPanel;
import com.gtnhplanner.GtnhPlanner;

/**
 * Lets go of a mouse button in every panel once it is released. ModularUI sends a panel's mouse drags to the widget
 * its last press went to, and takes a new press's widget only while the panel holds no button. Two releases never
 * reach that hold: one ModularUI's draggables take first (a click or drag on the board or a card), and one a focused
 * widget answers (its path skips letting go). The panel then holds the button for good, and every later press in it
 * leaves its drags with the old widget: a scroll bar or slider pressed afterwards never moves. After every release,
 * each panel still holding that button lets go, as it would have had it heard the release.
 */
final class PanelHolds {

    private static Field mouse, held, lastButton;
    private static Method reset;
    private static boolean resolved, broken;

    private PanelHolds() {}

    /** Whether a panel holding {@code lastButton} ({@code held}) still holds it once {@code released} is let go. */
    static boolean stale(final boolean held, final int lastButton, final int released) {
        return held && lastButton == released;
    }

    /** {@code button} is up: every panel still holding it lets go. */
    static void released(final Iterable<ModularPanel> panels, final int button) {
        if (!resolve()) return;
        for (final ModularPanel panel : panels) {
            try {
                final Object input = mouse.get(panel);
                if (stale(held.getBoolean(input), lastButton.getInt(input), button)) reset.invoke(input);
            } catch (final ReflectiveOperationException | RuntimeException e) {
                broken = true;
                GtnhPlanner.LOG.warn("Could not let go of a panel's mouse hold", e);
                return;
            }
        }
    }

    private static synchronized boolean resolve() {
        if (resolved) return !broken;
        resolved = true;
        try {
            mouse = ModularPanel.class.getDeclaredField("mouse");
            mouse.setAccessible(true);
            final Class<?> input = mouse.getType();
            held = input.getDeclaredField("held");
            lastButton = input.getDeclaredField("lastButton");
            reset = input.getDeclaredMethod("reset");
            held.setAccessible(true);
            lastButton.setAccessible(true);
            reset.setAccessible(true);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            broken = true;
            GtnhPlanner.LOG
                .warn("ModularUI's panel mouse state was not found; drags after a lost release may stall", e);
        }
        return !broken;
    }
}
