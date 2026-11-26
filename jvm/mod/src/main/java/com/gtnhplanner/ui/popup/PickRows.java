package com.gtnhplanner.ui.popup;

/**
 * The row geometry of a {@link PickList}, apart from drawing: rows have their own heights (a rule is thin), and the
 * window shows whole rows from the first one scrolled to, so y, hover and scroll range all come from these sums.
 */
final class PickRows {

    private PickRows() {}

    /** The height of the first {@code count} rows: the popup is exactly this tall. */
    static int total(final int[] heights, final int from, final int count) {
        int sum = 0;
        for (int i = from; i < from + count && i < heights.length; i++) sum += heights[i];
        return sum;
    }

    /** The y, from the top of the window, of row {@code index} when the window starts at row {@code scroll}. */
    static int top(final int[] heights, final int scroll, final int index) {
        return total(heights, scroll, index - scroll);
    }

    /** How many whole rows from {@code scroll} fit in a window {@code view} tall. */
    static int fitting(final int[] heights, final int scroll, final int view) {
        int used = 0;
        int n = 0;
        for (int i = scroll; i < heights.length && used + heights[i] <= view; i++) {
            used += heights[i];
            n++;
        }
        return n;
    }

    /** The row under {@code y} (from the top of the window), or -1 below the last whole row or above the first. */
    static int rowAt(final int[] heights, final int scroll, final int y, final int view) {
        if (y < 0) return -1;
        int top = 0;
        final int end = scroll + fitting(heights, scroll, view);
        for (int i = scroll; i < end; i++) {
            top += heights[i];
            if (y < top) return i;
        }
        return -1;
    }

    /** The furthest the window can scroll: the first row from which the rest all fit; 0 when everything fits. */
    static int maxScroll(final int[] heights, final int view) {
        int s = 0;
        while (s < heights.length && total(heights, s, heights.length - s) > view) s++;
        return s;
    }

    /**
     * The scroll whose first row starts nearest {@code px} down the whole list, kept in range: what a dragged scroll
     * thumb, which moves in pixels, means for a list that scrolls by rows.
     */
    static int scrollAt(final int[] heights, final int px, final int view) {
        final int max = maxScroll(heights, view);
        int s = 0, top = 0;
        while (s < max && top + heights[s] / 2 < px) top += heights[s++];
        return s;
    }

    /** The scroll that shows row {@code index} with the least movement from {@code scroll}. */
    static int reveal(final int[] heights, final int scroll, final int index, final int view) {
        if (index < scroll) return index;
        int s = scroll;
        while (s < index && index >= s + fitting(heights, s, view)) s++;
        return s;
    }
}
