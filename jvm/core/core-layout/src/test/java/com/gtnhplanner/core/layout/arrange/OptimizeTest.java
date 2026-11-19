package com.gtnhplanner.core.layout.arrange;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.layout.GridEdgeRouter;
import com.gtnhplanner.core.layout.GridPoint;
import com.gtnhplanner.core.layout.RouterTuning;

/**
 * The arrange's island search (Optimize, the website's board-arrange-optimize.ts): it keeps cards on the grid and
 * apart and drawers on their machine's side, never ends worse than it started, comes out the same every time, and lays
 * an island out exactly as the website does.
 */
class OptimizeTest {

    private static final double CELL = GridEdgeRouter.BOARD_GRID;
    private static final RouterTuning PRICES = RouterTuning.DEFAULT;

    /**
     * A small line: a washer with its water supply on its left, two mills, a centrifuge with two catches on its right,
     * an electrolyzer and a free output drawer. Four columns, two feeder sections in the mills' column, wires of mixed
     * widths and weights, one skipping a column.
     */
    private static final List<Optimize.OptimizeCard> CARDS = List.of(
        machine("washer", 200, 0, 0, 0),
        riding("water", 68, "washer", 0, true),
        machine("mill-a", 160, 1, 0, 0),
        machine("mill-b", 160, 1, 220, 1),
        machine("spinner", 240, 2, 0, 0),
        riding("dust", 68, "spinner", 2, false),
        riding("slag", 128, "spinner", 2, false),
        machine("cell", 200, 3, 0, 0),
        new Optimize.OptimizeCard("out", 136, 68, true, 3, 240, null, null));

    private static final List<Optimize.OptimizeWire> WIRES = List.of(
        wire("water", "washer", null, 6.0),
        wire("washer", "mill-a", null, 10.0),
        wire("washer", "mill-b", null, 8.0),
        wire("mill-a", "spinner", 2.0, null),
        wire("mill-b", "spinner", 1.0, null),
        wire("mill-b", "cell", null, 4.0),
        wire("spinner", "dust", null, 4.0),
        wire("spinner", "slag", null, 4.0),
        wire("spinner", "cell", null, 12.0),
        wire("cell", "out", null, null),
        wire("mill-a", "cell", 0.5, null));

    private static Optimize.OptimizeCard machine(final String id, final double height, final int layer,
        final double seq, final double section) {
        return new Optimize.OptimizeCard(id, 320, height, false, layer, seq, section, null);
    }

    /** A drawer on a machine's side: it carries the machine's column, order and section, as the layered pass gives. */
    private static Optimize.OptimizeCard riding(final String id, final double height, final String anchor,
        final int layer, final boolean left) {
        return new Optimize.OptimizeCard(id, 136, height, true, layer, 0, 0.0, new Optimize.Satellite(anchor, left));
    }

    private static Optimize.OptimizeWire wire(final String from, final String to, final Double weight,
        final Double width) {
        return new Optimize.OptimizeWire(from, to, weight, width);
    }

    /** Options with every spacing at its default and the judge off ({@code judge: false}). */
    private static Optimize.OptimizeOptions noJudge() {
        return new Optimize.OptimizeOptions(null, null, null, null, null, true, null, null, null, null);
    }

    /** Options with every spacing at its default, these trials and this judge. */
    private static Optimize.OptimizeOptions judgedBy(final double trials,
        final ToDoubleFunction<Map<String, GridPoint>> judge) {
        return new Optimize.OptimizeOptions(null, null, null, null, trials, false, judge, PRICES, null, null);
    }

    private static List<ArrangeCard> plain(final List<Optimize.OptimizeCard> cards) {
        final List<ArrangeCard> out = new ArrayList<>();
        for (final Optimize.OptimizeCard card : cards) {
            out.add(new ArrangeCard(card.id(), 0, 0, card.width(), card.height(), card.storage()));
        }
        return out;
    }

    private static Map<String, GridPoint> byId(final List<Optimize.OptimizeCard> cards,
        final List<GridPoint> positions) {
        final Map<String, GridPoint> out = new LinkedHashMap<>();
        int i = 0;
        for (final Optimize.OptimizeCard card : cards) out.put(card.id(), positions.get(i++));
        return out;
    }

