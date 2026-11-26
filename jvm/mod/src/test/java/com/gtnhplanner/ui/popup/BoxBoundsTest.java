package com.gtnhplanner.ui.popup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * No box leaves a small screen. Boxes cannot be built without the game, so every place that makes one is listed here by
 * file with how its size is bounded, and the sources are read to check the list is whole: a new box fails this test
 * until it is listed. Each way of bounding a box is then checked at 640 by 360 with content far too big for it.
 */
class BoxBoundsTest {

    private static final Path SRC = Path.of("src/main/java/com/gtnhplanner");
    private static final int[] SMALL = { 0, 0, 640, 360 };

    /** How a box's size is bounded. */
    enum Bound {
        /** A {@link FramedPopup}: header and footer kept, the body scrolls in the room's height. */
        FRAMED,
        /** A {@link PickList}: as many rows as the room shows, the rest scroll. */
        LIST,
        /** Sized from the room as it is built, scrolling inside where it has more. */
        ROOM,
        /** A fixed size, under the small screen's height (the number is the box's height). */
        FIXED,
        /**
         * Content that can grow past the screen with no scroll: cut to the screen and placed on it by {@link Popup},
         * so it never leaves it, but its lower part can be out of reach. None is allowed
         * ({@link #noBoxGrowsPastTheScreen}).
         */
        GROWS,
        /** The box classes themselves. */
        FRAMEWORK
    }

    record Listed(int boxes, Bound bound, int height, String what) {

        Listed(final int boxes, final Bound bound, final String what) {
            this(boxes, bound, 0, what);
        }
    }

    /** Every file that makes a box, how many it makes, and how they are bounded. */
    private static final Map<String, Listed> BOXES = new TreeMap<>();

    static {
        list("pattern/PatternPick.java", new Listed(1, Bound.FRAMED, "products in the pattern"));
        list("ui/BoardScreen.java", new Listed(4, Bound.LIST, "account, share and help menus, the board's help"));
        list("ui/BoardSession.java", new Listed(2, Bound.LIST, "delete or leave a plan; delete several cards"));
        list("ui/canvas/BoardCanvas.java", new Listed(6, Bound.LIST, "board, wire, drop, arrange and rule menus"));
        list("ui/card/RecipeCard.java", new Listed(8, Bound.LIST, "card menus and setting choices"));
        list("ui/card/RecipePicks.java", new Listed(2, Bound.LIST, "mob and crop picks"));
        list("ui/drawer/DrawerCard.java", new Listed(2, Bound.LIST, "drawer menus"));
        list("ui/group/GroupCard.java", new Listed(2, Bound.LIST, "group menu and colours"));
        list("ui/library/LibraryView.java", new Listed(4, Bound.LIST, "plan and setup menus"));
        list("ui/note/NoteCard.java", new Listed(4, Bound.LIST, "note menus, styles and colours"));
        list("ui/PlanTabs.java", new Listed(2, Bound.LIST, "new plan and tab menus"));
        list("ui/popup/PlanFileMenu.java", new Listed(1, Bound.LIST, "open a plan file"));
        list("ui/power/PowerPicker.java", new Listed(2, Bound.LIST, "generator types and tiers"));
        list("ui/task/TaskActions.java", new Listed(2, Bound.LIST, "task boards and picks"));
        list("ui/task/TaskBoardView.java", new Listed(1, Bound.LIST, "task menu"));
        list(
            "ui/task/TaskPanel.java",
            new Listed(7, Bound.ROOM, "the task box (room, its page scrolls) and its six menus"));
        list("ui/popup/SharePanel.java", new Listed(1, Bound.ROOM, "share: its lists give way, then scroll"));
        list("ui/popup/ScreenshotPanel.java", new Listed(1, Bound.ROOM, "screenshot"));
        list("ui/task/PictureView.java", new Listed(1, Bound.ROOM, "a task's picture, its shape kept"));
        list(
            "ui/library/AccountForms.java",
            new Listed(4, Bound.FIXED, 164, "sign in (164), post (158), edit post (158); icon list"));
        list("ui/popup/CrashOffer.java", new Listed(1, Bound.FIXED, 104, "crash report offer"));
        list("ui/popup/IncomingOffer.java", new Listed(1, Bound.FIXED, 70, "incoming plan offer"));
        list("ui/popup/UpdateOffer.java", new Listed(1, Bound.FIXED, 94, "update offer"));
        list("ui/popup/NumberPopup.java", new Listed(1, Bound.FIXED, 56, "number box"));
        list("ui/popup/TextPopup.java", new Listed(1, Bound.FIXED, 44, "text box"));
        list("ui/popup/FeedbackPanel.java", new Listed(1, Bound.FIXED, 235, "feedback"));
        list(
            "ui/popup/SettingsPanel.java",
            new Listed(
                1,
                Bound.ROOM,
                "settings: no taller than the room, a section's settings scroll under the title"));
        list(
            "ui/card/SettingsSheet.java",
            new Listed(1, Bound.FRAMED, "card settings: the settings scroll, the line on how it works kept"));
        list(
            "ui/SparePopup.java",
            new Listed(1, Bound.FRAMED, "spare split: its places scroll, the drawer link kept under them"));
        list("ui/popup/Popup.java", new Listed(2, Bound.FRAMEWORK, "Popup and its toggled key"));
        list("ui/popup/FramedPopup.java", new Listed(1, Bound.FRAMEWORK, "FramedPopup"));
        list("ui/popup/PickList.java", new Listed(1, Bound.FRAMEWORK, "PickList's popup"));
    }

