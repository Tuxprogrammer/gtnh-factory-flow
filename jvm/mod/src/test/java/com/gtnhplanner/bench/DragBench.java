package com.gtnhplanner.bench;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.core.layout.BoxRouter;
import com.gtnhplanner.core.layout.GridRoutedEdge;
import com.gtnhplanner.core.layout.IncrementalStats;
import com.gtnhplanner.layout.RouteMetrics;

/**
 * Drags a card across a board as the board does while the mouse moves: the busiest card, then a quiet one, forty steps,
 * each routed again incrementally and roughly ({@link BoxRouter#route}), then dropped (routed again thoroughly); and
 * compares
 * what that leaves with routing the final board afresh. {@code -Pbench=drag --tests '*DragBench*'}.
 */
@Tag("bench")
class DragBench {

    private static final String[] BOARDS = { "Farm Power", "FULL PLATLINE", "router-corpus/Untitled design", "HV Oil",
        "router-corpus/Platline", "LUV Superconductor" };

    @Test
    void drag() throws IOException {
        final List<BenchBoard> corpus = LayoutBenchmark.corpus();
        for (final String want : BOARDS) {
            BenchBoard board = null;
            for (final BenchBoard b : corpus) if (b.name()
                .contains(want)
                && b.items()
                    .stream()
                    .anyMatch(i -> i.x() != 0 || i.y() != 0)) {
                        board = b;
                        break;
                    }
            if (board == null) continue;
            // Wires per box, to pick the busiest and a quiet one.
            final Map<Uuid, Integer> count = new HashMap<>();
            for (final BenchBoard.Link l : board.links()) {
                count.merge(l.from(), 1, Integer::sum);
                count.merge(l.to(), 1, Integer::sum);
            }
            final List<BenchBoard.Item> byCount = new ArrayList<>(board.items());
            byCount.sort((a, b) -> count.getOrDefault(b.id(), 0) - count.getOrDefault(a.id(), 0));
            for (final BenchBoard.Item moving : List.of(byCount.get(0), byCount.get(byCount.size() / 3))) {
                drag(board, moving, count.getOrDefault(moving.id(), 0));
            }
        }
    }

    private static void drag(final BenchBoard board, final BenchBoard.Item moving, final int wiresAt) {
        final BoxRouter router = new BoxRouter();
        final List<BoxRouter.Wire> wires = BenchRun.wires(board);
        // Warm up, then the board as it opens.
        new BoxRouter().route(boxes(board, moving, 0, 0), wires, true, null);
        long t0 = System.nanoTime();
        router.route(boxes(board, moving, 0, 0), wires, true, null);
        final double openMs = (System.nanoTime() - t0) / 1e6;
        double worst = 0, total = 0;
        int redone = 0;
        final int steps = 40, dx = 14, dy = 6;
        for (int k = 1; k <= steps; k++) {
            final IncrementalStats stats = new IncrementalStats();
            t0 = System.nanoTime();
            router.route(boxes(board, moving, k * dx, k * dy), wires, false, stats);
            final double ms = (System.nanoTime() - t0) / 1e6;
            worst = Math.max(worst, ms);
            total += ms;
            redone += stats.routed;
        }
        final IncrementalStats drop = new IncrementalStats();
        t0 = System.nanoTime();
        final Map<Uuid, List<int[]>> dropped = pixels(
            router.route(boxes(board, moving, steps * dx, steps * dy), wires, true, drop));
        final double dropMs = (System.nanoTime() - t0) / 1e6;
        final int dropRedone = drop.routed;
        final Map<Uuid, int[]> at = new HashMap<>();
        at.put(moving.id(), new int[] { moving.x() + steps * dx, moving.y() + steps * dy });
        final BenchBoard after = board.placed(at);
        final RouteMetrics.Result kept = BenchRun.measure(after, dropped);
        t0 = System.nanoTime();
        final Map<Uuid, List<int[]>> afresh = pixels(
            new BoxRouter().route(boxes(after, null, 0, 0), wires, true, null));
        final double freshMs = (System.nanoTime() - t0) / 1e6;
        final RouteMetrics.Result clean = BenchRun.measure(after, afresh);
        System.out.printf(
            Locale.ROOT,
            "[drag] %-34s %3d wires at the card  open %7.1f ms  step avg %6.2f max %6.2f ms (%.1f wires a step)  drop %6.1f ms (%d wires)  points %8.0f vs afresh %8.0f (%5.1f ms)  crossings %d vs %d%n",
            board.name()
                .length() > 34 ? board.name()
                    .substring(0, 34) : board.name(),
            wiresAt,
            openMs,
            total / steps,
            worst,
            redone / (double) steps,
            dropMs,
            dropRedone,
            kept.points(),
            clean.points(),
            freshMs,
            kept.crossings(),
            clean.crossings());
    }

    private static Map<Uuid, List<int[]>> pixels(final Map<String, GridRoutedEdge> routes) {
        final Map<Uuid, List<int[]>> out = new HashMap<>();
        for (final GridRoutedEdge r : routes.values()) out.put(Uuid.fromString(r.edgeId()), BoxRouter.pixels(r));
        return out;
    }

    /** The board's boxes, {@code moving} (if given) shifted by (dx, dy). */
    private static List<BoxRouter.Box> boxes(final BenchBoard board, final BenchBoard.Item moving, final int dx,
        final int dy) {
        final List<BoxRouter.Box> out = new ArrayList<>();
        for (final BenchBoard.Item i : board.items()) {
            final boolean m = moving != null && i.id()
                .equals(moving.id());
            out.add(
                new BoxRouter.Box(
                    i.id()
                        .toString(),
                    i.x() + (m ? dx : 0),
                    i.y() + (m ? dy : 0),
                    i.w(),
                    i.h(),
                    i.drawer()));
        }
        return out;
    }
}
