package com.gtnhplanner.core.layout.arrange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.layout.GridEdgeRouter;
import com.gtnhplanner.core.layout.GridPoint;
import com.gtnhplanner.core.layout.RouterTuning;

/**
 * The free placement, the website's board-arrange-free.ts: a chain of seven machines with a branch, a supply drawer on
 * the first machine, a drawer shared by two machines, a product drawer on the last of the chain, and a card with no
 * wires, arranged with a modest search.
 */
class FreeArrangeTest {

    private static final double CELL = GridEdgeRouter.BOARD_GRID;
    private static final double CARD_W = 320, CARD_H = 150, DRAWER_W = 136, DRAWER_H = 68;
    private static final double TRIALS = 4000;
    /** The least air between cards, and the air beside a partner, at the defaults (1 and 2 cells). */
    private static final double GAP = cells(1), BESIDE = cells(2);

    private final List<ArrangeCard> cards = new ArrayList<>();
    private final List<ArrangeWire> wires = new ArrayList<>();
    private final Map<String, ArrangeCard> byId = new LinkedHashMap<>();

    {
        for (int i = 1; i <= 7; i++) card("m" + i, CARD_W, CARD_H, false);
        card("supply", DRAWER_W, DRAWER_H, true);
        card("shared", DRAWER_W, DRAWER_H, true);
        card("product", DRAWER_W, DRAWER_H, true);
        card("lonely", CARD_W, CARD_H, false);
        // The chain, and its branch at m3.
        wire("m1", "m2");
        wire("m2", "m3");
        wire("m3", "m4");
        wire("m4", "m5");
        wire("m3", "m6");
        wire("m6", "m7");
        // A supply on m1, a drawer between m2 and m6, a product of m5.
        wire("supply", "m1");
        wire("m2", "shared");
        wire("shared", "m6");
        wire("m5", "product");
    }

    private static double cells(final double n) {
        return n * CELL;
    }

    private void card(final String id, final double width, final double height, final boolean storage) {
        final ArrangeCard card = new ArrangeCard(id, 0, 0, width, height, storage);
        cards.add(card);
        byId.put(id, card);
    }

    private void wire(final String source, final String target) {
        wires.add(new ArrangeWire(source + ">" + target, source, target, null, null, null, null));
    }

    private FreeArrange.FreeResult arrange(final List<double[]> progress) {
        return FreeArrange.arrangeFree(
            cards,
            wires,
            new FreeArrange.FreeOptions(
                RouterTuning.DEFAULT,
                TRIALS,
                null,
                null,
                (done, total) -> progress.add(new double[] { done, total })));
    }

    private FreeArrange.FreeResult arrange() {
        return arrange(new ArrayList<>());
    }

    private static boolean overlaps(final GridPoint p, final ArrangeCard a, final GridPoint q, final ArrangeCard b,
        final double gap) {
        return p.x() < q.x() + b.width() + gap && q.x() < p.x() + a.width() + gap
            && p.y() < q.y() + b.height() + gap
            && q.y() < p.y() + a.height() + gap;
    }

    /**
     * Every wired card lands on the grid. The shelf does not, here: as on the website it starts the island gap below
     * the components' row, whose height is the tallest card's bottom, and 150 px cards are not a whole number of
     * cells (the website's sizes always are).
     */
    @Test
    void everyCardLandsOnTheGridWithAirBetween() {
        final FreeArrange.FreeResult result = arrange();
        final Map<String, GridPoint> at = result.positions();
        assertEquals(byId.keySet(), at.keySet(), "every card placed");
        double left = Double.POSITIVE_INFINITY, top = Double.POSITIVE_INFINITY;
        for (final Map.Entry<String, GridPoint> entry : at.entrySet()) {
            final GridPoint p = entry.getValue();
            assertEquals(0, p.x() % CELL, entry.getKey() + " on the grid: " + p);
            if (!entry.getKey()
                .equals("lonely")) assertEquals(0, p.y() % CELL, entry.getKey() + " on the grid: " + p);
            left = Math.min(left, p.x());
            top = Math.min(top, p.y());
        }
        assertEquals(
            result.islands()
                .get(0)
                .height() + cells(8),
            at.get("lonely")
                .y(),
            "the shelf: the island gap below the components' row");
        assertEquals(0, left, "the box starts at 0,0");
        assertEquals(0, top, "the box starts at 0,0");
        final List<String> ids = new ArrayList<>(at.keySet());
        for (int i = 0; i < ids.size(); i++) {
            for (int k = i + 1; k < ids.size(); k++) {
                final ArrangeCard a = byId.get(ids.get(i)), b = byId.get(ids.get(k));
                // Drawers in one pattern line may touch; anything with a machine keeps the gap.
                final double gap = a.storage() && b.storage() ? 0 : GAP;
                assertFalse(
                    overlaps(at.get(a.id()), a, at.get(b.id()), b, gap),
                    a.id() + " " + at.get(a.id()) + " and " + b.id() + " " + at.get(b.id()) + " too close");
            }
        }
    }

