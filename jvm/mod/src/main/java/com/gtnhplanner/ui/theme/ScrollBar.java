package com.gtnhplanner.ui.theme;

import com.cleanroommc.modularui.api.widget.IWidget;

/**
 * A list's scroll bar along its right edge: a thumb to drag, a track to click (the thumb jumps there), drawn wider and
 * brighter while the mouse is on it. The list owns the scroll; this works it out from the thumb ({@link Thumb}), and
 * while the thumb is held it follows the mouse every frame ({@link BarDrag}).
 */
public final class ScrollBar {

    public static final int W = 5;

    private final Thumb thumb = new Thumb(14);
    private final String name;
    private final IWidget owner;
    private int x, top, height;

    /** The bar of {@code owner}'s list, named for the dev harness's list of bars. */
    public ScrollBar(final String name, final IWidget owner) {
        this.name = name;
        this.owner = owner;
    }

    /**
     * Draws the bar for a list {@code visible} tall showing {@code content} of it from {@code scroll}, against the
     * right edge {@code right}; returns the scroll clamped to the content, under the mouse while the thumb is held.
     * Nothing is drawn when everything fits.
     */
    public int draw(final int right, final int top, final int visible, final int content, final int scroll,
        final boolean hot) {
        this.x = right - W - 1;
        this.top = top;
        this.height = visible;
        thumb.layout(top, visible, content);
        final int clamped = thumb.clamp(
            BarDrag.follow(
                thumb,
                owner.getContext()
                    .getAbsMouseY() - owner.getArea().y,
                scroll));
        if (!thumb.scrolls()) return clamped;
        final boolean lit = thumb.dragging() || hot;
        if (lit) Hyb.rect(x - 1, top, W + 1, visible, 0x40000000);
        Hyb.rect(x + 1, thumb.at(clamped), W - 2, thumb.size(), lit ? Hyb.INK : 0xFF6A6C74);
        com.gtnhplanner.dev.DevBars.drawn(name, owner, thumb, false, x + 1, W - 2, clamped);
        return clamped;
    }

    /** Whether there is a bar (the content overflows), and the point is on it. */
    public boolean contains(final int px, final int py) {
        return thumb.scrolls() && px >= x - 2 && px < x + W + 2 && py >= top && py < top + height;
    }

    /**
     * A press on the bar at {@code y}: takes the thumb where it was grabbed, or centres it on the mouse; the scroll.
     */
    public int press(final int y, final int scroll) {
        return thumb.press(y, scroll);
    }

    /** The scroll for the mouse at {@code y} while dragging. */
    public int drag(final int y) {
        return thumb.to(y);
    }

    public boolean dragging() {
        return thumb.dragging();
    }

    /** Lets go; true when it was being dragged. */
    public boolean release() {
        return thumb.release();
    }
}
