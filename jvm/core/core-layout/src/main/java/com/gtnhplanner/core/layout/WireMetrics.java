package com.gtnhplanner.core.layout;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Geometry of finished wires, the website's {@code route-metrics.ts}: where they cross, how long they are, how often
 * they bend, and the points a board scores at the router's prices. The router, the arrange and the hosts' score all
 * read these same numbers.
 */
public final class WireMetrics {

    private WireMetrics() {}

    private static final double EPS = 1e-6;

    /** A wire to measure: its id, its polyline and the stroke it routes at (null: 4 px). */
    public record Measured(String edgeId, List<GridPoint> points, @Nullable Double width) {

        static Measured of(final GridRoutedEdge route) {
            return new Measured(route.edgeId(), route.points(), route.width());
        }
    }

    /** One crossing: where, and the two wires. */
    public record Crossing(GridPoint point, String a, String b) {}

    /**
     * The measure: crossings, length, bends of about 45 and 90 degrees and sharper, the crossing events, and the same
     * four each wire's {@link #wireWeight} times over.
     */
    public record Measure(int crossings, double length, int bends45, int bends90, int bendsSharp,
        List<Crossing> events, double weightedCrossings, double weightedLength, double weightedBends45,
        double weightedBends90, double weightedBendsSharp) {}

    /** A wire's weight from its width: 4 px (quietest) weighs 1, 16 px (busiest) 2.5. */
    public static double wireWeight(@Nullable final Double width) {
        return 0.5 + (width != null ? width : 4) / 8;
    }

    private static List<Double> raysAt(final List<GridPoint> points, final GridPoint p) {
        final List<Double> rays = new ArrayList<>();
        for (int i = 1; i < points.size(); i++) {
            final GridPoint a = points.get(i - 1), b = points.get(i);
            final double dx = b.x() - a.x(), dy = b.y() - a.y(), length = JsMath.hypot(dx, dy);
            if (length < EPS || Math.abs(dx * (p.y() - a.y()) - dy * (p.x() - a.x())) > EPS * length) continue;
            final double t = ((p.x() - a.x()) * dx + (p.y() - a.y()) * dy) / (length * length);
            if (t < -EPS || t > 1 + EPS) continue;
            for (final GridPoint q : new GridPoint[] { a, b }) {
                if (JsMath.hypot(q.x() - p.x(), q.y() - p.y()) < EPS) continue;
                final double angle = StrictMath.atan2(q.y() - p.y(), q.x() - p.x());
                boolean seen = false;
                for (final double r : rays) {
                    if (Math.abs(StrictMath.atan2(StrictMath.sin(r - angle), StrictMath.cos(r - angle))) < EPS) {
                        seen = true;
                        break;
                    }
                }
                if (!seen) rays.add(angle);
            }
        }
        return rays;
    }

    /** Four outgoing rays must alternate. A tangency, shared dock or overlap does not cross. */
    private static boolean pathsCrossAt(final Measured a, final Measured b, final GridPoint p) {
        final List<Double> ar = raysAt(a.points(), p), br = raysAt(b.points(), p);
        if (ar.size() != 2 || br.size() != 2) return false;
        final List<double[]> rays = new ArrayList<>(4);
        for (final double angle : ar) rays.add(new double[] { angle, 0 });
        for (final double angle : br) rays.add(new double[] { angle, 1 });
        JsSort.sort(rays, (l, r) -> JsMath.sign(l[0] - r[0]));
        for (int i = 0; i < 4; i++) {
            final double[] r = rays.get(i), next = rays.get((i + 1) % 4);
            if (r[1] == next[1]) return false;
            if (!(Math.abs(StrictMath.atan2(StrictMath.sin(r[0] - next[0]), StrictMath.cos(r[0] - next[0]))) > EPS))
                return false;
        }
        return true;
    }

    /** The bends along one route: {45, 90, sharper}. */
    public static int[] countBends(final List<GridPoint> points) {
        int bends45 = 0, bends90 = 0, bendsSharp = 0;
        double pdx = 0, pdy = 0;
        boolean previous = false;
        for (int i = 1; i < points.size(); i++) {
            final double dx = points.get(i)
                .x()
                - points.get(i - 1)
                    .x();
            final double dy = points.get(i)
                .y()
                - points.get(i - 1)
                    .y();
            final double length = JsMath.hypot(dx, dy);
            if (length < 0.5) continue;
            final double ux = dx / length, uy = dy / length;
            if (previous) {
                final double cos = pdx * ux + pdy * uy;
                if (cos < 0.99) {
                    if (cos > 0.5) bends45 += 1;
                    else if (cos > -0.5) bends90 += 1;
                    else bendsSharp += 1;
                }
            }
            pdx = ux;
            pdy = uy;
            previous = true;
        }
        return new int[] { bends45, bends90, bendsSharp };
    }

    /** A segment of a wire, with its box and its length (hypot of dx, dy), worked out once. */
    private record Segment(GridPoint a, GridPoint b, double dx, double dy, double left, double right, double top,
        double bottom, double length) {}

    /** {@code measureWireRoutes} over routed wires. */
    public static Measure measureRoutes(final Iterable<GridRoutedEdge> routes) {
        final List<Measured> list = new ArrayList<>();
        for (final GridRoutedEdge r : routes) list.add(Measured.of(r));
        return measure(list);
    }

