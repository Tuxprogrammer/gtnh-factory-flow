package com.gtnhplanner.ui.theme;

/**
 * A scroll bar's thumb along one axis: how long it is, where it sits for a scroll, and the scroll for the mouse while
 * it is dragged. Pure, so every bar in the planner works it out the same way and the tests hold it.
 *
 * <p>
 * The track runs the whole view. The thumb is as long as the view is of the content, never under {@code least}, and
 * travels the rest of the track; the scroll runs from 0 to content less view over that travel. A press on the thumb
 * takes it where it was grabbed, so it then follows the mouse one to one and stops at both ends; a press on the track
 * centres the thumb on the mouse.
 */
public final class Thumb {

    private final int least;
    private int start, visible, size, travel, max;
    private boolean dragging;
    private int grab;

    /** A thumb never shorter than {@code least} pixels. */
    public Thumb(final int least) {
        this.least = least;
    }

    /** Lays it out for a track from {@code start}, {@code visible} long, over {@code content} of list. */
    public Thumb layout(final int start, final int visible, final int content) {
        this.start = start;
        this.visible = visible;
        max = Math.max(0, content - visible);
        size = max <= 0 ? visible : Math.min(visible, Math.max(least, visible * visible / Math.max(1, content)));
        travel = Math.max(0, visible - size);
        return this;
    }

    /** Whether there is anything to scroll: the content is longer than the view. */
    public boolean scrolls() {
        return max > 0;
    }

    /** The most the list scrolls. */
    public int max() {
        return max;
    }

    /** Where the track starts, and its length (the view's). */
    public int start() {
        return start;
    }

    public int track() {
        return visible;
    }

    /** The thumb's length. */
    public int size() {
        return size;
    }

    /** {@code scroll} kept within the content. */
    public int clamp(final int scroll) {
        return Math.max(0, Math.min(max, scroll));
    }

    /** Where the thumb starts for {@code scroll}. */
    public int at(final int scroll) {
        return start + (max <= 0 ? 0 : Math.round((float) travel * clamp(scroll) / max));
    }

    /** Whether {@code pos} is on the thumb at {@code scroll}. */
    public boolean onThumb(final int pos, final int scroll) {
        final int t = at(scroll);
        return pos >= t && pos < t + size;
    }

    /** A press at {@code pos}: the drag starts, and the scroll it gives. */
    public int press(final int pos, final int scroll) {
        grab = onThumb(pos, scroll) ? pos - at(scroll) : size / 2;
        dragging = true;
        return to(pos);
    }

    /** The scroll for the mouse at {@code pos} while dragging: the thumb's grabbed point under the mouse. */
    public int to(final int pos) {
        if (travel <= 0) return 0;
        final int moved = Math.max(0, Math.min(travel, pos - grab - start));
        return Math.round((float) moved * max / travel);
    }

    public boolean dragging() {
        return dragging;
    }

    /** Lets go; true when it was being dragged. */
    public boolean release() {
        final boolean was = dragging;
        dragging = false;
        return was;
    }
}
