package com.gtnhplanner.core.layout.arrange;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.gtnhplanner.core.layout.BoxRouter;
import com.gtnhplanner.core.layout.GridObstacle;
import com.gtnhplanner.core.layout.GridPoint;
import com.gtnhplanner.core.layout.JsMath;
import com.gtnhplanner.core.layout.RouteJudge;
import com.gtnhplanner.core.layout.RouterTuning;
import com.gtnhplanner.core.layout.WireMetrics;

/**
 * An arrange judge for a host that has boxes and wires rather than the website's route requests (the game's board):
 * the board's own router ({@link RouteJudge}) on requests built as {@link BoxRouter} builds them, every box an
 * obstacle and a rim of docks, drawers met square, a wire from a box back to itself leaving right and coming round
 * left. Positions are judged on whole px, as the board stands its cards. Keeps verdicts between calls, so one per
 * arrange and thread.
 */
public final class BoxJudge implements Arrange.Judge {

    private final RouteJudge judge;
    /** Each routed wire's key to the id it was given (null: the wire cannot be named, so its crossings blame no one). */
    private final Map<String, String> idOf = new HashMap<>();

    public BoxJudge(final List<ArrangeCard> cards, final List<ArrangeWire> wires, final RouterTuning tuning) {
        final Map<String, Integer> index = new HashMap<>();
        final List<BoxRouter.Box> boxes = new ArrayList<>(cards.size());
        for (int i = 0; i < cards.size(); i++) {
            final ArrangeCard c = cards.get(i);
            index.put(c.id(), i);
            boxes.add(
                new BoxRouter.Box(
                    c.id(),
                    JsMath.round(c.x()),
                    JsMath.round(c.y()),
                    JsMath.round(c.width()),
                    JsMath.round(c.height()),
                    c.storage()));
        }
        final List<BoxRouter.Wire> routed = new ArrayList<>();
        for (final ArrangeWire w : wires) {
            final Integer a = index.get(w.source()), b = index.get(w.target());
            if (a == null || b == null) continue;
            final String key = w.id() != null ? w.id() : w.source() + ">" + w.target() + "#" + routed.size();
            idOf.put(key, w.id());
            final double width = w.width() != null ? w.width() : 4;
            routed.add(
                a.equals(b) ? new BoxRouter.Wire(key, a, b, BoxRouter.RIGHT, BoxRouter.LEFT, width, List.of())
                    : new BoxRouter.Wire(key, a, b, BoxRouter.ANY, BoxRouter.ANY, width, List.of()));
        }
        final List<GridObstacle> obstacles = new ArrayList<>(boxes.size());
        for (final BoxRouter.Box box : boxes)
            obstacles.add(new GridObstacle(box.key(), box.x(), box.y(), box.x() + box.w(), box.y() + box.h()));
        judge = new RouteJudge(obstacles, BoxRouter.requests(boxes, routed), tuning, List.of());
    }

    @Override
    public Arrange.Verdict judge(final Map<String, GridPoint> positions, final boolean quick,
        @Nullable final Map<String, GridPoint> base) {
        final RouteJudge.Verdict v = judge.judge(places(positions), quick, base == null ? null : places(base));
        final List<WireMetrics.Crossing> events = new ArrayList<>(
            v.events()
                .size());
        for (final WireMetrics.Crossing c : v.events()) {
            final String a = idOf.get(c.a()), b = idOf.get(c.b());
            if (a != null && b != null) events.add(new WireMetrics.Crossing(c.point(), a, b));
        }
        return new Arrange.Verdict(v.crossings(), v.length(), v.points(), events);
    }

    private static Map<String, GridPoint> places(final Map<String, GridPoint> positions) {
        final Map<String, GridPoint> out = new LinkedHashMap<>();
        for (final Map.Entry<String, GridPoint> e : positions.entrySet()) out.put(
            e.getKey(),
            new GridPoint(
                JsMath.round(
                    e.getValue()
                        .x()),
                JsMath.round(
                    e.getValue()
                        .y())));
        return out;
    }
}
