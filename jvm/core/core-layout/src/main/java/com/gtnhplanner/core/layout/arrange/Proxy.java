package com.gtnhplanner.core.layout.arrange;

import com.gtnhplanner.core.layout.GridEdgeRouter;
import com.gtnhplanner.core.layout.JsMath;
import com.gtnhplanner.core.layout.RouterTuning;

/**
 * The proxy route and its prices, the path helpers of the website's {@code board-arrange-optimize.ts} (exported there
 * for board-arrange-free.ts too). Every wire gets a PROXY route shaped like the router's (leave at the rim point facing
 * the far card, run straight for the clean cells, shortest octilinear way with one centred diagonal, land straight),
 * scored in the router's own points.
 *
 * <p>
 * A path is its corners as one array, x then y: {@code [x0, y0, x1, y1, ...]}.
 */
final class Proxy {

    private Proxy() {}

    static final double BOARD_GRID = GridEdgeRouter.BOARD_GRID;
    static final double CLEAN = 2 * BOARD_GRID;

    /** A box in board px ({@code Rect}). */
    static final class Rect {

        double left, top, right, bottom;

        Rect(final double left, final double top, final double right, final double bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    /**
     * The rim point of {@code rect} facing {@code other}, and the outward normal there: {x, y, nx, ny}. The side is
     * chosen by the gap between the two rectangles, not by the other card's centre (a drawer beside a tall tower faces
     * it across the gap even though the tower's centre lies far below). With no clear gap axis it falls back to the
     * centre.
     */
    private static double[] exitPoint(final Rect rect, final Rect other) {
        final double toX = (other.left + other.right) / 2, toY = (other.top + other.bottom) / 2;
        final double clampX = Math.min(Math.max(toX, rect.left + BOARD_GRID), rect.right - BOARD_GRID);
        final double clampY = Math.min(Math.max(toY, rect.top + BOARD_GRID), rect.bottom - BOARD_GRID);
        final double gapX = Math.max(Math.max(other.left - rect.right, rect.left - other.right), 0);
        final double gapY = Math.max(Math.max(other.top - rect.bottom, rect.top - other.bottom), 0);
        final boolean vertical = gapY > gapX;
        if (vertical || (gapX == 0 && gapY == 0 && (toY < rect.top || toY > rect.bottom))) {
            if (toY < rect.top) {
                return new double[] { clampX, rect.top, 0, -1 };
            }
            return new double[] { clampX, rect.bottom, 0, 1 };
        }
        if (toX < rect.left) {
            return new double[] { rect.left, clampY, -1, 0 };
        }
        return new double[] { rect.right, clampY, 1, 0 };
    }

    /** The proxy route ({@code proxyPath}): clean exit, shortest octilinear way, clean landing. */
    static double[] proxyPath(final Rect source, final Rect target) {
        // Docking is free, so a wire leaves at the rim point facing its far end, never at a fixed port row.
        final double[] exit = exitPoint(source, target);
        final double[] entry = exitPoint(target, source);
        // Facing sides whose dock ranges overlap line up on one row (or column): the router's straight shot.
        if (exit[2] != 0 && exit[2] == -entry[2]) {
            final double lo = Math.max(source.top, target.top) + BOARD_GRID;
            final double hi = Math.min(source.bottom, target.bottom) - BOARD_GRID;
            if (lo <= hi) {
                final double y = Math.min(Math.max(entry[1], lo), hi);
                exit[1] = y;
                entry[1] = y;
            }
        } else if (exit[3] != 0 && exit[3] == -entry[3]) {
            final double lo = Math.max(source.left, target.left) + BOARD_GRID;
            final double hi = Math.min(source.right, target.right) - BOARD_GRID;
            if (lo <= hi) {
                final double x = Math.min(Math.max(entry[0], lo), hi);
                exit[0] = x;
                entry[0] = x;
            }
        }
        // The clean runs reach out along the normals, but only as far as half the room ahead: two cards a couple of
        // cells apart meet in the middle of the gap in a straight line, the way the router draws them.
        final double aheadOfExit = (entry[0] - exit[0]) * exit[2] + (entry[1] - exit[1]) * exit[3];
        final double aheadOfEntry = (exit[0] - entry[0]) * entry[2] + (exit[1] - entry[1]) * entry[3];
        final double stubA = Math.max(0, Math.min(CLEAN, aheadOfExit / 2));
        final double stubB = Math.max(0, Math.min(CLEAN, aheadOfEntry / 2));
        final double ax = exit[0] + exit[2] * stubA, ay = exit[1] + exit[3] * stubA;
        final double bx = entry[0] + entry[2] * stubB, by = entry[1] + entry[3] * stubB;
        final double dx = bx - ax;
        final double dy = by - ay;
        final double adx = Math.abs(dx);
        final double ady = Math.abs(dy);
        if (adx > ady) {
            final double straight = (adx - ady) / 2;
            final double sx = Math.signum(dx);
            final double m1x = ax + sx * straight, m1y = ay;
            final double m2x = m1x + sx * ady, m2y = by;
            return new double[] { exit[0], exit[1], ax, ay, m1x, m1y, m2x, m2y, bx, by, entry[0], entry[1] };
        } else if (ady > adx) {
            final double straight = (ady - adx) / 2;
            final double sy = Math.signum(dy);
            final double m1x = ax, m1y = ay + sy * straight;
            final double m2x = bx, m2y = m1y + sy * adx;
            return new double[] { exit[0], exit[1], ax, ay, m1x, m1y, m2x, m2y, bx, by, entry[0], entry[1] };
        }
        return new double[] { exit[0], exit[1], ax, ay, bx, by, entry[0], entry[1] };
    }

    static double pathLength(final double[] path) {
        double length = 0;
        for (int i = 2; i < path.length; i += 2) {
            length += JsMath.hypot(path[i] - path[i - 2], path[i + 1] - path[i - 1]);
        }
        return length;
    }

    /** Bends along a path at the router's prices: 45, 90, sharper as three 90s. */
    static double pathBends(final double[] path, final RouterTuning prices) {
        double bends = 0;
        for (int i = 4; i < path.length; i += 2) {
            final double ax = path[i - 2] - path[i - 4];
            final double ay = path[i - 1] - path[i - 3];
            final double bx = path[i] - path[i - 2];
            final double by = path[i + 1] - path[i - 1];
            final double la = JsMath.hypot(ax, ay);
            final double lb = JsMath.hypot(bx, by);
            if (la < 1e-6 || lb < 1e-6) continue;
            final double cos = (ax * bx + ay * by) / (la * lb);
            if (cos > 0.99) continue;
            bends += cos > 0.5 ? prices.turn45() : cos > -0.5 ? prices.turn90() : 3 * prices.turn90();
        }
        return bends;
    }

    private static double cross(final double ox, final double oy, final double ax, final double ay, final double bx,
        final double by) {
        return (ax - ox) * (by - oy) - (ay - oy) * (bx - ox);
    }

    /** Proper crossings between two paths (touching ends do not count). */
    static int pathCrossings(final double[] p, final double[] q) {
        int count = 0;
        for (int i = 2; i < p.length; i += 2) {
            final double ax = p[i - 2], ay = p[i - 1], bx = p[i], by = p[i + 1];
            if (ax == bx && ay == by) continue;
            for (int j = 2; j < q.length; j += 2) {
                final double cx = q[j - 2], cy = q[j - 1], dx = q[j], dy = q[j + 1];
                if (cx == dx && cy == dy) continue;
                final double d1 = cross(ax, ay, bx, by, cx, cy);
                final double d2 = cross(ax, ay, bx, by, dx, dy);
                final double d3 = cross(cx, cy, dx, dy, ax, ay);
                final double d4 = cross(cx, cy, dx, dy, bx, by);
                final double eps = 0.5;
                if (((d1 > eps && d2 < -eps) || (d1 < -eps && d2 > eps))
                    && ((d3 > eps && d4 < -eps) || (d3 < -eps && d4 > eps))) {
                    count += 1;
                }
            }
        }
        return count;
    }

    /** Does the segment enter the open rectangle? (Liang-Barsky, inlined: it runs millions of times a search.) */
    private static boolean segmentEnters(final double ax, final double ay, final double bx, final double by,
        final double left, final double top, final double right, final double bottom) {
        final double dx = bx - ax;
        final double dy = by - ay;
        double t0 = 0;
        double t1 = 1;
        // clip(-dx, ax - left)
        double p = -dx;
        double q = ax - left;
        if (Math.abs(p) < 1e-9) {
            if (!(q > 0)) return false;
        } else {
            final double r = q / p;
            if (p < 0) {
                if (r > t1) return false;
                if (r > t0) t0 = r;
            } else {
                if (r < t0) return false;
                if (r < t1) t1 = r;
            }
        }
        p = dx;
        q = right - ax;
        if (Math.abs(p) < 1e-9) {
            if (!(q > 0)) return false;
        } else {
            final double r = q / p;
            if (p < 0) {
                if (r > t1) return false;
                if (r > t0) t0 = r;
            } else {
                if (r < t0) return false;
                if (r < t1) t1 = r;
            }
        }
        p = -dy;
        q = ay - top;
        if (Math.abs(p) < 1e-9) {
            if (!(q > 0)) return false;
        } else {
            final double r = q / p;
            if (p < 0) {
                if (r > t1) return false;
                if (r > t0) t0 = r;
            } else {
                if (r < t0) return false;
                if (r < t1) t1 = r;
            }
        }
        p = dy;
        q = bottom - ay;
        if (Math.abs(p) < 1e-9) {
            if (!(q > 0)) return false;
        } else {
            final double r = q / p;
            if (p < 0) {
                if (r > t1) return false;
                if (r > t0) t0 = r;
            } else {
                if (r < t0) return false;
                if (r < t1) t1 = r;
            }
        }
        return t1 - t0 > 1e-6;
    }

    /**
     * Estimated detour cost for every card (other than the path's own two) the proxy path runs through: two 90-degree
     * corners plus half the card's shorter side plus the margins, per card.
     */
    static double pathBlocked(final double[] path, final Rect[] rects, final int skipA, final int skipB,
        final RouterTuning prices) {
        double cost = 0;
        // The path's own box: a card whose inflated box misses it cannot be run through.
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < path.length; i += 2) {
            final double x = path[i], y = path[i + 1];
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }
        for (int r = 0; r < rects.length; r += 1) {
            if (r == skipA || r == skipB) continue;
            final Rect rect = rects[r];
            final double left = rect.left - BOARD_GRID;
            final double top = rect.top - BOARD_GRID;
            final double right = rect.right + BOARD_GRID;
            final double bottom = rect.bottom + BOARD_GRID;
            if (maxX <= left || minX >= right || maxY <= top || minY >= bottom) continue;
            for (int i = 2; i < path.length; i += 2) {
                if (segmentEnters(path[i - 2], path[i - 1], path[i], path[i + 1], left, top, right, bottom)) {
                    final double shorter = Math.min(rect.right - rect.left, rect.bottom - rect.top);
                    cost += 2 * prices.turn90() + shorter / 2 + 2 * BOARD_GRID;
                    break;
                }
            }
        }
        return cost;
    }

    /** A tiny deterministic RNG (mulberry32), bit for bit the website's. */
    static final class Rng {

        private int state;

        Rng(final int seed) {
            this.state = seed;
        }

        double next() {
            state = state + 0x6d2b79f5;
            int t = (state ^ (state >>> 15)) * (1 | state);
            t = (t + (t ^ (t >>> 7)) * (61 | t)) ^ t;
            return ((t ^ (t >>> 14)) & 0xffffffffL) / 4294967296.0;
        }
    }

    /** FNV-1a over the ids' UTF-16 units, mixed per id ({@code hashIds}); the int is the website's {@code >>> 0}. */
    static int hashIds(final Iterable<String> ids) {
        int hash = (int) 2166136261L;
        for (final String id : ids) {
            for (int i = 0; i < id.length(); i += 1) {
                hash ^= id.charAt(i);
                hash = hash * 16777619;
            }
            hash ^= 0x9e3779b9;
        }
        return hash;
    }
}
