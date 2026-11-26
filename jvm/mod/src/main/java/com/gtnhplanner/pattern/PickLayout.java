package com.gtnhplanner.pattern;

import javax.annotation.Nullable;

import com.gtnhplanner.ui.popup.BoxFit;

/**
 * Where everything in the "Products in the pattern" box goes, apart from drawing: the title on top, the recipes' rows
 * (a head over each of a shared machine's recipes) in a body that scrolls when the room is too short, and Cancel and
 * Make pattern in the footer. Body positions are content coordinates (y from the first row, the scroll added).
 */
final class PickLayout {

    static final int W = 260, PAD = 8, TITLE_H = 18, HEAD_H = 14, ROW = 20, FOOT_H = 30, KEY_H = 18, SWITCH_W = 22,
        SWITCH_H = 12;
    /** The header: the padding over the title and the title's line. */
    static final int HEADER = PAD + TITLE_H;
    /** The keys' top in the footer. */
    static final int KEY_Y = FOOT_H - PAD - KEY_H;

    private final boolean[] heads;
    private final int[] counts, firstRow;
    private final int content;

    /** One recipe per entry: whether it has a head, and how many rows. */
    PickLayout(final boolean[] heads, final int[] counts) {
        this.heads = heads;
        this.counts = counts;
        firstRow = new int[counts.length];
        int y = 0;
        for (int i = 0; i < counts.length; i++) {
            if (heads[i]) y += HEAD_H;
            firstRow[i] = y;
            y += counts[i] * ROW;
        }
        content = y;
    }

    int contentHeight() {
        return content;
    }

    /** The box laid out in a room {@code roomH} tall. */
    BoxFit.Frame frame(final int roomH) {
        return BoxFit.frame(roomH, HEADER, content, FOOT_H);
    }

    /** The content y of recipe {@code recipe}'s head (its first row's when it has none). */
    int headTop(final int recipe) {
        return firstRow[recipe] - (heads[recipe] ? HEAD_H : 0);
    }

    /** The content y of a row's top. */
    int rowTop(final int recipe, final int row) {
        return firstRow[recipe] + row * ROW;
    }

    /** The recipe and row at content point {@code x, y}, {recipe, row}; null off every row (or {@code y} -1). */
    @Nullable
    int[] rowAt(final int x, final int y) {
        if (y < 0 || x < PAD || x >= W - PAD) return null;
        for (int i = 0; i < counts.length; i++)
            if (y >= firstRow[i] && y < firstRow[i] + counts[i] * ROW) return new int[] { i, (y - firstRow[i]) / ROW };
        return null;
    }

    /** The row under a box point with the body scrolled to {@code scroll}: none outside the body's window. */
    @Nullable
    int[] rowAtBox(final BoxFit.Frame f, final int scroll, final int x, final int y) {
        return rowAt(x, f.contentY(y, scroll));
    }

    /** Make pattern's key in the footer, {x, y, w, h}, for a label {@code makeW} wide. */
    static int[] makeRect(final int makeW) {
        final int w = makeW + 24;
        return new int[] { W - PAD - w, KEY_Y, w, KEY_H };
    }

    /** Cancel's key in the footer, left of Make pattern's. */
    static int[] cancelRect(final int cancelW, final int makeW) {
        final int w = cancelW + 16;
        return new int[] { makeRect(makeW)[0] - 6 - w, KEY_Y, w, KEY_H };
    }

    /** A footer rect moved into the box. */
    static int[] inBox(final BoxFit.Frame f, final int[] footerRect) {
        return new int[] { footerRect[0], f.footerTop() + footerRect[1], footerRect[2], footerRect[3] };
    }

    static boolean in(final int[] r, final int x, final int y) {
        return x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3];
    }
}