    @Test
    void aDrawerOnOneMachineStandsBesideIt() {
        final Map<String, GridPoint> at = arrange().positions();
        // The supply stands in the line on m1's left, at the far side of the corridor, within m1's height. The line's
        // x is snapped to the grid, so the air beside it is the beside air rounded to a cell (136 px drawers: 44 px).
        final GridPoint m1 = at.get("m1"), supply = at.get("supply");
        final double room = m1.x() - (supply.x() + DRAWER_W);
        assertTrue(room >= GAP && room <= BESIDE + CELL / 2, "supply just left of m1: " + supply + " " + m1);
        assertTrue(supply.y() >= m1.y() && supply.y() + DRAWER_H <= m1.y() + CARD_H, "within m1's height");
        // The product stands in the line on m5's right, the beside air from it, within its height.
        final GridPoint m5 = at.get("m5"), product = at.get("product");
        assertEquals(m5.x() + CARD_W + BESIDE, product.x(), "product just right of m5: " + product + " " + m5);
        assertTrue(product.y() >= m5.y() && product.y() + DRAWER_H <= m5.y() + CARD_H, "within m5's height");
        // The shared drawer stands between its two machines: in m2's right line when m6 stands to m2's right.
        final GridPoint m2 = at.get("m2"), m6 = at.get("m6"), shared = at.get("shared");
        assertTrue(m6.x() >= m2.x() + CARD_W, "m6 stands right of m2: " + m2 + " " + m6);
        assertEquals(m2.x() + CARD_W + BESIDE, shared.x(), "shared in m2's right line: " + shared);
        assertTrue(shared.y() >= m2.y() && shared.y() + DRAWER_H <= m2.y() + CARD_H, "within m2's height");
    }

    @Test
    void theUnwiredCardGoesOnTheShelfBelow() {
        final FreeArrange.FreeResult result = arrange();
        final Map<String, GridPoint> at = result.positions();
        final GridPoint lonely = at.get("lonely");
        for (final Map.Entry<String, GridPoint> entry : at.entrySet()) {
            if (entry.getKey()
                .equals("lonely")) continue;
            final double bottom = entry.getValue()
                .y()
                + byId.get(entry.getKey())
                    .height();
            assertTrue(lonely.y() >= bottom + cells(8), "below " + entry.getKey() + " with the island gap");
        }
        assertEquals(0, lonely.x(), "the shelf starts at the left edge");
        assertEquals(
            2,
            result.islands()
                .size(),
            "one wired component and the shelf");
        final ArrangeResult.Island component = result.islands()
            .get(0),
            shelf = result.islands()
                .get(1);
        assertTrue(component.backdrop(), "the wired component has a backdrop");
        assertFalse(shelf.backdrop(), "the shelf has none");
        assertEquals(new ArrangeResult.Island(0, lonely.y(), CARD_W, CARD_H, false), shelf);
        for (final Map.Entry<String, GridPoint> entry : at.entrySet()) {
            if (entry.getKey()
                .equals("lonely")) continue;
            final ArrangeCard card = byId.get(entry.getKey());
            final GridPoint p = entry.getValue();
            assertTrue(
                p.x() >= component.x() && p.x() + card.width() <= component.x() + component.width()
                    && p.y() >= component.y()
                    && p.y() + card.height() <= component.y() + component.height(),
                entry.getKey() + " inside its island");
        }
    }

    @Test
    void theSameBoardArrangesTheSameWayTwice() {
        final List<double[]> progress = new ArrayList<>();
        final FreeArrange.FreeResult first = arrange(progress), second = arrange();
        assertEquals(first.positions(), second.positions());
        assertEquals(
            new ArrayList<>(
                first.positions()
                    .keySet()),
            new ArrayList<>(
                second.positions()
                    .keySet()));
        assertEquals(first.islands(), second.islands());
        // Progress runs up to the whole budget.
        final double[] last = progress.get(progress.size() - 1);
        assertEquals(TRIALS, last[0]);
        assertEquals(TRIALS, last[1]);
    }

    /** Positions as the website's own arrangeFree gives them for this board and these options. */
    @Test
    void matchesTheWebsite() {
        final Map<String, GridPoint> website = new LinkedHashMap<>();
        website.put("m1", new GridPoint(180, 40));
        website.put("m2", new GridPoint(720, 40));
        website.put("supply", new GridPoint(0, 80));
        website.put("m3", new GridPoint(1260, 240));
        website.put("shared", new GridPoint(1080, 80));
        website.put("m4", new GridPoint(1800, 240));
        website.put("m6", new GridPoint(1800, 0));
        website.put("m5", new GridPoint(2340, 240));
        website.put("m7", new GridPoint(2340, 0));
        website.put("product", new GridPoint(2700, 280));
        website.put("lonely", new GridPoint(0, 550));
        final FreeArrange.FreeResult result = arrange();
        assertEquals(
            new ArrayList<>(website.entrySet()),
            new ArrayList<>(
                result.positions()
                    .entrySet()));
        assertEquals(
            List.of(
                new ArrangeResult.Island(0, 0, 2836, 390, true),
                new ArrangeResult.Island(0, 550, 320, 150, false)),
            result.islands());
    }

    @Test
    void theExplanationAddsUp() {
        final Map<String, GridPoint> at = arrange().positions();
        final FreeArrange.FreeScore terms = FreeArrange
            .explainFreeScore(cards, wires, at, RouterTuning.DEFAULT, null, null);
        assertTrue(Double.isFinite(terms.total()), "a finite score: " + terms);
        assertEquals(
            terms.own() + terms.flow() + terms.crossings() + terms.air() + terms.sprawl() + terms.tidy(),
            terms.total(),
            1e-9);
        assertTrue(terms.own() > 0 && terms.sprawl() > 0, "wires and a box cost something: " + terms);
    }
}
