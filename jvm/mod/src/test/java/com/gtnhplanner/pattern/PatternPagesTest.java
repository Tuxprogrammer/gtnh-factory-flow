package com.gtnhplanner.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** "Pattern the plan" a page at a time: where each page starts and ends, and where the run goes on from. */
class PatternPagesTest {

    private static List<String> recipes(final int n) {
        final List<String> r = new ArrayList<>();
        for (int i = 1; i <= n; i++) r.add("r" + i);
        return r;
    }

    /** One page: start, end by {@code room}, nothing unmade but the page's end; the position after it. */
    private static PatternPages.Position<String> page(final PatternPages.Position<String> at, final List<String> now,
        final int room, final int[] fromTo) {
        final PatternPages.Start<String> s = PatternPages.start(at, now);
        final int end = PatternPages.end(
            s.from(),
            s.order()
                .size(),
            room,
            i -> false);
        fromTo[0] = s.from();
        fromTo[1] = end;
        return PatternPages.after(s.order(), PatternPages.resume(end, List.of()));
    }

    @Test
    void fiftyFiveRecipesPagedByTheFreeSlots() {
        final List<String> plan = recipes(55);
        final int[] fromTo = new int[2];
        PatternPages.Position<String> at = page(null, plan, 27, fromTo);
        assertEquals(0, fromTo[0]);
        assertEquals(27, fromTo[1], "patterns 1 to 27");
        assertEquals(27, at.next());
        assertEquals("Pattern the plan (28 to 55 of 55)", PatternPages.row(at));

        at = page(at, plan, 20, fromTo);
        assertEquals(27, fromTo[0]);
        assertEquals(47, fromTo[1], "patterns 28 to 47 with 20 free");

        at = page(at, plan, 36, fromTo);
        assertEquals(47, fromTo[0]);
        assertEquals(55, fromTo[1], "patterns 48 to 55: the rest");
        assertNull(at, "the plan is done and the run starts over");

        page(at, plan, 36, fromTo);
        assertEquals(0, fromTo[0], "the next run is from the first");
    }

    @Test
    void zeroFreeSlotsKeepsThePosition() {
        final List<String> plan = recipes(55);
        final PatternPages.Position<String> at = new PatternPages.Position<>(plan, 27);
        final PatternPages.Start<String> s = PatternPages.start(at, plan);
        final int end = PatternPages.end(s.from(), 55, 0, i -> false);
        assertEquals(27, end);
        assertEquals(
            27,
            PatternPages.after(s.order(), PatternPages.resume(end, List.of()))
                .next());
        assertNull(PatternPages.after(plan, 0), "nothing made from the first: no position to show");
    }

    @Test
    void aRunKeepsItsOrderWhenCardsMove() {
        final List<String> plan = recipes(5);
        final PatternPages.Position<String> at = new PatternPages.Position<>(plan, 2);
        final PatternPages.Start<String> s = PatternPages.start(at, List.of("r5", "r4", "r3", "r2", "r1"));
        assertFalse(s.restarted());
        assertEquals(plan, s.order(), "the first page's order");
        assertEquals(2, s.from());
    }

    @Test
    void aChangedSetOfRecipesStartsAgainFromTheFirst() {
        final List<String> plan = recipes(5);
        final PatternPages.Position<String> at = new PatternPages.Position<>(plan, 3);
        final List<String> added = new ArrayList<>(plan);
        added.add("r6");
        PatternPages.Start<String> s = PatternPages.start(at, added);
        assertTrue(s.restarted(), "a card added");
        assertEquals(0, s.from());
        assertEquals(added, s.order());
        s = PatternPages.start(at, List.of("r1", "r2", "r3", "r4"));
        assertTrue(s.restarted(), "a card removed");
        s = PatternPages.start(at, List.of("r1", "r2", "r3", "r4", "r9"));
        assertTrue(s.restarted(), "a card's recipe changed");
        assertFalse(
            PatternPages.start(null, plan)
                .restarted(),
            "no run yet is not a restart");
    }

    @Test
    void startingOverDropsThePosition() {
        // The Share menu's second row drops the plan's position before the page is worked out.
        final PatternPages.Start<String> s = PatternPages.start(null, recipes(55));
        assertEquals(0, s.from());
        assertFalse(s.restarted());
        assertEquals(PatternPages.FROM_START, "Pattern the plan from the start");
    }

    @Test
    void survivalIsBoundedByTheBlanks() {
        final List<String> plan = recipes(55);
        final PatternRules.Room room = PatternRules.room(30, new int[] { 12 }, false, 55);
        assertTrue(room.blanksShort());
        final int end = PatternPages.end(0, 55, room.fit(), i -> false);
        assertEquals(12, end);
        final PatternPages.Position<String> at = PatternPages.after(plan, PatternPages.resume(end, List.of()));
        assertEquals(12, at.next(), "out of blanks leaves the position where it stopped");
    }

    @Test
    void recipesThatCannotBePatternedTakeNoSlot() {
        final Set<Integer> cannot = Set.of(1, 3, 4);
        assertEquals(5, PatternPages.end(0, 10, 2, cannot::contains), "0 and 2 made, 1 3 4 passed over");
        assertEquals(2, PatternPages.end(0, 10, 1, cannot::contains), "0 made, then 1 passed over");
        assertEquals(10, PatternPages.end(0, 10, 99, cannot::contains));
    }

    @Test
    void noRoomMidPageLeavesThePositionAtTheFirstNotMade() {
        final List<String> plan = recipes(55);
        final int end = PatternPages.end(0, 55, 27, i -> false);
        // The inventory filled underneath: recipe 20 (index 19) came back with no room, and the rest were not asked.
        final List<Integer> notMade = new ArrayList<>();
        for (int i = 19; i < 27; i++) notMade.add(i);
        final int resume = PatternPages.resume(end, notMade);
        assertEquals(19, resume);
        assertEquals(
            19,
            PatternPages.after(plan, resume)
                .next(),
            "the next run makes pattern 20 first");
    }
}
