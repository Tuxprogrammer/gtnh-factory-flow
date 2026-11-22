package com.gtnhplanner.ui.popup;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PickRowsTest {

    private static final int R = PickList.RULE;
    private static final int W = PickList.ROW;

    /** Three rows, a rule, two rows: the shape of a card's actions menu. */
    private static final int[] MENU = { W, W, W, R, W, W };

    @Test
    void aMenuIsExactlyAsTallAsItsRows() {
        assertEquals(5 * W + R, PickRows.total(MENU, 0, MENU.length));
    }

    @Test
    void rowsBelowARuleSitOnlyARuleHeightLower() {
        assertEquals(0, PickRows.top(MENU, 0, 0));
        assertEquals(3 * W, PickRows.top(MENU, 0, 3));
        assertEquals(3 * W + R, PickRows.top(MENU, 0, 4));
        assertEquals(4 * W + R, PickRows.top(MENU, 0, 5));
    }

    @Test
    void theRowUnderTheMouseIsTheOneAboveAndBelowTheRule() {
        final int view = PickRows.total(MENU, 0, MENU.length);
        assertEquals(2, PickRows.rowAt(MENU, 0, 3 * W - 1, view));
        assertEquals(3, PickRows.rowAt(MENU, 0, 3 * W, view));
        assertEquals(3, PickRows.rowAt(MENU, 0, 3 * W + R - 1, view));
        assertEquals(4, PickRows.rowAt(MENU, 0, 3 * W + R, view));
        assertEquals(5, PickRows.rowAt(MENU, 0, view - 1, view));
        assertEquals(-1, PickRows.rowAt(MENU, 0, view, view));
        assertEquals(-1, PickRows.rowAt(MENU, 0, -1, view));
    }

    @Test
    void plainListsKeepTheOldUniformRows() {
        final int[] plain = { W, W, W, W };
        assertEquals(2, PickRows.rowAt(plain, 0, 2 * W + 3, 4 * W));
        assertEquals(0, PickRows.maxScroll(plain, 4 * W));
    }

    @Test
    void aWindowShowsOnlyWholeRows() {
        final int[] list = { W, W, W, W, W, W };
        assertEquals(3, PickRows.fitting(list, 0, 3 * W + 10));
        assertEquals(-1, PickRows.rowAt(list, 0, 3 * W + 5, 3 * W + 10));
    }

    @Test
    void scrollingMovesRowsUpAndTheRuleWithThem() {
        final int[] list = { W, W, W, R, W, W, W, W };
        final int view = 4 * W;
        assertEquals(4, PickRows.fitting(list, 0, view));
        // From row 2 the window holds a row, a rule and two rows (45), then 15 left: not another whole row.
        assertEquals(4, PickRows.fitting(list, 2, view));
        assertEquals(0, PickRows.top(list, 2, 2));
        assertEquals(W, PickRows.top(list, 2, 3));
        assertEquals(W + R, PickRows.top(list, 2, 4));
        assertEquals(4, PickRows.rowAt(list, 2, W + R, view));
        assertEquals(3, PickRows.rowAt(list, 2, W + R - 1, view));
    }

    @Test
    void theScrollRangeEndsWhereTheLastRowsFit() {
        final int[] list = { W, W, W, R, W, W, W, W };
        final int view = 4 * W;
        // Rows 4..7 are exactly four rows; from 3 it is a rule and four rows (85), which does not fit.
        assertEquals(4, PickRows.maxScroll(list, view));
        assertEquals(0, PickRows.maxScroll(new int[] { W, W }, 2 * W));
    }

    @Test
    void revealScrollsJustFarEnough() {
        final int[] list = { W, W, W, W, W, W, W, W };
        final int view = 3 * W;
        assertEquals(0, PickRows.reveal(list, 0, 2, view));
        assertEquals(3, PickRows.reveal(list, 0, 5, view));
        assertEquals(1, PickRows.reveal(list, 4, 1, view));
    }
}