    private static List<GridPoint> points(final double... xy) {
        final List<GridPoint> out = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) out.add(new GridPoint(xy[i], xy[i + 1]));
        return out;
    }

    @Test
    void keepsCardsOnTheGridApartAndOnTheirSides() {
        final Optimize.OptimizeResult result = Optimize.optimizeIslandLayout(CARDS, WIRES, noJudge());
        final List<GridPoint> at = result.positions();
        assertEquals(CARDS.size(), at.size());
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        for (final GridPoint p : at) {
            assertEquals(0, p.x() % CELL, "x on the grid: " + p);
            assertEquals(0, p.y() % CELL, "y on the grid: " + p);
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
        }
        assertEquals(0, minX, "normalised to the left edge");
        assertEquals(0, minY, "normalised to the top edge");
        for (int i = 0; i < CARDS.size(); i++) {
            for (int k = i + 1; k < CARDS.size(); k++) {
                final Optimize.OptimizeCard a = CARDS.get(i), b = CARDS.get(k);
                final GridPoint p = at.get(i), q = at.get(k);
                final boolean overlap = p.x() < q.x() + b.width() && q.x() < p.x() + a.width()
                    && p.y() < q.y() + b.height()
                    && q.y() < p.y() + a.height();
                assertFalse(overlap, a.id() + " at " + p + " overlaps " + b.id() + " at " + q);
            }
        }
        final Map<String, GridPoint> placed = byId(CARDS, at);
        final GridPoint water = placed.get("water"), washer = placed.get("washer");
        final GridPoint spinner = placed.get("spinner"), dust = placed.get("dust"), slag = placed.get("slag");
        assertTrue(water.x() + 136 <= washer.x(), "the supply rides on the left");
        assertTrue(dust.x() >= spinner.x() + 320, "a catch rides on the right");
        assertTrue(slag.x() >= spinner.x() + 320, "a catch rides on the right");
        assertTrue(result.after() <= result.before(), "the search never ends worse than it started");
        assertNull(result.points(), "no judge, no points");
        assertEquals(List.of(), result.finalists(), "no judge, no finalists");
    }

    @Test
    void comesOutTheSameEveryTime() {
        final Optimize.OptimizeResult first = Optimize.optimizeIslandLayout(CARDS, WIRES, noJudge());
        final Optimize.OptimizeResult second = Optimize.optimizeIslandLayout(CARDS, WIRES, noJudge());
        assertEquals(first, second);
        final Optimize.OptimizeOptions judged = judgedBy(
            1500,
            p -> Optimize.scoreLayoutProxy(plain(CARDS), WIRES, p, PRICES, null));
        assertEquals(
            Optimize.optimizeIslandLayout(CARDS, WIRES, judged),
            Optimize.optimizeIslandLayout(CARDS, WIRES, judged));
    }

    /** The website's own optimizer, run on this island, gave these. */
    @Test
    void laysTheIslandOutAsTheWebsiteDoes() {
        final Optimize.OptimizeResult unjudged = Optimize.optimizeIslandLayout(CARDS, WIRES, noJudge());
        assertEquals(
            points(UNJUDGED_POSITIONS),
            unjudged.positions());
        assertEquals(UNJUDGED_BEFORE, unjudged.before());
        assertEquals(UNJUDGED_AFTER, unjudged.after());

        final ToDoubleFunction<Map<String, GridPoint>> judge = p -> Optimize
            .scoreLayoutProxy(plain(CARDS), WIRES, p, PRICES, null);
        final Optimize.OptimizeResult judged = Optimize.optimizeIslandLayout(
            CARDS,
            WIRES,
            new Optimize.OptimizeOptions(1.0, 3.0, 2.0, 1.0, 1500.0, false, judge, PRICES, null, null));
        assertEquals(points(JUDGED_POSITIONS), judged.positions());
        assertEquals(JUDGED_POINTS, (double) judged.points());
        assertArrayEquals(JUDGED_FINALIST_POINTS, pointsOf(judged));
    }

    // The website's answers for this island, judge off ({ judge: false }) and judged by the proxy score with tight
    // spacing and 1500 trials, to the bit.
    private static final double[] UNJUDGED_POSITIONS = { 180, 100, 0, 260, 560, 260, 560, 40, 940, 140, 1300, 160, 1300,
        300, 1500, 100, 1580, 0 };
    private static final double UNJUDGED_BEFORE = 4607.568542494924;
    private static final double UNJUDGED_AFTER = 3011.2;
    private static final double[] JUDGED_POSITIONS = { 160, 100, 0, 260, 520, 220, 520, 0, 880, 120, 1220, 120, 1220,
        260, 1400, 60, 1480, 320 };
    private static final double JUDGED_POINTS = 1780;
    /** Three column arrangements made the final. */
    private static final double[] JUDGED_FINALIST_POINTS = { 1780, 2380.284271247462, 3613.8528137423855 };

    /** What the judge gave each finalist. */
    private static double[] pointsOf(final Optimize.OptimizeResult result) {
        final List<Optimize.Finalist> finalists = result.finalists();
        final double[] out = new double[finalists.size()];
        int i = 0;
        for (final Optimize.Finalist finalist : finalists) out[i++] = finalist.points();
        return out;
    }

    @Test
    void theJudgePicksTheFinalistWithTheFewestPoints() {
        final List<ArrangeCard> plain = plain(CARDS);
        final ToDoubleFunction<Map<String, GridPoint>> judge = p -> Optimize
            .scoreLayoutProxy(plain, WIRES, p, PRICES, null);
        final Optimize.OptimizeResult result = Optimize.optimizeIslandLayout(CARDS, WIRES, judgedBy(2000, judge));
        final double[] points = pointsOf(result);
        assertTrue(points.length >= 1 && points.length <= PRICES.finalists(), points.length + " finalists");
        double fewest = Double.POSITIVE_INFINITY;
        for (final double p : points) fewest = Math.min(fewest, p);
        assertEquals(fewest, (double) result.points(), 0);
        // The judge's points depend only on where cards stand relative to each other, so the winner scores the same
        // where it is handed back.
        assertEquals(fewest, judge.applyAsDouble(byId(CARDS, result.positions())), 1e-6);
    }

    @Test
    void aLoneCardOrAnUnwiredIslandStaysPut() {
        final List<Optimize.OptimizeCard> lone = List
            .of(new Optimize.OptimizeCard("a", 100, 60, false, 0, 0, null, null));
        final Optimize.OptimizeResult one = Optimize.optimizeIslandLayout(lone, List.of(), noJudge());
        assertEquals(List.of(new GridPoint(0, 0)), one.positions());
        assertEquals(one.before(), one.after());

        final List<Optimize.OptimizeCard> two = List.of(
            new Optimize.OptimizeCard("a", 100, 60, false, 0, 0, null, null),
            new Optimize.OptimizeCard("b", 100, 60, false, 1, 0, null, null));
        // A wire to a card outside the island and a wire to itself do not count.
        final Optimize.OptimizeResult unwired = Optimize.optimizeIslandLayout(
            two,
            List.of(wire("a", "elsewhere", null, null), wire("b", "b", null, null)),
            noJudge());
        assertEquals(points(0, 0, 160, 0), unwired.positions(), "the layered pass's columns, three cells apart");
        assertEquals(unwired.before(), unwired.after());
    }

    @Test
    void aCrossingScoresWorseThanTheSameBoardUncrossed() {
        final List<ArrangeCard> cards = List.of(
            new ArrangeCard("a1", 0, 0, 100, 100, false),
            new ArrangeCard("a2", 0, 0, 100, 100, false),
            new ArrangeCard("b1", 0, 0, 100, 100, false),
            new ArrangeCard("b2", 0, 0, 100, 100, false));
        final List<Optimize.OptimizeWire> wires = List.of(wire("a1", "b1", null, null), wire("a2", "b2", null, null));
        final Map<String, GridPoint> straight = Map.of(
            "a1",
            new GridPoint(0, 0),
            "a2",
            new GridPoint(0, 200),
            "b1",
            new GridPoint(400, 0),
            "b2",
            new GridPoint(400, 200));
        final Map<String, GridPoint> crossed = Map.of(
            "a1",
            new GridPoint(0, 0),
            "a2",
            new GridPoint(0, 200),
            "b1",
            new GridPoint(400, 200),
            "b2",
            new GridPoint(400, 0));
        final double uncrossedScore = Optimize.scoreLayoutProxy(cards, wires, straight, PRICES, null);
        final double crossedScore = Optimize.scoreLayoutProxy(cards, wires, crossed, PRICES, null);
        assertEquals(600, uncrossedScore, 1e-9, "two straight wires, 300 px each");
        assertTrue(
            crossedScore >= uncrossedScore + PRICES.crossing(),
            "crossed " + crossedScore + " against straight " + uncrossedScore);
        assertEquals(
            uncrossedScore + 25,
            Optimize.scoreLayoutProxy(cards, wires, straight, PRICES, p -> 25),
            1e-9,
            "the air is added on top");
    }
}