    private static void list(final String file, final Listed listed) {
        BOXES.put(file, listed);
    }

    /**
     * What makes a box: a Popup or FramedPopup made or extended, or a PickList popup (not the tour's target finder).
     */
    private static final Pattern MAKES = Pattern
        .compile("new (Framed)?Popup\\(|extends (Framed)?Popup\\b|(?<!Targets)\\.popup\\(");

    @Test
    void everyBoxIsListed() throws IOException {
        final Map<String, Integer> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(SRC)) {
            for (final Path f : files.filter(
                p -> p.toString()
                    .endsWith(".java"))
                .toList()) {
                int n = 0;
                for (final String line : Files.readAllLines(f)) {
                    final Matcher m = MAKES.matcher(line);
                    while (m.find()) n++;
                }
                if (n > 0) found.put(
                    SRC.relativize(f)
                        .toString()
                        .replace('\\', '/'),
                    n);
            }
        }
        final Map<String, Integer> listed = new TreeMap<>();
        BOXES.forEach((file, l) -> listed.put(file, l.boxes()));
        assertEquals(
            listed,
            found,
            "a box was added or removed: list it in BoxBoundsTest with how its size is bounded on a small screen");
    }

    @Test
    void anyBoxIsCutToTheScreenAndPlacedOnIt() {
        final int[] room = { 0, 0, 440, 360 };
        for (final int[] want : new int[][] { { 300, 100 }, { 2000, 100 }, { 300, 3000 }, { 5000, 5000 } }) {
            final int[] s = BoxFit.size(SMALL, want[0], want[1]);
            for (final int[] at : new int[][] { { -100, -100 }, { 600, 300 }, { 9999, 9999 } }) {
                final int[] inRoom = BoxFit.place(room, at[0], at[1], s[0], s[1]);
                final int[] p = BoxFit.place(SMALL, inRoom[0], inRoom[1], s[0], s[1]);
                assertInside(SMALL, p[0], p[1], s[0], s[1]);
            }
        }
    }

    @Test
    void aFramedBoxKeepsItsKeysOnASmallScreen() {
        final BoxFit.Frame f = BoxFit.frame(SMALL[3], 26, 100_000, 30);
        assertTrue(f.height() <= SMALL[3] - 2 * BoxFit.MARGIN);
        assertTrue(f.footerTop() + f.footer() <= f.height(), "the footer is inside the box");
        assertTrue(f.view() > 0 && f.scrolls());
    }

    @Test
    void aListShowsOnlyTheRowsTheRoomHas() {
        // Above the rows: the popup's edge, then a title, a filter, or both.
        for (final int above : new int[] { 8, 22, 26, 40 }) {
            final int rows = PickRows.rowsInRoom(SMALL[3], above, PickList.ROW);
            assertTrue(
                above + rows * PickList.ROW <= SMALL[3] - 2 * BoxFit.MARGIN,
                "a list " + above + " over its rows");
        }
    }

    @Test
    void fixedBoxesFitTheSmallScreen() {
        for (final Map.Entry<String, Listed> e : BOXES.entrySet()) {
            if (e.getValue()
                .bound() != Bound.FIXED) continue;
            assertTrue(
                e.getValue()
                    .height() > 0
                    && e.getValue()
                        .height() <= SMALL[3] - 2 * BoxFit.MARGIN,
                e.getKey());
        }
    }

    @Test
    void noBoxGrowsPastTheScreen() {
        final List<String> grows = BOXES.entrySet()
            .stream()
            .filter(
                e -> e.getValue()
                    .bound() == Bound.GROWS)
            .map(Map.Entry::getKey)
            .toList();
        assertEquals(List.of(), grows, "a box whose lower part can be out of reach: put it on FramedPopup");
    }

    /** A box listed as framed or room-sized is built that way: its source uses the bound it is listed with. */
    @Test
    void eachBoxUsesTheBoundItIsListedWith() throws IOException {
        for (final Map.Entry<String, Listed> e : BOXES.entrySet()) {
            final String src = Files.readString(SRC.resolve(e.getKey()));
            switch (e.getValue()
                .bound()) {
                case FRAMED -> assertTrue(src.contains("FramedPopup"), e.getKey() + " is listed as a FramedPopup");
                case ROOM -> assertTrue(
                    src.contains("room(") || src.contains("BoxFit."),
                    e.getKey() + " is listed as sized from the room");
                case LIST -> assertTrue(
                    src.contains("PickList") || src.contains(".popup("),
                    e.getKey() + " is listed as a list");
                default -> {}
            }
        }
    }

    private static void assertInside(final int[] r, final int x, final int y, final int w, final int h) {
        assertTrue(
            x >= r[0] && y >= r[1] && x + w <= r[0] + r[2] && y + h <= r[1] + r[3],
            x + "," + y + " " + w + "x" + h + " leaves the screen");
    }
}
