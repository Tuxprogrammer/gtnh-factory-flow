package com.gtnhplanner.ui.popup;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Every offer that stands over the board (a box the planner raises by itself and waits on: the tour's, an update's, a
 * crash report's, an incoming plan's) holds the board while it is open, so the board's keys and mouse wait for an
 * answer. The rule is {@link Popup#holdsBoard} (the board's keys check it in {@code BoardScreen.boardHeld}, and it
 * shuts off the panels below); a new offer that forgets it fails here.
 */
class OfferHoldsBoardTest {

    private static final Path SRC = Path.of("src/main/java/com/gtnhplanner/ui");

    @Test
    void everyOfferBoxHoldsTheBoard() throws IOException {
        final List<String> missing = new ArrayList<>(), offers = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SRC)) {
            for (final Path f : files.filter(
                p -> p.getFileName()
                    .toString()
                    .endsWith("Offer.java"))
                .toList()) {
                final String src = Files.readString(f);
                if (!src.contains("new Popup(")) continue;
                offers.add(
                    f.getFileName()
                        .toString());
                if (!src.matches("(?s).*public boolean holdsBoard\\(\\)\\s*\\{\\s*return true;.*")) missing.add(
                    f.getFileName()
                        .toString());
            }
        }
        assertTrue(offers.size() >= 3, "offers found: " + offers);
        assertTrue(missing.isEmpty(), "offers that let the board's keys act under them: " + missing);
    }

    @Test
    void theTourOfferTakesPressesAndTheWheelAndTheBoardKeysCheckIt() throws IOException {
        final String tour = Files.readString(SRC.resolve("TourOffer.java"));
        assertTrue(tour.contains("onMouseScroll"), "the wheel under the tour's offer zooms the board");
        final String board = Files.readString(SRC.resolve("BoardScreen.java"));
        assertTrue(board.contains("if (boardHeld())"), "the board's keys do not check for a box holding the board");
        assertTrue(board.contains("\"tourOffer\") instanceof final TourOffer"), "boardHeld does not count the tour");
    }

    @Test
    void aPlainBoxDoesNotHoldTheBoard() throws IOException {
        // Menus and number boxes leave the board as it is: only offers wait for an answer.
        final String popup = Files.readString(SRC.resolve("popup/Popup.java"));
        assertTrue(popup.contains("return holdsBoard();"), "disablePanelsBelow does not follow holdsBoard");
        assertFalse(
            popup.matches("(?s).*public boolean holdsBoard\\(\\)\\s*\\{\\s*return true;.*"),
            "every box holds the board");
    }
}
