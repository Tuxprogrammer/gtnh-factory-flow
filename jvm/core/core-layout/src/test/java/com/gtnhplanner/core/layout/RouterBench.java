package com.gtnhplanner.core.layout;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.conformance.Conformance;

/**
 * The router's speed on the 24 reference boards (conformance/layout/boards.json.gz), by the protocol the side-by-side
 * bench used for the mod's old router: one warm-up route, a fresh thorough route, the busiest box dragged twenty
 * beats of 20 px (each a rough incremental route), then dropped (thorough). Each board is run {@code runs} times and
 * the median kept. Also checks that every route is still the one the conformance data holds (points and crossings).
 * Writes build/bench/router.md. {@code -Pbench --tests '*RouterBench*' [-Pruns=5] [-Pboard=nano] [-Pjfr]}.
 */
@Tag("bench")
class RouterBench {

    private static final int STEPS = 20, DX = 20;

    private static JsonObject read() throws IOException {
        try (InputStream raw = Conformance.open("layout/boards.json.gz"); InputStream in = new GZIPInputStream(raw)) {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            in.transferTo(bytes);
            return new JsonParser().parse(bytes.toString(StandardCharsets.UTF_8))
                .getAsJsonObject();
        }
    }

    record Board(String name, List<BoxRouter.Box> boxes, List<BoxRouter.Wire> wires, int moving, double points) {}

    static List<Board> boards() throws IOException {
        final List<Board> out = new ArrayList<>();
        for (final JsonElement e : read().getAsJsonArray("boards")) {
            final JsonObject board = e.getAsJsonObject();
            final List<BoxRouter.Box> boxes = new ArrayList<>();
            for (final JsonElement b : board.getAsJsonArray("boxes")) {
                final JsonObject o = b.getAsJsonObject();
                boxes.add(
                    new BoxRouter.Box(
                        o.get("key")
                            .getAsString(),
                        o.get("x")
                            .getAsDouble(),
                        o.get("y")
                            .getAsDouble(),
                        o.get("w")
                            .getAsDouble(),
                        o.get("h")
                            .getAsDouble(),
                        o.has("square") && o.get("square")
                            .getAsBoolean()));
            }
            final List<BoxRouter.Wire> wires = new ArrayList<>();
            for (final JsonElement w : board.getAsJsonArray("wires")) {
                final JsonObject o = w.getAsJsonObject();
                wires.add(
                    new BoxRouter.Wire(
                        o.get("key")
                            .getAsString(),
                        o.get("from")
                            .getAsInt(),
                        o.get("to")
                            .getAsInt(),
                        o.get("fromSides")
                            .getAsInt(),
                        o.get("toSides")
                            .getAsInt(),
                        o.get("width")
                            .getAsDouble(),
                        List.of()));
            }
            final JsonArray steps = board.getAsJsonArray("steps");
            final double points = steps.get(0)
                .getAsJsonObject()
                .getAsJsonObject("measure")
                .get("points")
                .getAsDouble();
            out.add(
                new Board(
                    board.get("name")
                        .getAsString(),
                    boxes,
                    wires,
                    board.get("moving")
                        .getAsInt(),
                    points));
        }
        return out;
    }

    static List<BoxRouter.Box> shifted(final Board board, final double dx) {
        final List<BoxRouter.Box> at = new ArrayList<>(board.boxes());
        final BoxRouter.Box m = at.get(board.moving());
        at.set(board.moving(), new BoxRouter.Box(m.key(), m.x() + dx, m.y(), m.w(), m.h(), m.square()));
        return at;
    }

