package com.gtnhplanner.core.layout.arrange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.layout.GridPoint;
import com.gtnhplanner.core.layout.RouterTuning;

/** The game's judge (boxes and wires, no route requests) and the arrange it drives, as the board runs it. */
class BoxJudgeTest {

    private static final List<ArrangeCard> CARDS = List.of(
        new ArrangeCard("a", 0, 0, 320, 200, false),
        new ArrangeCard("b", 900, 400, 320, 200, false),
        new ArrangeCard("c", 300, 700, 320, 200, false),
        new ArrangeCard("d", 1400, 0, 320, 200, false),
        new ArrangeCard("s", 600, 0, 120, 60, true));

    private static final List<ArrangeWire> WIRES = List.of(
        new ArrangeWire("ab", "a", "b", 60.0, 60.0, null, 8.0),
        new ArrangeWire("ac", "a", "c", 100.0, 60.0, null, 4.0),
        new ArrangeWire("cd", "c", "d", 60.0, 100.0, null, 4.0),
        new ArrangeWire("bd", "b", "d", 60.0, 60.0, null, 12.0),
        new ArrangeWire("sa", "s", "a", null, 140.0, null, 4.0));

    private static Map<String, GridPoint> at(final ArrangeResult r) {
        final Map<String, GridPoint> out = new LinkedHashMap<>();
        for (final ArrangeResult.Move m : r.moves()) out.put(m.id(), m.position());
        return out;
    }

    private static Arrange.Input input(final com.gtnhplanner.core.base.Worker worker) {
        return new Arrange.Input(
            CARDS,
            WIRES,
            null,
            null,
            "compact",
            new BoxJudge(CARDS, WIRES, RouterTuning.DEFAULT),
            null,
            null,
            null,
            () -> 0,
            worker);
    }

    @Test
    void crossingsNameTheWiresByTheirIds() {
        final BoxJudge judge = new BoxJudge(CARDS, WIRES, RouterTuning.DEFAULT);
        // a's wire to b drawn straight through where c's wire to d runs.
        final Map<String, GridPoint> crossed = new LinkedHashMap<>();
        crossed.put("a", new GridPoint(0, 0));
        crossed.put("b", new GridPoint(800, 600));
        crossed.put("c", new GridPoint(0, 600));
        crossed.put("d", new GridPoint(800, 0));
        final Arrange.Verdict v = judge.judge(crossed, false, null);
        assertTrue(v.points() > 0);
        final List<String> named = new ArrayList<>();
        for (final var e : v.events()) {
            named.add(e.a());
            named.add(e.b());
        }
        for (final String id : named) assertTrue(List.of("ab", "ac", "cd", "bd", "sa").contains(id), id);
    }

    @Test
    void theStagedArrangeOnASecondThreadLandsWhereTheSerialOneDoes() {
        final ArrangeResult serial = Arrange.arrangeBoard(input(null));
        final ArrangeResult staged = Arrange.arrangeStaged(input(new com.gtnhplanner.core.base.Worker() {

            @Override
            public <T> com.gtnhplanner.core.base.Worker.Job<T> start(final java.util.function.Supplier<T> work) {
                final T result = work.get();
                return () -> result;
            }
        }));
        assertEquals(at(serial), at(staged));
        // Every card on the grid, none on another.
        final Map<String, GridPoint> places = at(serial);
        for (final ArrangeCard c : CARDS) {
            final GridPoint p = places.get(c.id());
            assertEquals(0, p.x() % 20);
            assertEquals(0, p.y() % 20);
            for (final ArrangeCard o : CARDS) {
                if (o == c) continue;
                final GridPoint q = places.get(o.id());
                assertFalse(
                    p.x() < q.x() + o.width() && q.x() < p.x() + c.width()
                        && p.y() < q.y() + o.height()
                        && q.y() < p.y() + c.height(),
                    c.id() + " on " + o.id());
            }
        }
    }
}