    /** {@code measureWireRoutes}: crossings counted at bends and run ends too, touching ends excepted. */
    public static Measure measure(final List<Measured> routes) {
        final List<List<Segment>> segments = new ArrayList<>(routes.size());
        // Each wire's box (left, right, top, bottom over its segments' boxes): two wires whose boxes are apart have no
        // pair of segments close enough to test, so the pair is passed over whole. Math.min/max carry a NaN through,
        // so a wire with one is never passed over.
        final double[] boxes = new double[4 * routes.size()];
        for (int index = 0; index < routes.size(); index++) {
            final List<Segment> list = new ArrayList<>();
            final List<GridPoint> pts = routes.get(index)
                .points();
            double left = Double.POSITIVE_INFINITY, right = Double.NEGATIVE_INFINITY, top = Double.POSITIVE_INFINITY,
                bottom = Double.NEGATIVE_INFINITY;
            for (int i = 1; i < pts.size(); i++) {
                final GridPoint a = pts.get(i - 1), b = pts.get(i);
                final double dx = b.x() - a.x(), dy = b.y() - a.y();
                final Segment s = new Segment(
                    a,
                    b,
                    dx,
                    dy,
                    Math.min(a.x(), b.x()),
                    Math.max(a.x(), b.x()),
                    Math.min(a.y(), b.y()),
                    Math.max(a.y(), b.y()),
                    JsMath.hypot(dx, dy));
                list.add(s);
                left = Math.min(left, s.left);
                right = Math.max(right, s.right);
                top = Math.min(top, s.top);
                bottom = Math.max(bottom, s.bottom);
            }
            segments.add(list);
            boxes[4 * index] = left;
            boxes[4 * index + 1] = right;
            boxes[4 * index + 2] = top;
            boxes[4 * index + 3] = bottom;
        }
        final double[] weights = new double[routes.size()];
        for (int i = 0; i < routes.size(); i++) weights[i] = wireWeight(
            routes.get(i)
                .width());
        double length = 0, weightedLength = 0;
        for (int index = 0; index < segments.size(); index++) {
            for (final Segment s : segments.get(index)) {
                final double run = s.length;
                length += run;
                weightedLength += run * weights[index];
            }
        }
        int bends45 = 0, bends90 = 0, bendsSharp = 0;
        double weightedBends45 = 0, weightedBends90 = 0, weightedBendsSharp = 0;
        for (int index = 0; index < routes.size(); index++) {
            final int[] bends = countBends(
                routes.get(index)
                    .points());
            bends45 += bends[0];
            bends90 += bends[1];
            bendsSharp += bends[2];
            weightedBends45 += bends[0] * weights[index];
            weightedBends90 += bends[1] * weights[index];
            weightedBendsSharp += bends[2] * weights[index];
        }
        double weightedCrossings = 0;
        final List<Crossing> events = new ArrayList<>();
        for (int i = 0; i < routes.size(); i++) for (int j = i + 1; j < routes.size(); j++) {
            // The segments' own test below, on the two wires' boxes: every segment box lies inside its wire's.
            if (boxes[4 * i + 1] < boxes[4 * j] - EPS || boxes[4 * j + 1] < boxes[4 * i] - EPS
                || boxes[4 * i + 3] < boxes[4 * j + 2] - EPS
                || boxes[4 * j + 3] < boxes[4 * i + 2] - EPS) continue;
            final List<GridPoint> contacts = new ArrayList<>();
            for (final Segment a : segments.get(i)) for (final Segment b : segments.get(j)) {
                if (a.right < b.left - EPS || b.right < a.left - EPS
                    || a.bottom < b.top - EPS
                    || b.bottom < a.top - EPS) continue;
                final double den = a.dx * b.dy - a.dy * b.dx;
                if (Math.abs(den) < EPS * a.length * b.length || den == 0) continue;
                final double wx = b.a.x() - a.a.x(), wy = b.a.y() - a.a.y();
                final double t = (wx * b.dy - wy * b.dx) / den, u = (wx * a.dy - wy * a.dx) / den;
                if (t < -EPS || t > 1 + EPS || u < -EPS || u > 1 + EPS) continue;
                final GridPoint p = new GridPoint(a.a.x() + t * a.dx, a.a.y() + t * a.dy);
                boolean seen = false;
                for (final GridPoint q : contacts) {
                    if (JsMath.hypot(p.x() - q.x(), p.y() - q.y()) < EPS) {
                        seen = true;
                        break;
                    }
                }
                if (!seen) contacts.add(p);
            }
            for (final GridPoint p : contacts) {
                if (pathsCrossAt(routes.get(i), routes.get(j), p)) {
                    events.add(
                        new Crossing(
                            p,
                            routes.get(i)
                                .edgeId(),
                            routes.get(j)
                                .edgeId()));
                    weightedCrossings += Math.max(weights[i], weights[j]);
                }
            }
        }
        return new Measure(
            events.size(),
            length,
            bends45,
            bends90,
            bendsSharp,
            events,
            weightedCrossings,
            weightedLength,
            weightedBends45,
            weightedBends90,
            weightedBendsSharp);
    }

    /**
     * {@code routePoints}: every wire's length plus its bends at the router's prices (a sharper bend as three 90s),
     * plus every crossing, each counted the wire's weight times over. One number, lower is better.
     */
    public static double points(final Measure m, final double turn45, final double turn90, final double crossing) {
        return m.weightedLength() + m.weightedBends45() * turn45
            + (m.weightedBends90() + 3 * m.weightedBendsSharp()) * turn90
            + m.weightedCrossings() * crossing;
    }

    /** {@link #points(Measure, double, double, double)} at a tuning's prices. */
    public static double points(final Measure m, final RouterTuning t) {
        return points(m, t.turn45(), t.turn90(), t.crossing());
    }
}
