package com.gtnhplanner.layout.arrange;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.gtnhplanner.core.layout.BoxRouter;
import com.gtnhplanner.core.layout.GridObstacle;
import com.gtnhplanner.core.layout.GridPoint;
import com.gtnhplanner.core.layout.RouteJudge;
import com.gtnhplanner.core.layout.RouterTuning;
import com.gtnhplanner.core.layout.WireMetrics;

/**
 * The arrange's judge on the board's own router ({@link RouteJudge}, the website's judge): routes the real wires with
 * every card at a layout's places (cards it is not told about stay where they are) and scores them in the router's
 * points, so the arrange optimises the picture the board will actually draw. Wires and boxes are asked for exactly as
 * the board asks ({@link BoxRouter}); a full verdict routes afresh and is remembered by layout, a quick one re-solves
 * only the wires the trial's moves touched.
 */
public final class RouterJudge implements Arrange.Judge {

    private final List<ArrangeCard> cards;
    private final List<ArrangeWire> given;
    /** Each routed wire's key to the id it was given (null: it cannot be named). */
    private final Map<String, String> idOf = new HashMap<>();
    private final RouteJudge judge;

    public RouterJudge(final List<ArrangeCard> cards, final List<ArrangeWire> wires) {
        this.cards = List.copyOf(cards);
        this.given = List.copyOf(wires);
        final Map<String, Integer> index = new HashMap<>();
        final List<BoxRouter.Box> boxes = new ArrayList<>(cards.size());
        for (int i = 0; i < cards.size(); i++) {
            final ArrangeCard c = cards.get(i);
            index.put(c.id(), i);
            // On whole px, as the board stands them; drawers are met square.
            boxes.add(
                new BoxRouter.Box(
                    c.id(),
                    Math.round(c.x()),
                    Math.round(c.y()),
                    Math.round(c.width()),
                    Math.round(c.height()),
                    c.storage()));
        }
        final List<BoxRouter.Wire> routed = new ArrayList<>();
        for (final ArrangeWire w : wires) {
            final Integer a = index.get(w.source()), b = index.get(w.target());
            if (a == null || b == null) continue;
            final String key = w.id() != null ? w.id() : w.source() + ">" + w.target() + "#" + routed.size();
            idOf.put(key, w.id());
            final double width = w.width() != null ? w.width() : 4;
            // A wire from a card back to itself (two recipes on one shared machine) leaves right and comes round left.
            routed.add(
                a.equals(b) ? new BoxRouter.Wire(key, a, b, BoxRouter.RIGHT, BoxRouter.LEFT, width, List.of())
                    : new BoxRouter.Wire(key, a, b, BoxRouter.ANY, BoxRouter.ANY, width, List.of()));
        }
        final List<GridObstacle> obstacles = new ArrayList<>(boxes.size());
        for (final BoxRouter.Box box : boxes)
            obstacles.add(new GridObstacle(box.key(), box.x(), box.y(), box.x() + box.w(), box.y() + box.h()));
        judge = new RouteJudge(obstacles, BoxRouter.requests(boxes, routed), RouterTuning.DEFAULT, List.of());
    }

    @Override
    public Arrange.Judge fork() {
        return new RouterJudge(cards, given);
    }

    @Override
    public Arrange.Verdict judge(final Map<String, Point> positions, final boolean quickly,
        final Map<String, Point> base) {
        final RouteJudge.Verdict v = judge.judge(places(positions), quickly, base == null ? null : places(base));
        final List<String[]> events = new ArrayList<>(
            v.events()
                .size());
        for (final WireMetrics.Crossing c : v.events()) {
            final String a = idOf.get(c.a()), b = idOf.get(c.b());
            if (a != null && b != null) events.add(new String[] { a, b });
        }
        return new Arrange.Verdict(v.crossings(), v.length(), v.points(), events);
    }

    /** A layout's top-lefts on whole px, by card id. */
    private static Map<String, GridPoint> places(final Map<String, Point> positions) {
        final Map<String, GridPoint> out = new HashMap<>();
        for (final Map.Entry<String, Point> e : positions.entrySet()) out.put(
            e.getKey(),
            new GridPoint(
                Math.round(
                    e.getValue()
                        .x()),
                Math.round(
                    e.getValue()
                        .y())));
        return out;
    }
}
