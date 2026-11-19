package com.gtnhplanner.core.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.conformance.Conformance;

/**
 * A board held between calls and told only what changed answers as the incremental router told the whole board each
 * time, and hands out exactly the routes that moved: on the reference boards, dragged and dropped as the conformance
 * run drags them.
 */
class RouterBoardTest {

    /** The reference boards up to this many wires (the big one is the conformance run's, and slow). */
    private static final int MAX_WIRES = 130;

    private record Board(String name, List<BoxRouter.Box> boxes, List<BoxRouter.Wire> wires, int moving) {}

    private static List<Board> boards() throws IOException {
        final JsonObject root;
        try (InputStream in = new GZIPInputStream(Conformance.open("layout/boards.json.gz"))) {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            in.transferTo(bytes);
            root = new JsonParser().parse(bytes.toString(StandardCharsets.UTF_8))
                .getAsJsonObject();
        }
        final List<Board> out = new ArrayList<>();
        for (final JsonElement e : root.getAsJsonArray("boards")) {
            final JsonObject b = e.getAsJsonObject();
            final JsonArray wiresJson = b.getAsJsonArray("wires");
            if (wiresJson.size() > MAX_WIRES) continue;
            final List<BoxRouter.Box> boxes = new ArrayList<>();
            for (final JsonElement x : b.getAsJsonArray("boxes")) {
                final JsonObject o = x.getAsJsonObject();
                boxes.add(new BoxRouter.Box(o.get("key").getAsString(), o.get("x").getAsDouble(), o.get("y").getAsDouble(),
                    o.get("w").getAsDouble(), o.get("h").getAsDouble(),
                    o.has("square") && !o.get("square").isJsonNull() && o.get("square").getAsBoolean()));
            }
            final List<BoxRouter.Wire> wires = new ArrayList<>();
            for (final JsonElement x : wiresJson) {
                final JsonObject o = x.getAsJsonObject();
                wires.add(new BoxRouter.Wire(o.get("key").getAsString(), o.get("from").getAsInt(), o.get("to").getAsInt(),
                    o.get("fromSides").getAsInt(), o.get("toSides").getAsInt(), o.get("width").getAsDouble(), List.of()));
            }
            out.add(new Board(b.get("name").getAsString(), boxes, wires, b.get("moving").getAsInt()));
        }
        return out;
    }

    private static List<GridObstacle> obstacles(final List<BoxRouter.Box> boxes) {
        final List<GridObstacle> out = new ArrayList<>();
        for (final BoxRouter.Box b : boxes) out.add(b.obstacle());
        return out;
    }

    /** The host's side: what it last told the board, and the routes it holds. */
    private static final class Host {

        final RouterBoard board = new RouterBoard();
        final Map<String, GridObstacle> toldObstacles = new LinkedHashMap<>();
        final Map<String, GridRouteRequest> toldRequests = new LinkedHashMap<>();
        final Map<String, List<GridPoint>> held = new LinkedHashMap<>();

        RouterBoard.Changes route(final List<GridObstacle> obstacles, final List<GridRouteRequest> requests,
            final boolean thorough) {
            final Map<String, GridObstacle> nextObstacles = new LinkedHashMap<>();
            for (final GridObstacle o : obstacles) nextObstacles.put(o.id(), o);
            for (final String id : toldObstacles.keySet()) if (!nextObstacles.containsKey(id)) board.removeObstacle(id);
            for (final GridObstacle o : obstacles) if (!o.equals(toldObstacles.get(o.id()))) board.putObstacle(o);
            if (!new ArrayList<>(toldObstacles.keySet()).equals(new ArrayList<>(nextObstacles.keySet())))
                board.orderObstacles(new ArrayList<>(nextObstacles.keySet()));
            final Map<String, GridRouteRequest> nextRequests = new LinkedHashMap<>();
            for (final GridRouteRequest r : requests) nextRequests.put(r.edgeId(), r);
            for (final String id : toldRequests.keySet()) if (!nextRequests.containsKey(id)) board.removeRequest(id);
            for (final GridRouteRequest r : requests) if (!r.equals(toldRequests.get(r.edgeId()))) board.putRequest(r);
            if (!new ArrayList<>(toldRequests.keySet()).equals(new ArrayList<>(nextRequests.keySet())))
                board.orderRequests(new ArrayList<>(nextRequests.keySet()));
            toldObstacles.clear();
            toldObstacles.putAll(nextObstacles);
            toldRequests.clear();
            toldRequests.putAll(nextRequests);
            final RouterBoard.Changes changes = board.route(RouterTuning.DEFAULT, thorough);
            for (final GridRoutedEdge r : changes.routes()) {
                assertNotEquals(r.points(), held.get(r.edgeId()), "handed out an unchanged route " + r.edgeId());
                held.put(r.edgeId(), r.points());
            }
            for (final String id : changes.gone()) held.remove(id);
            return changes;
        }
    }

