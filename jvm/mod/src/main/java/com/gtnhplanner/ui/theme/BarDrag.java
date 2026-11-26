package com.gtnhplanner.ui.theme;

/**
 * Carries a scroll bar's thumb with the mouse every frame, read from the mouse itself rather than from ModularUI's drag
 * events. Those go to the widget the panel last saw pressed, which a release the panel never heard of leaves behind
 * (the thumb then never moves), and the last of them comes before the mouse stops (the thumb then stops short of it).
 * The drag ends when the left button is up, wherever it was let go.
 */
public final class BarDrag {

    private BarDrag() {}

    /** The scroll for this frame: under the mouse at {@code pos} while the thumb is dragged, else {@code scroll}. */
    public static int follow(final Thumb thumb, final int pos, final int scroll) {
        if (!thumb.dragging()) return scroll;
        if (!org.lwjgl.input.Mouse.isButtonDown(0)) {
            thumb.release();
            return scroll;
        }
        return thumb.to(pos);
    }
}