    /** One board's times: fresh route, drag beat average and worst, drop; ms. */
    private static double[] once(final Board board) {
        new BoxRouter().route(board.boxes(), board.wires(), true, null);
        final BoxRouter router = new BoxRouter();
        long t0 = System.nanoTime();
        final Map<String, GridRoutedEdge> fresh = router.route(board.boxes(), board.wires(), true, null);
        final double routeMs = (System.nanoTime() - t0) / 1e6;
        final double points = WireMetrics.points(WireMetrics.measureRoutes(fresh.values()), RouterTuning.DEFAULT);
        if (points != board.points())
            throw new AssertionError(board.name() + ": routes moved (points " + points + ", want " + board.points() + ")");
        double total = 0, worst = 0;
        for (int k = 1; k <= STEPS; k++) {
            t0 = System.nanoTime();
            router.route(shifted(board, k * DX), board.wires(), false, new IncrementalStats());
            final double ms = (System.nanoTime() - t0) / 1e6;
            total += ms;
            worst = Math.max(worst, ms);
        }
        t0 = System.nanoTime();
        router.route(shifted(board, STEPS * DX), board.wires(), true, null);
        final double dropMs = (System.nanoTime() - t0) / 1e6;
        return new double[] { routeMs, total / STEPS, worst, dropMs };
    }

    private static double median(final double[] values) {
        final double[] v = values.clone();
        Arrays.sort(v);
        return v.length % 2 == 1 ? v[v.length / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2;
    }

    /** For a profiler: routes one board once, then drags its busiest box back and forth for a while. */
    @Test
    void dragOnly() throws IOException {
        final String only = System.getProperty("gtnh.bench.drag", "");
        if (only.isEmpty()) return;
        for (final Board b : boards()) {
            if (!b.name()
                .contains(only)) continue;
            final BoxRouter router = new BoxRouter();
            router.route(b.boxes(), b.wires(), true, null);
            final long t0 = System.nanoTime();
            int beats = 0;
            for (int pass = 0; pass < Integer.getInteger("gtnh.bench.passes", 10); pass++)
                for (int k = 1; k <= STEPS; k++, beats++) {
                final IncrementalStats s = new IncrementalStats();
                final long b0 = System.nanoTime();
                router.route(shifted(b, (pass % 2 == 0 ? k : STEPS - k) * DX), b.wires(), false, s);
                if (pass == 0) System.out.println(
                    "[beat] " + k + " routed " + s.routed + " kept " + s.kept + " pops " + s.pops + " priced " + s.priced
                        + " searches " + s.searches + " " + (System.nanoTime() - b0) / 1e6 + " ms");
            }
            System.out.println("[drag] " + b.name() + " " + (System.nanoTime() - t0) / 1e6 / beats + " ms a beat");
        }
    }

    @Test
    void bench() throws IOException {
        if (!System.getProperty("gtnh.bench.drag", "")
            .isEmpty()) return;
        final int runs = Integer.getInteger("gtnh.bench.runs", 3);
        final String only = System.getProperty("gtnh.bench.board", "");
        final List<Board> boards = new ArrayList<>();
        for (final Board b : boards()) if (b.name()
            .contains(only)) boards.add(b);
        final double[][][] times = new double[boards.size()][4][runs];
        for (int run = 0; run < runs; run++) {
            for (int i = 0; i < boards.size(); i++) {
                final double[] t = once(boards.get(i));
                for (int k = 0; k < 4; k++) times[i][k][run] = t[k];
            }
        }
        final StringBuilder table = new StringBuilder(
            "| board | boxes | wires | route ms | drag beat avg ms | drag beat max ms | drop ms |\n|---|---|---|---|---|---|---|\n");
        final double[] totals = new double[4];
        for (int i = 0; i < boards.size(); i++) {
            final Board b = boards.get(i);
            final double[] m = new double[4];
            for (int k = 0; k < 4; k++) {
                m[k] = median(times[i][k]);
                totals[k] += m[k];
            }
            table.append(
                String.format(
                    Locale.ROOT,
                    "| %s | %d | %d | %.1f | %.2f | %.2f | %.1f |%n",
                    b.name(),
                    b.boxes()
                        .size(),
                    b.wires()
                        .size(),
                    m[0],
                    m[1],
                    m[2],
                    m[3]));
        }
        table.append(
            String.format(
                Locale.ROOT,
                "| **total** (median of %d runs) | | | %.1f | %.2f | %.2f | %.1f |%n",
                runs,
                totals[0],
                totals[1],
                totals[2],
                totals[3]));
        final File out = new File("build/bench");
        out.mkdirs();
        Files.writeString(new File(out, "router.md").toPath(), table.toString(), StandardCharsets.UTF_8);
        System.out.println(table);
    }
}
