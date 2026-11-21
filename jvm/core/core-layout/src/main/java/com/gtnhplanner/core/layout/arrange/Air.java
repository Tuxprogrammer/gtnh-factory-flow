package com.gtnhplanner.core.layout.arrange;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

import com.gtnhplanner.core.layout.GridEdgeRouter;
import com.gtnhplanner.core.layout.GridPoint;

/**
 * Air between strangers, the website's {@code board-arrange-air.ts}: the readability term the arrange adds on top of
 * the router's points, and how islands emerge without any island rule.
 *
 * <p>
 * Strangers are cards three or more hops apart in the wire graph, or not connected. Each card pays {@code perPixel}
 * points per px its nearest stranger stands closer than {@link #ISLAND_AIR_CELLS}; per card rather than per pair, so
 * the dial means the same on small and large boards. Wired cards attract through the wire's length; cards two hops
 * apart are neutral. A drawer serving exactly one machine stands in for that machine in the hop count, so two drawers
 * on neighbouring machines are not strangers.
 */
public final class Air {

    /** Strangers owe each other this much air, in cells. */
    public static final int ISLAND_AIR_CELLS = 6;
    /** Cards this many hops apart, or further, are strangers. */
    public static final int ISLAND_STRANGER_HOPS = 3;

    private Air() {}

    /**
     * The air term for a board ({@code makeAirTerm}): given every card's top-left, the price owed by every card whose
     * nearest stranger stands too close. Pairs whose cards are absent from the map are skipped. Not for two threads at
     * once (it keeps one scratch array, as the website's does).
     */
    public static ToDoubleFunction<Map<String, GridPoint>> makeAirTerm(final List<ArrangeCard> cards,
        final List<ArrangeWire> wires, final double perPixel) {
        if (perPixel <= 0 || cards.size() < 2) return positions -> 0;
        final Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < cards.size(); i++) index.put(
            cards.get(i)
                .id(),
            i);
        final int n = cards.size();
        final List<List<Integer>> partners = new ArrayList<>(n);
        for (int i = 0; i < n; i++) partners.add(new ArrayList<>());
        for (final ArrangeWire wire : wires) {
            final Integer a = index.get(wire.source());
            final Integer b = index.get(wire.target());
            if (a == null || b == null || a.intValue() == b.intValue()) continue;
            partners.get(a)
                .add(b);
            partners.get(b)
                .add(a);
        }
        // A drawer with one partner is that partner for distance purposes.
        final int[] standIn = new int[n];
        for (int i = 0; i < n; i++) {
            if (!cards.get(i)
                .storage()) {
                standIn[i] = i;
                continue;
            }
            final Set<Integer> unique = new LinkedHashSet<>(partners.get(i));
            standIn[i] = unique.size() == 1 ? unique.iterator()
                .next() : i;
        }
        final List<int[]> strangers = new ArrayList<>();
        final int[] distance = new int[n];
        final int[] queue = new int[n];
        for (int i = 0; i < n; i++) {
            Arrays.fill(distance, -1);
            int tail = 0;
            queue[tail++] = standIn[i];
            distance[standIn[i]] = 0;
            for (int head = 0; head < tail; head++) {
                final int here = queue[head];
                for (final int next : partners.get(here)) {
                    final int who = standIn[next];
                    if (distance[who] < 0) {
                        distance[who] = distance[here] + 1;
                        queue[tail++] = who;
                    }
                }
            }
            for (int k = i + 1; k < n; k++) {
                final int d = distance[standIn[k]];
                if (standIn[i] == standIn[k]) continue;
                if (d < 0 || d >= ISLAND_STRANGER_HOPS) strangers.add(new int[] { i, k });
            }
        }
        final double air = ISLAND_AIR_CELLS * GridEdgeRouter.BOARD_GRID;
        final double[] nearest = new double[n];
        return positions -> {
            // Each card pays for its nearest stranger only, so the term means the same on nine cards as on ninety.
            Arrays.fill(nearest, Double.POSITIVE_INFINITY);
            for (final int[] pair : strangers) {
                final int i = pair[0], k = pair[1];
                final ArrangeCard ci = cards.get(i), ck = cards.get(k);
                final GridPoint p = positions.get(ci.id());
                final GridPoint q = positions.get(ck.id());
                if (p == null || q == null) continue;
                final double gapX = Math.max(Math.max(q.x() - (p.x() + ci.width()), p.x() - (q.x() + ck.width())), 0);
                final double gapY = Math.max(Math.max(q.y() - (p.y() + ci.height()), p.y() - (q.y() + ck.height())), 0);
                final double gap = Math.max(gapX, gapY);
                if (gap < nearest[i]) nearest[i] = gap;
                if (gap < nearest[k]) nearest[k] = gap;
            }
            double owed = 0;
            for (int i = 0; i < n; i++) if (nearest[i] < air) owed += (air - nearest[i]) * perPixel;
            return owed;
        };
    }
}
