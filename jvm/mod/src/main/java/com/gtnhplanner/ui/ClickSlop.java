package com.gtnhplanner.ui;

/**
 * When a press stops being a click: one rule for every control of the planner (cards, drawers, notes, groups, chips,
 * ports, leftover rows, plan tabs and task cards). A press is a click until the mouse has gone {@link #GUI} GUI pixels
 * or {@link #SCREEN} screen pixels from where it went down, whichever is further, along either axis; then it carries
 * what it pressed. A hand that wobbles a few screen pixels while it holds a slow click still clicks at GUI scale 1, and
 * the room in screen pixels only grows with the scale.
 */
public final class ClickSlop {

    /** The least the slop is in GUI pixels, at any scale. */
    public static final int GUI = 6;
    /** The least the slop is in screen pixels, at any scale. */
    public static final int SCREEN = 8;

    private ClickSlop() {}

    /** The slop in GUI pixels at {@code scale} screen pixels to a GUI pixel. */
    public static int guiPixels(final int scale) {
        final int s = Math.max(1, scale);
        return Math.max(GUI, (SCREEN + s - 1) / s);
    }

    /** Whether a press moved {@code dx}, {@code dy} GUI pixels is a drag at {@code scale}. */
    public static boolean moved(final int dx, final int dy, final int scale) {
        return Math.max(Math.abs(dx), Math.abs(dy)) >= guiPixels(scale);
    }

    /** Whether a press moved {@code dx}, {@code dy} planner GUI pixels is a drag, at the planner's scale. */
    public static boolean moved(final int dx, final int dy) {
        return moved(dx, dy, PlannerScale.board());
    }
}
