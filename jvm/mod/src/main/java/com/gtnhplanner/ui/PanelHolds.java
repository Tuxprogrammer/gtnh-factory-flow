package com.gtnhplanner.ui;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.lwjgl.input.Mouse;

import com.cleanroommc.modularui.api.IMuiScreen;
import com.cleanroommc.modularui.api.event.MouseInputEvent;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.gtnhplanner.GtnhPlanner;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * Lets go of a mouse button in every panel once it is released. ModularUI sends a panel's mouse drags to the widget
 * its last press went to, and takes a new press's widget only while the panel holds no button. Two releases never
 * reach that hold: one ModularUI's draggables take first (a click or drag on the board or a card), and one a focused
 * widget answers (its path skips letting go). The panel then holds the button for good, and every later press in it
 * leaves its drags with the old widget: a scroll bar or slider pressed afterwards never moves. After every release,
 * each panel still holding that button lets go, as it would have had it heard the release.
 * <p>
 * The release a draggable takes never reaches the screen's {@code onMouseRelease}: ModularUI hands it to the draggable
 * in {@code onMouseInputPre}, which is final, and stops there. So the planner hears every release of the real mouse on
 * the input event itself ({@link #INSTANCE}, after ModularUI's own handler, cancelled or not), and the board screen's
 * {@code onMouseRelease} keeps its call for releases the tour sends straight to the screen.
 */
public final class PanelHolds {

    /** Registered on Forge's event bus at start. */
    public static final PanelHolds INSTANCE = new PanelHolds();

    private static Field mouse, held, lastButton;
    private static Method reset;
    private static boolean resolved, broken;

    private PanelHolds() {}

    /** Whether a panel holding {@code lastButton} ({@code held}) still holds it once {@code released} is let go. */
    static boolean stale(final boolean held, final int lastButton, final int released) {
        return held && lastButton == released;
    }

    /** The button a mouse event lets go of, or -1 when it lets go of none (a press, a move, the wheel). */
    static int releasedBy(final int eventButton, final boolean down) {
        return eventButton >= 0 && !down ? eventButton : -1;
    }

    /** A mouse event on the planner, handled: a button it let go of is let go in every panel. */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onMouse(final MouseInputEvent.Pre e) {
        final int button = releasedBy(Mouse.getEventButton(), Mouse.getEventButtonState());
        if (button < 0 || !(e.gui instanceof IMuiScreen ui) || !(ui.getScreen() instanceof BoardScreen board)) return;
        released(
            board.getPanelManager()
                .getOpenPanels(),
            button);
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