    private static void same(final String where, final Map<String, GridRoutedEdge> want,
        final Map<String, List<GridPoint>> got) {
        assertEquals(want.keySet(), got.keySet(), where + " wires");
        for (final GridRoutedEdge r : want.values()) assertEquals(r.points(), got.get(r.edgeId()), where + " " + r.edgeId());
    }

    @TestFactory
    List<DynamicTest> referenceBoardsAnswerAsTheWholeBoardDoes() throws IOException {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final Board b : boards()) tests.add(DynamicTest.dynamicTest(b.name(), () -> drag(b)));
        assertTrue(tests.size() > 10);
        return tests;
    }

    private static void drag(final Board b) {
        final IncrementalRouter whole = new IncrementalRouter();
        final Host host = new Host();
        for (int beat = 0; beat <= 4; beat++) {
            final double shift = Math.min(beat, 3) * 20;
            final boolean thorough = beat == 0 || beat == 4;
            final List<BoxRouter.Box> at = new ArrayList<>(b.boxes());
            final BoxRouter.Box m = at.get(b.moving());
            at.set(b.moving(), new BoxRouter.Box(m.key(), m.x() + shift, m.y(), m.w(), m.h(), m.square()));
            final List<GridRouteRequest> requests = BoxRouter.requests(at, b.wires());
            final IncrementalStats stats = new IncrementalStats();
            final Map<String, GridRoutedEdge> want = whole.solve(obstacles(at), requests, RouterTuning.DEFAULT, thorough,
                stats);
            final RouterBoard.Changes changes = host.route(obstacles(at), requests, thorough);
            same(b.name() + " beat " + beat, want, host.held);
            assertEquals(stats.routed, changes.stats().routed, b.name() + " routed");
            assertEquals(stats.kept, changes.stats().kept, b.name() + " kept");
            if (beat == 0) assertEquals(requests.size(), changes.routes()
                .size(), "the first answer hands out every route");
        }
        // A wire leaves, then the board is routed with no change at all.
        if (b.wires()
            .size() < 2) return;
        final List<BoxRouter.Wire> fewer = new ArrayList<>(b.wires());
        final BoxRouter.Wire left = fewer.remove(0);
        final List<BoxRouter.Box> at = new ArrayList<>(b.boxes());
        final BoxRouter.Box m = at.get(b.moving());
        at.set(b.moving(), new BoxRouter.Box(m.key(), m.x() + 60, m.y(), m.w(), m.h(), m.square()));
        final List<GridRouteRequest> requests = BoxRouter.requests(at, fewer);
        final Map<String, GridRoutedEdge> want = whole.solve(obstacles(at), requests, RouterTuning.DEFAULT, true, null);
        final RouterBoard.Changes changes = host.route(obstacles(at), requests, true);
        assertTrue(changes.gone()
            .contains(left.key()), "the wire that left is gone");
        same(b.name() + " a wire left", want, host.held);
        whole.solve(obstacles(at), requests, RouterTuning.DEFAULT, true, null);
        assertEquals(0, host.route(obstacles(at), requests, true)
            .routes()
            .size(), "nothing changed, nothing handed out");
    }

    @Test
    void restoredBoardDiffsAgainstTheStoredRoutes() throws IOException {
        final Board b = boards().stream()
            .filter(x -> x.wires()
                .size() >= 20)
            .findFirst()
            .orElseThrow();
        final IncrementalRouter whole = new IncrementalRouter();
        final List<GridRouteRequest> requests = BoxRouter.requests(b.boxes(), b.wires());
        whole.solve(obstacles(b.boxes()), requests, RouterTuning.DEFAULT, true, null);
        final IncrementalRouter.Snapshot stored = whole.snapshot();
        final Host host = new Host();
        host.board.restore(stored);
        final IncrementalStats stats = new IncrementalStats();
        final RouterBoard.Changes changes = host.route(obstacles(b.boxes()), requests, true);
        assertEquals(requests.size(), changes.routes()
            .size(), "a restored board hands out every route once");
        assertEquals(requests.size(), changes.stats().kept, "and keeps them all");
        same("restored", whole.solve(obstacles(b.boxes()), requests, RouterTuning.DEFAULT, true, stats), host.held);
    }

    @Test
    void anOrderMustNameEveryIdOnce() {
        final RouterBoard board = new RouterBoard();
        board.putObstacle(new GridObstacle("a", 0, 0, 20, 20));
        board.putObstacle(new GridObstacle("b", 40, 0, 60, 20));
        assertThrows(IllegalArgumentException.class, () -> board.orderObstacles(List.of("a")));
        assertThrows(IllegalArgumentException.class, () -> board.orderObstacles(List.of("a", "c")));
        board.orderObstacles(List.of("b", "a"));
        assertEquals(2, board.obstacleCount());
    }
}
