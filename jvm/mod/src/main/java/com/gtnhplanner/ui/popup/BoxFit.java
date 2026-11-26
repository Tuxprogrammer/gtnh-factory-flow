package com.gtnhplanner.ui.popup;

/**
 * The screen bound every box opens under, apart from drawing: a box is never wider or taller than its room less
 * {@link #MARGIN} on each side and never placed partly outside it. A box whose content can grow declares a header, a
 * scrolling body and a footer ({@link Frame}); the body gives way first, so the header and the footer's keys stay on
 * screen. Rooms are {x, y, w, h}.
 */
public final class BoxFit {

    /** The gap kept between a box and each edge of its room. */
    public static final int MARGIN = 2;

    private BoxFit() {}

    /** The most a box may measure in a room, {w, h}. */
    public static int[] most(final int[] room) {
        return new int[] { Math.max(0, room[2] - 2 * MARGIN), Math.max(0, room[3] - 2 * MARGIN) };
    }

    /** A box {@code w} by {@code h} cut down to what the room shows, {w, h}. */
    public static int[] size(final int[] room, final int w, final int h) {
        final int[] most = most(room);
        return new int[] { Math.min(w, most[0]), Math.min(h, most[1]) };
    }

    /** The top-left nearest {@code x, y} that keeps a box {@code w} by {@code h} wholly inside the room. */
    public static int[] place(final int[] room, final int x, final int y, final int w, final int h) {
        return new int[] { Math.max(room[0] + MARGIN, Math.min(x, room[0] + room[2] - w - MARGIN)),
            Math.max(room[1] + MARGIN, Math.min(y, room[1] + room[3] - h - MARGIN)) };
    }

    /** The gap between a point a box opens from and the box. */
    public static final int CLEAR = 4;

    /**
     * The top-left for a box {@code w} by {@code h} opened from the point {@code ax, ay}, wholly inside the room and
     * clear of the point: under it, else over it, else right of it, else left of it, whichever the room keeps clear
     * first. A box opened under the pointer would take the next click meant for what was under it (on a short screen
     * the spare box landed with its drawer link under the pointer). Only when no side has room does it cover the point.
     */
    public static int[] beside(final int[] room, final int ax, final int ay, final int w, final int h) {
        final int[][] tries = { place(room, ax, ay + CLEAR, w, h), place(room, ax, ay - CLEAR - h, w, h),
            place(room, ax + CLEAR, ay - h / 2, w, h), place(room, ax - CLEAR - w, ay - h / 2, w, h) };
        for (final int[] t : tries) if (ax < t[0] || ax >= t[0] + w || ay < t[1] || ay >= t[1] + h) return t;
        return tries[0];
    }

    /**
     * A box of a fixed header, a body {@code content} tall and a fixed footer, in a room {@code roomH} tall: the body
     * window ({@code view}) is the content when it fits, else what is left of the room once the header and footer are
     * placed; {@code maxScroll} is how far the body scrolls (0 when it fits).
     */
    public record Frame(int header, int content, int footer, int view, int height, int maxScroll) {

        /** The body window's top and bottom, in the box. */
        public int bodyTop() {
            return header;
        }

        public int bodyBottom() {
            return header + view;
        }

        /** The footer's top, in the box. */
        public int footerTop() {
            return header + view;
        }

        /** Whether the body scrolls (and shows a thumb). */
        public boolean scrolls() {
            return maxScroll > 0;
        }

        /** {@code scroll} kept inside the body's range. */
        public int clamp(final int scroll) {
            return Math.max(0, Math.min(maxScroll, scroll));
        }

        /** The content y under a box y with the body scrolled to {@code scroll}; -1 outside the body window. */
        public int contentY(final int boxY, final int scroll) {
            if (boxY < bodyTop() || boxY >= bodyBottom()) return -1;
            return boxY - bodyTop() + clamp(scroll);
        }

        /** Whether content rows {@code y} to {@code y + h} show whole in the body window at {@code scroll}. */
        public boolean shows(final int y, final int h, final int scroll) {
            final int s = clamp(scroll);
            return y >= s && y + h <= s + view;
        }

        /** The least scroll from {@code scroll} that shows content rows {@code y} to {@code y + h}. */
        public int reveal(final int y, final int h, final int scroll) {
            final int s = clamp(scroll);
            if (y < s) return clamp(y);
            if (y + h > s + view) return clamp(y + h - view);
            return s;
        }
    }

    /** The frame for a body {@code content} tall between a header and a footer, in a room {@code roomH} tall. */
    public static Frame frame(final int roomH, final int header, final int content, final int footer) {
        final int room = Math.max(0, roomH - 2 * MARGIN - header - footer);
        final int view = Math.max(0, Math.min(content, room));
        return new Frame(header, content, footer, view, header + view + footer, Math.max(0, content - view));
    }
}
