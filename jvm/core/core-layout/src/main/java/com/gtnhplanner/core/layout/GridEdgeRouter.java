package com.gtnhplanner.core.layout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.ExactMath;
import com.gtnhplanner.core.base.Float32Ops;

/**
 * The grid edge router, the website's {@code grid-edge-router.ts} written once in Java for both sides. It is a
 * translation, not a redesign: the same 20 px grid, the same costs and the same tie-breaks, so the website's own
 * router tests are its specification (conformance/layout).
 *
 * <p>
 * Wires live on the cards' 20 px grid: every straight run travels along a grid line (horizontal, vertical, or a 45
 * degree diagonal through the vertices) and stays at least one cell from every card; only the port stub, the last hop
 * from the card's margin to the port, comes closer. A grid line is a lane with a usable width (16 px orthogonal, the
 * diagonal's from the tuning); wires are fractions of a lane, and wires that fit side by side share it, packed round
 * the centre with a 2 px gap. A full lane costs heavily, so overlap is never chosen while a separated path exists.
 *
 * <p>
 * The wires are solved together: docks are planned per card first ({@code planDocks}), every crossing of an earlier
 * wire costs, and wires that still cross are ripped up and rerouted with contested spots dearer each round. Everything
 * is a pure function of its inputs: board geometry in, polylines out, the same at every zoom.
 *
 * <p>
 * One router instance keeps its search pools between solves and is not for two threads at once; a host gives each
 * thread its own. Search work is counted in doubles, as the website counts it in numbers.
 */
public final class GridEdgeRouter {

    /** The board grid, px (tokens.json geometry.grid). */
    public static final int BOARD_GRID = Tokens.Grid.BOARD_GRID;
    /** One cell of clearance between any wire run and any card. */
    public static final int WIRE_NODE_MARGIN = Tokens.Wire.WIRE_NODE_MARGIN;
    /** Usable stroke px inside one orthogonal lane. */
    public static final double LANE_CAPACITY = Tokens.Wire.LANE_CAPACITY;
    /** Breathing room between two wires sharing a lane. */
    public static final double LANE_GAP = Tokens.Wire.LANE_GAP;
    /** The widths wires draw at, as lane fractions. */
    private static final double[] LANE_FRACTIONS = Tokens.Wire.LANE_FRACTIONS;
    /** Cost per px of distance from a side's centre when choosing a dock. */
    public static final double DOCK_CENTER_BIAS = Tokens.Wire.DOCK_CENTER_BIAS;

    /** A* gives up after this many pops, or two per state, whichever is larger. */
    private static final int MIN_ASTAR_POPS = 40_000;
    private static final int ASTAR_POPS_PER_STATE = 2;
    /** The search window grows by this when a wire cannot route inside it. */
    private static final double WINDOW_GROWTH = 2.5;
    /** Heuristic weight of the wide rung. 1: plain A*. */
    private static final double BOARD_RUNG_WEIGHT = 1;
    /** Search work the solve may spend, in A* pops per wire on the board. */
    private static final double NEGOTIATION_POPS_PER_WIRE = 40_000;

    /** The eight directions, clockwise from east (screen y points down). */
    private static final int[] DIR_DX = { 1, 1, 0, -1, -1, -1, 0, 1 };
    private static final int[] DIR_DY = { 0, 1, 1, 1, 0, -1, -1, -1 };
    /** Axis of each direction: 0 h, 1 v, 2 falling diagonal (x-y), 3 rising (x+y). */
    private static final int[] DIR_AXIS = { 0, 2, 1, 3, 0, 2, 1, 3 };

    /** A step is blocked: it enters a card's margin or cuts a corner. */
    private static final double STEP_BLOCKED = -1;

    /** A clock for the stats' times, in ms. */
    public interface Clock {

        double nowMs();
    }

    private final Clock clock;

    /** A router whose stats carry no times (they read 0). */
    public GridEdgeRouter() {
        this(() -> 0);
    }

    public GridEdgeRouter(final Clock clock) {
        this.clock = clock;
    }

    /** Normalized flow heat (0..1) to stroke width: the menu of lane fractions by index. */
    public static double laneWidthForHeat(final double heat) {
        final double clamped = Math.min(Math.max(heat, 0), 1);
        final double fraction = LANE_FRACTIONS[(int) JsMath.round(clamped * (LANE_FRACTIONS.length - 1))];
        return JsMath.round(fraction * LANE_CAPACITY * 100) / 100;
    }

    // region Tuning, per solve

    private RouterTuning t = RouterTuning.DEFAULT;
    private final double[] dirLength = new double[8];
    /** Cost of turning from one direction to another; NaN forbids the turn. */
    private final double[] turnCosts = new double[64];
    private double diagonalLength = BOARD_GRID * Math.sqrt(2);
    private double workPops, workPriced, workSearches;

    private void applyTuning(final RouterTuning tuning) {
        t = tuning;
        diagonalLength = BOARD_GRID * tuning.diagonalLength();
        for (int dir = 0; dir < 8; dir += 1) dirLength[dir] = (dir & 1) == 1 ? diagonalLength : BOARD_GRID;
        for (int from = 0; from < 8; from += 1) {
            for (int to = 0; to < 8; to += 1) {
                final int diff = (to - from + 8) % 8;
                turnCosts[from * 8 + to] = diff == 0 ? 0
                    : diff == 1 || diff == 7 ? tuning.turn45()
                        : diff == 2 || diff == 6 ? tuning.turn90() : diff == 4 ? tuning.reverse() : Double.NaN;
            }
        }
    }

    // endregion

    // region Pooled search state

    // What is read together sits together (the website keeps one typed array per field): a state's stamp and g share
    // one double[] two to a state, its came-from and start one int[]; a heap entry's f and g one double[], its state
    // and sequence one int[]; a step's stamp and cost one double[]. Stamps are whole numbers below 2^31, exact as
    // doubles. Only memory traffic changes; every number read is the one the website's arrays hold.
    private int searchPoolCapacity, searchPoolGeneration;
    /** Per state: stamp, g. */
    private double[] searchPoolStampG = new double[0];
    /** Per state: came from, start index. */
    private int[] searchPoolLinks = new int[0];
    /** Per heap entry: f, g. */
    private double[] searchHeapFG = new double[0];
    /** Per heap entry: state, sequence. */
    private int[] searchHeapStateSeq = new int[0];

    private int acquireSearchGeneration(final int stateCount) {
        if (stateCount > searchPoolCapacity) {
            searchPoolCapacity = Math.max(Math.max(stateCount, searchPoolCapacity * 2), 4096);
            searchPoolStampG = new double[2 * searchPoolCapacity];
            searchPoolLinks = new int[2 * searchPoolCapacity];
            searchPoolGeneration = 0;
        }
        searchPoolGeneration += 1;
        if (searchPoolGeneration >= 0x7ffffffe) {
            Arrays.fill(searchPoolStampG, 0);
            searchPoolGeneration = 1;
        }
        return searchPoolGeneration;
    }

    private void ensureSearchHeapCapacity(final int size) {
        if (2 * size <= searchHeapFG.length) return;
        final int capacity = Math.max(Math.max(size, searchHeapFG.length), 1024);
        searchHeapFG = Arrays.copyOf(searchHeapFG, 2 * capacity);
        searchHeapStateSeq = Arrays.copyOf(searchHeapStateSeq, 2 * capacity);
    }

    private int stepPoolCapacity, stepPoolGeneration;
    /** Per step: stamp, cost. */
    private double[] stepPool = new double[0];

    private int acquireStepGeneration(final int stepCount) {
        if (stepCount > stepPoolCapacity) {
            stepPoolCapacity = Math.max(Math.max(stepCount, stepPoolCapacity * 2), 4096);
            stepPool = new double[2 * stepPoolCapacity];
            stepPoolGeneration = 0;
        }
        stepPoolGeneration += 1;
        if (stepPoolGeneration >= 0x7ffffffe) {
            Arrays.fill(stepPool, 0);
            stepPoolGeneration = 1;
        }
        return stepPoolGeneration;
    }

    // Two vertex-keyed maps of the search, dense with stamps instead of hashed by boxed vertex (the website's are
    // Maps of numbers, which box nothing): a leg's goals by vertex, and a window's clean zone (the start variants
    // whose clean run passes each vertex). Each holds an index into a list the search or window owns.
    private int goalPoolCapacity, goalPoolGeneration;
    private int[] goalPoolStamp = new int[0], goalPoolIndex = new int[0];

    private int acquireGoalGeneration(final int vertexCount) {
        if (vertexCount > goalPoolCapacity) {
            goalPoolCapacity = Math.max(Math.max(vertexCount, goalPoolCapacity * 2), 4096);
            goalPoolStamp = new int[goalPoolCapacity];
            goalPoolIndex = new int[goalPoolCapacity];
            goalPoolGeneration = 0;
        }
        goalPoolGeneration += 1;
        if (goalPoolGeneration >= 0x7ffffffe) {
            Arrays.fill(goalPoolStamp, 0);
            goalPoolGeneration = 1;
        }
        return goalPoolGeneration;
    }

    private int zonePoolCapacity, zonePoolGeneration;
    private int[] zonePoolStamp = new int[0], zonePoolIndex = new int[0];

    private int acquireZoneGeneration(final int vertexCount) {
        if (vertexCount > zonePoolCapacity) {
            zonePoolCapacity = Math.max(Math.max(vertexCount, zonePoolCapacity * 2), 4096);
            zonePoolStamp = new int[zonePoolCapacity];
            zonePoolIndex = new int[zonePoolCapacity];
            zonePoolGeneration = 0;
        }
        zonePoolGeneration += 1;
        if (zonePoolGeneration >= 0x7ffffffe) {
            Arrays.fill(zonePoolStamp, 0);
            zonePoolGeneration = 1;
        }
        return zonePoolGeneration;
    }

    private int floodPoolCapacity, floodPoolGeneration;
    private int[] floodPoolStamp = new int[0], floodPoolQueue = new int[0];

    private int acquireFloodGeneration(final int vertexCount) {
        if (vertexCount > floodPoolCapacity) {
            floodPoolCapacity = Math.max(Math.max(vertexCount, floodPoolCapacity * 2), 4096);
            floodPoolStamp = new int[floodPoolCapacity];
            floodPoolQueue = new int[floodPoolCapacity];
            floodPoolGeneration = 0;
        }
        floodPoolGeneration += 1;
        if (floodPoolGeneration >= 0x7ffffffe) {
            Arrays.fill(floodPoolStamp, 0);
            floodPoolGeneration = 1;
        }
        return floodPoolGeneration;
    }

    private int cellPoolCapacity;
    private byte[] cellBlocked = new byte[0], cellExempt = new byte[0], cellHome = new byte[0];

    private void ensureCellPool(final int vertexCount) {
        if (vertexCount > cellPoolCapacity) {
            cellPoolCapacity = Math.max(Math.max(vertexCount, cellPoolCapacity * 2), 4096);
            cellBlocked = new byte[cellPoolCapacity];
            cellExempt = new byte[cellPoolCapacity];
            cellHome = new byte[cellPoolCapacity];
        }
    }

    /** routeWithinWindow's answer when no path exists and no larger window could hold one. */
    private static final RouteFound SEALED = new RouteFound(null, null, List.of(), 0);

    // endregion

    // region Occupancy

    /** One wire's slice of a lane, in px across it. Compared by identity, as the website's objects are. */
    private static final class LaneClaim {

        final double lo, hi;

        LaneClaim(final double lo, final double hi) {
            this.lo = lo;
            this.hi = hi;
        }
    }

    private static final long KEY_OFFSET = 1 << 20;
    private static final long KEY_SPAN = 1 << 21;
    /** The packed vertex word: four 6-bit passer counts by axis, then 4 bits of history. */
    private static final int PASSER_BITS = 6;
    private static final int PASSER_MASK = (1 << PASSER_BITS) - 1;
    private static final int HISTORY_SHIFT = 4 * PASSER_BITS;
    private static final int HISTORY_MASK = 15;

    /** A run's axis and its first and last vertex, in cells. */
    private record ClaimedRun(int axis, int x0, int y0, int x1, int y1) {}

    /** The board's extent in cells: what the dense occupancy arrays cover. */
    private record CellBounds(int x0, int y0, int width, int height) {}

    /** The direction a passer axis runs in, for the corner test. */
    private static final int[] CORNER_AXIS_DIRECTION = { 0, 2, 1, 3 };

    private record ClaimRecord(long key, int cell, int axis, LaneClaim claim) {}

    private record PasserRecord(boolean centre, int axis, int cell) {}

    private record Corner(String edgeId, int a, int b) {}

    /**
     * Who already rides which stretch of which line. Lane claims are tracked per vertex of a line, because two wires
     * can share a line for part of its length; passers are tracked per vertex (and per cell centre for the
     * diagonals) so a step can ask how many wires it would cross. Everything a wire holds is remembered against it so
     * it can be ripped up.
     */
    private static final class LaneOccupancy {

        // The lane key is up to 45 bits (axis, line and place along it, each offset into 21 bits): a long here, a
        // double on the website. It is the one long on the router's hot path; a dense int index (axis by cell) would
        // do inside the board's extent, but not for a pinned route that runs outside it.
        private final Map<Long, List<LaneClaim>> cells = new HashMap<>();
        private final float[] totals;
        private final int[] vertices, centres;
        private final int cellCount;
        private final CellBounds bounds;
        private final Map<String, List<ClaimRecord>> claimsByEdge = new HashMap<>();
        private final Map<String, List<PasserRecord>> passersByEdge = new HashMap<>();
        private final Map<String, List<ClaimedRun>> runsByEdge = new HashMap<>();
        private final Map<Integer, List<Corner>> corners = new HashMap<>();
        private final Map<String, List<Integer>> cornersByEdge = new HashMap<>();
        private final int[] cornerCount;

        LaneOccupancy(final CellBounds bounds) {
            this.bounds = bounds;
            this.cellCount = bounds.width * bounds.height;
            this.totals = new float[4 * cellCount];
            this.vertices = new int[cellCount];
            this.centres = new int[cellCount];
            this.cornerCount = new int[cellCount];
        }

        void markCorner(final int x, final int y, final int incoming, final int outgoing, final String edgeId) {
            if (incoming == outgoing) return;
            final int cell = cellOf(x, y);
            corners.computeIfAbsent(cell, k -> new ArrayList<>())
                .add(new Corner(edgeId, (incoming + 4) % 8, outgoing));
            if (cell >= 0) cornerCount[cell] += 1;
            cornersByEdge.computeIfAbsent(edgeId, k -> new ArrayList<>())
                .add(cell);
        }

        /** A wire may cross a bend too: compare the two pairs of outgoing rays. */
        int cornerCrossings(final int x, final int y, final int incoming, final int outgoing) {
            final int cell = cellOf(x, y);
            final int word = cell < 0 ? 0 : vertices[cell];
            final int bends;
            if (cell < 0) {
                final List<Corner> list = corners.get(cell);
                bends = list == null ? 0 : list.size();
            } else bends = cornerCount[cell];
            if (word == 0 && bends == 0) return 0;
            final int a = (incoming + 4) % 8, b = outgoing;
            if (a == b) return 0;
            final int span = (b - a + 8) % 8;
            int count = 0;
            if (bends > 0) {
                final List<Corner> list = corners.get(cell);
                if (list != null) for (final Corner c : list) {
                    if (a == c.a || a == c.b || b == c.a || b == c.b) continue;
                    if ((c.a - a + 8) % 8 < span != (c.b - a + 8) % 8 < span) count++;
                }
            }
            if (word != 0) {
                for (int axis = 0; axis < 4; axis++) {
                    final int c = CORNER_AXIS_DIRECTION[axis], d = c + 4;
                    if (a == c || a == d || b == c || b == d) continue;
                    if ((c - a + 8) % 8 < span != (d - a + 8) % 8 < span)
                        count += (word >>> (axis * PASSER_BITS)) & PASSER_MASK;
                }
            }
            return count;
        }

        /**
         * Whether {@link #cornerCrossings} is 0 at this vertex whatever the turn: no passer and no bend there. The
         * search asks once a vertex instead of once a direction.
         */
        boolean noCorner(final int x, final int y) {
            final int cell = cellOf(x, y);
            if (cell < 0) {
                final List<Corner> list = corners.get(cell);
                return list == null || list.isEmpty();
            }
            return vertices[cell] == 0 && cornerCount[cell] == 0;
        }

        /** {@link #usedWidth} of a zero-length step at a vertex (dense index, -1 outside), greater than 0. */
        boolean occupiedAt(final int axis, final int cell) {
            return cell >= 0 && totals[axis * cellCount + cell] > 0;
        }

        int centreCrossings(final int axis, final int x0, final int y0, final int x1, final int y1) {
            if (axis < 2) return 0;
            final int cell = cellOf(Math.min(x0, x1), Math.min(y0, y1));
            return cell < 0 ? 0 : otherPassers(centres[cell], axis, true);
        }

        /** Dense index of a vertex, or -1 outside the board's extent. */
        int cellOf(final int x, final int y) {
            final int xi = x - bounds.x0;
            final int yi = y - bounds.y0;
            if (xi < 0 || yi < 0 || xi >= bounds.width || yi >= bounds.height) return -1;
            return xi * bounds.height + yi;
        }

        /** Cells are keyed by axis, line and position along the line, in cells. */
        private static long laneKey(final int axis, final int x, final int y) {
            final int line = switch (axis) {
                case 0 -> y;
                case 1 -> x;
                case 2 -> x - y;
                default -> x + y;
            };
            final int along = axis == 1 ? y : x;
            return (axis * KEY_SPAN + line + KEY_OFFSET) * KEY_SPAN + (along + KEY_OFFSET);
        }

        /** Total stroke already claimed on the busier of a step's two vertices. */
        double usedWidth(final int axis, final int x0, final int y0, final int x1, final int y1) {
            final int ca = cellOf(x0, y0);
            final int cb = cellOf(x1, y1);
            final double a = ca < 0 ? 0 : totals[axis * cellCount + ca];
            final double b = cb < 0 ? 0 : totals[axis * cellCount + cb];
            return a > b ? a : b;
        }

        List<LaneClaim> claimsAlong(final ClaimedRun run) {
            final List<LaneClaim> merged = new ArrayList<>();
            final int dx = Integer.signum(run.x1 - run.x0);
            final int dy = Integer.signum(run.y1 - run.y0);
            final int steps = Math.max(Math.abs(run.x1 - run.x0), Math.abs(run.y1 - run.y0));
            for (int i = 0; i <= steps; i += 1) {
                final List<LaneClaim> claims = cells.get(laneKey(run.axis, run.x0 + dx * i, run.y0 + dy * i));
                if (claims != null) merged.addAll(claims);
            }
            return merged;
        }

        void claim(final ClaimedRun run, final LaneClaim claimInterval, final String edgeId) {
            final double width = claimInterval.hi - claimInterval.lo;
            final List<ClaimRecord> records = claimsByEdge.computeIfAbsent(edgeId, k -> new ArrayList<>());
            final int dx = Integer.signum(run.x1 - run.x0);
            final int dy = Integer.signum(run.y1 - run.y0);
            final int steps = Math.max(Math.abs(run.x1 - run.x0), Math.abs(run.y1 - run.y0));
            for (int i = 0; i <= steps; i += 1) {
                final int x = run.x0 + dx * i;
                final int y = run.y0 + dy * i;
                final long key = laneKey(run.axis, x, y);
                cells.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(claimInterval);
                final int cell = cellOf(x, y);
                // Float32 as on the website: the sum is rounded to a float on every store.
                if (cell >= 0) {
                    final int index = run.axis * cellCount + cell;
                    totals[index] = Float32Ops.of(totals[index] + width);
                }
                records.add(new ClaimRecord(key, cell, run.axis, claimInterval));
            }
        }

        /**
         * What a step would cross: passers of every other axis through its far vertex, and for a diagonal step the
         * other diagonal through the cell centre it cuts. {@code weighted} multiplies each by one plus the spot's
         * negotiation history.
         */
        int stepCrossings(final int axis, final int x0, final int y0, final int x1, final int y1,
            final boolean weighted) {
            final int far = cellOf(x1, y1);
            int count = far < 0 ? 0 : otherPassers(vertices[far], axis, weighted);
            if (axis >= 2 && (x0 != x1 || y0 != y1)) {
                final int centre = cellOf(Math.min(x0, x1), Math.min(y0, y1));
                if (centre >= 0) count += otherPassers(centres[centre], axis, weighted);
            }
            return count;
        }

        private static int otherPassers(final int word, final int axis, final boolean weighted) {
            if (word == 0) return 0;
            final int passers = (word & PASSER_MASK) + ((word >>> PASSER_BITS) & PASSER_MASK)
                + ((word >>> (2 * PASSER_BITS)) & PASSER_MASK)
                + ((word >>> (3 * PASSER_BITS)) & PASSER_MASK)
                - ((word >>> (axis * PASSER_BITS)) & PASSER_MASK);
            if (passers == 0) return 0;
            return weighted ? passers * (1 + ((word >>> HISTORY_SHIFT) & HISTORY_MASK)) : passers;
        }

        /** Ends a negotiation round: every spot wires cross at now costs more. */
        void escalate() {
            bump(vertices);
            bump(centres);
        }

        private static void bump(final int[] words) {
            for (int i = 0; i < words.length; i += 1) {
                final int word = words[i];
                if (word == 0) continue;
                int axes = 0;
                for (int axis = 0; axis < 4; axis += 1) {
                    if (((word >>> (axis * PASSER_BITS)) & PASSER_MASK) != 0) axes += 1;
                }
                if (axes >= 2 && ((word >>> HISTORY_SHIFT) & HISTORY_MASK) < HISTORY_MASK)
                    words[i] = word + (1 << HISTORY_SHIFT);
            }
        }

        private static void addPasser(final int[] words, final int cell, final int axis, final int delta) {
            if (cell < 0) return;
            final int shift = axis * PASSER_BITS;
            final int word = words[cell];
            final int current = (word >>> shift) & PASSER_MASK;
            final int next = Math.min(PASSER_MASK, Math.max(0, current + delta));
            // History outlives the passers.
            words[cell] = (word & ~(PASSER_MASK << shift)) | (next << shift);
        }

        private void record(final String edgeId, final boolean centre, final int axis, final int cell) {
            passersByEdge.computeIfAbsent(edgeId, k -> new ArrayList<>())
                .add(new PasserRecord(centre, axis, cell));
        }

        /** Records a run as passed through: its interior vertices, and for a diagonal every cell centre it cuts. */
        void markRun(final ClaimedRun run, final String edgeId) {
            runsByEdge.computeIfAbsent(edgeId, k -> new ArrayList<>())
                .add(run);
            final int dx = Integer.signum(run.x1 - run.x0);
            final int dy = Integer.signum(run.y1 - run.y0);
            final int steps = Math.max(Math.abs(run.x1 - run.x0), Math.abs(run.y1 - run.y0));
            for (int i = 1; i < steps; i += 1) {
                final int cell = cellOf(run.x0 + dx * i, run.y0 + dy * i);
                addPasser(vertices, cell, run.axis, 1);
                record(edgeId, false, run.axis, cell);
            }
            if (run.axis >= 2) {
                for (int i = 0; i < steps; i += 1) {
                    final int x = run.x0 + dx * i;
                    final int y = run.y0 + dy * i;
                    final int cell = cellOf(Math.min(x, x + dx), Math.min(y, y + dy));
                    addPasser(centres, cell, run.axis, 1);
                    record(edgeId, true, run.axis, cell);
                }
            }
        }

        /** Records a wire's apron vertex as passed through along its stub's axis. */
        void markStub(final int axis, final int x, final int y, final String edgeId) {
            final int cell = cellOf(x, y);
            addPasser(vertices, cell, axis, 1);
            record(edgeId, false, axis, cell);
            // A zero-length run at the vertex.
            runsByEdge.computeIfAbsent(edgeId, k -> new ArrayList<>())
                .add(new ClaimedRun(axis, x, y, x, y));
        }

        /** Rips a wire up: its lane claims and passers are forgotten. */
        void release(final String edgeId) {
            final List<Integer> owned = cornersByEdge.get(edgeId);
            if (owned != null) for (final int cell : owned) {
                if (cell >= 0) cornerCount[cell] -= 1;
                final List<Corner> list = corners.get(cell);
                final List<Corner> remaining = new ArrayList<>();
                if (list != null) for (final Corner c : list) if (!c.edgeId.equals(edgeId)) remaining.add(c);
                if (!remaining.isEmpty()) corners.put(cell, remaining);
                else corners.remove(cell);
            }
            cornersByEdge.remove(edgeId);
            final List<ClaimRecord> claims = claimsByEdge.get(edgeId);
            if (claims != null) {
                for (final ClaimRecord r : claims) {
                    final List<LaneClaim> list = cells.get(r.key);
                    if (list != null) {
                        for (int at = 0; at < list.size(); at++) {
                            if (list.get(at) == r.claim) {
                                list.remove(at);
                                break;
                            }
                        }
                        if (list.isEmpty()) cells.remove(r.key);
                    }
                    if (r.cell >= 0) {
                        final int index = r.axis * cellCount + r.cell;
                        final double total = totals[index] - (r.claim.hi - r.claim.lo);
                        totals[index] = total <= 1e-6 ? 0 : Float32Ops.of(total);
                    }
                }
                claimsByEdge.remove(edgeId);
            }
            final List<PasserRecord> passers = passersByEdge.get(edgeId);
            if (passers != null) {
                for (final PasserRecord p : passers) addPasser(p.centre ? centres : vertices, p.cell, p.axis, -1);
                passersByEdge.remove(edgeId);
            }
            runsByEdge.remove(edgeId);
        }
    }

    private record Packed(LaneClaim claim, boolean overflowed) {}

    /**
     * Where in the lane a new wire of {@code width} sits: a lone wire dead centre, a joiner beside the claims there,
     * on the side of its own next turn ({@code prefer} -1/+1); a wire that fits nowhere spills past the band.
     */
    private static Packed packIntoLane(final List<LaneClaim> existing, final double width, final int prefer,
        final double capacity) {
        final double half = capacity / 2;
        final List<Double> candidates = new ArrayList<>();
        candidates.add(0.0);
        for (final LaneClaim claim : existing) {
            candidates.add(claim.hi + LANE_GAP + width / 2);
            candidates.add(claim.lo - LANE_GAP - width / 2);
        }
        JsSort.sort(candidates, (left, right) -> {
            if (prefer != 0) {
                final int c = JsMath.sign(prefer * (right - left));
                return c != 0 ? c : JsMath.sign(Math.abs(left) - Math.abs(right));
            }
            final int c = JsMath.sign(Math.abs(left) - Math.abs(right));
            return c != 0 ? c : JsMath.sign(left - right);
        });
        for (final double center : candidates) {
            final double lo = center - width / 2;
            final double hi = center + width / 2;
            if (lo < -half - 1e-6 || hi > half + 1e-6) continue;
            if (clear(existing, lo, hi)) return new Packed(new LaneClaim(lo, hi), false);
        }
        for (final double center : candidates) {
            final double lo = center - width / 2;
            final double hi = center + width / 2;
            if (clear(existing, lo, hi)) return new Packed(new LaneClaim(lo, hi), true);
        }
        return new Packed(new LaneClaim(-width / 2, width / 2), true);
    }

    private static boolean clear(final List<LaneClaim> existing, final double lo, final double hi) {
        for (final LaneClaim claim : existing) {
            if (lo < claim.hi + LANE_GAP - 1e-6 && hi > claim.lo - LANE_GAP + 1e-6) return false;
        }
        return true;
    }

    // endregion

    // region Endpoint plumbing

    private static double snapLine(final double value) {
        return JsMath.round(value / BOARD_GRID) * BOARD_GRID;
    }

    /**
     * Every dock a card offers ({@code cardRimDocks}): one per grid line crossing its border, on each side's first
     * grid line at or outside the border, never the nearest; {@code stubDepth} carries the drawn wire on to the true
     * border. Huge cards coarsen to every other line; a corner itself is never a dock.
     */
    public static List<GridEndpoint> cardRimDocks(final double rectLeft, final double rectTop, final double rectRight,
        final double rectBottom) {
        final double left = Math.floor(rectLeft / BOARD_GRID + 1e-6) * BOARD_GRID;
        final double top = Math.floor(rectTop / BOARD_GRID + 1e-6) * BOARD_GRID;
        final double right = Math.ceil(rectRight / BOARD_GRID - 1e-6) * BOARD_GRID;
        final double bottom = Math.ceil(rectBottom / BOARD_GRID - 1e-6) * BOARD_GRID;
        final double topDepth = depth(rectTop - top);
        final double bottomDepth = depth(bottom - rectBottom);
        final double leftDepth = depth(rectLeft - left);
        final double rightDepth = depth(right - rectRight);
        final double perimeterCells = (right - left + (bottom - top)) / BOARD_GRID;
        final double step = perimeterCells > 60 ? 2 * BOARD_GRID : BOARD_GRID;
        final double centerX = (left + right) / 2;
        final double centerY = (top + bottom) / 2;
        final double keepOutX = right - left >= 2 * BOARD_GRID ? BOARD_GRID : 0;
        final double keepOutY = bottom - top >= 2 * BOARD_GRID ? BOARD_GRID : 0;
        final List<GridEndpoint> docks = new ArrayList<>();
        for (double x = left + keepOutX; x <= right - keepOutX; x += step) {
            final double penalty = Math.abs(x - centerX) * DOCK_CENTER_BIAS;
            docks.add(new GridEndpoint(x, top, GridSide.TOP, penalty, topDepth));
            docks.add(new GridEndpoint(x, bottom, GridSide.BOTTOM, penalty, bottomDepth));
        }
        for (double y = top + keepOutY; y <= bottom - keepOutY; y += step) {
            final double penalty = Math.abs(y - centerY) * DOCK_CENTER_BIAS;
            docks.add(new GridEndpoint(left, y, GridSide.LEFT, penalty, leftDepth));
            docks.add(new GridEndpoint(right, y, GridSide.RIGHT, penalty, rightDepth));
        }
        if (docks.isEmpty()) {
            docks.add(new GridEndpoint(left, snapLine(centerY), GridSide.LEFT, 0, leftDepth));
            docks.add(new GridEndpoint(right, snapLine(centerY), GridSide.RIGHT, 0, rightDepth));
            docks.add(new GridEndpoint(snapLine(centerX), bottom, GridSide.BOTTOM, 0, bottomDepth));
            docks.add(new GridEndpoint(snapLine(centerX), top, GridSide.TOP, 0, topDepth));
        }
        return docks;
    }

    /** {@link #cardRimDocks(double, double, double, double)} of an obstacle. */
    public static List<GridEndpoint> cardRimDocks(final GridObstacle rect) {
        return cardRimDocks(rect.left(), rect.top(), rect.right(), rect.bottom());
    }

    private static double depth(final double gap) {
        return gap > 0.5 ? gap : 0;
    }

    /** The apron vertex: the endpoint pushed one cell out of its card, on-grid. */
    private static GridPoint apronPoint(final GridEndpoint endpoint) {
        final int dir = endpoint.side().outward;
        return new GridPoint(
            snapLine(endpoint.x()) + DIR_DX[dir] * WIRE_NODE_MARGIN,
            snapLine(endpoint.y()) + DIR_DY[dir] * WIRE_NODE_MARGIN);
    }

    private static boolean isFinite(final double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    private static boolean isFiniteEndpoint(final GridEndpoint endpoint) {
        return isFinite(endpoint.x()) && isFinite(endpoint.y());
    }

    private static List<GridEndpoint> finite(final List<GridEndpoint> endpoints) {
        final List<GridEndpoint> out = new ArrayList<>(endpoints.size());
        for (final GridEndpoint e : endpoints) if (isFiniteEndpoint(e)) out.add(e);
        return out;
    }

    private static String dockKey(final double x, final double y) {
        return (int) JsMath.round(x) + "," + (int) JsMath.round(y);
    }

    private static String dockKey(final GridEndpoint endpoint) {
        return dockKey(endpoint.x(), endpoint.y());
    }

    // endregion

    // region The solve

    private static final class SolveContext {

        final List<GridObstacle> obstacles;
        LaneOccupancy occupancy;
        final CellBounds board;
        double widePopCap;
        /** Dock points already taken, as "x,y", each against the wire holding it. */
        final Map<String, String> usedDocks = new HashMap<>();

        SolveContext(final List<GridObstacle> obstacles, final CellBounds board, final double widePopCap) {
            this.obstacles = obstacles;
            this.occupancy = new LaneOccupancy(board);
            this.board = board;
            this.widePopCap = widePopCap;
        }
    }

    /** A request after dock planning: the pruned docks, and the whole rim to fall back on. */
    private static final class Planned {

        final GridRouteRequest request;
        List<GridEndpoint> sources, targets;
        final List<GridEndpoint> allSources, allTargets;
        /** Rim-to-rim gap, independent of the docks eventually selected. */
        final double distanceCells;

        Planned(final GridRouteRequest request) {
            this.request = request;
            this.sources = finite(request.sources());
            this.targets = finite(request.targets());
            this.allSources = finite(request.sources());
            this.allTargets = finite(request.targets());
            this.distanceCells = endpointGapCells(request.sources(), request.targets());
        }

        String edgeId() {
            return request.edgeId();
        }

        @Nullable
        String sourceCardId() {
            return request.sourceCardId();
        }

        @Nullable
        String targetCardId() {
            return request.targetCardId();
        }

        boolean selfLoop() {
            return request.sourceCardId() != null && request.sourceCardId()
                .equals(request.targetCardId());
        }

        List<GridPoint> waypoints() {
            return request.waypoints() == null ? List.of() : request.waypoints();
        }
    }

    /** A found route before it is installed: its ends, its vertex chain in cells, and what the search paid. */
    private record RouteFound(GridEndpoint source, GridEndpoint target, List<GridPoint> vertices, double cost) {}

    /** One wire's standing in the solve. */
    private static final class RouteState {

        final Planned request;
        @Nullable
        final RouteFound found;
        final GridRoutedEdge routed;
        int crossings;
        final boolean overflowed;
        boolean stuck;
        boolean pinned;

        RouteState(final Planned request, @Nullable final RouteFound found, final GridRoutedEdge routed,
            final int crossings, final boolean overflowed) {
            this.request = request;
            this.found = found;
            this.routed = routed;
            this.crossings = crossings;
            this.overflowed = overflowed;
        }
    }

    /** {@link #solve(List, List, GridSolveStats, RouterTuning, List)} with nothing pinned. */
    public Map<String, GridRoutedEdge> solve(final List<GridObstacle> obstacles, final List<GridRouteRequest> requests,
        @Nullable final GridSolveStats stats, final RouterTuning tuning) {
        return solve(obstacles, requests, stats, tuning, List.of());
    }

    /**
     * Every wire on the board as one solve ({@code solveGridRoutes}): docks planned per card, a first pass thickest
     * then longest first, then negotiation (wires that cross or overflow ripped up and rerouted against the finished
     * board, contested spots dearer each round, dock swaps between siblings). {@code pinned} routes are installed
     * first exactly as they are and never touched. Returns each wire's route by edge id, in install order.
     */
    public Map<String, GridRoutedEdge> solve(final List<GridObstacle> obstacles, final List<GridRouteRequest> requests,
        @Nullable final GridSolveStats stats, final RouterTuning tuning, final List<PinnedRoute> pinned) {
        applyTuning(tuning);
        workPops = 0;
        workPriced = 0;
        workSearches = 0;
        final double startedAt = clock.nowMs();
        final List<GridRouteRequest> everyRequest = new ArrayList<>(requests);
        for (final PinnedRoute pin : pinned) everyRequest.add(pin.request());
        final CellBounds bounds = boardBounds(obstacles, everyRequest);
        final SolveContext context = new SolveContext(
            obstacles,
            bounds,
            NEGOTIATION_POPS_PER_WIRE * requests.size() / 2);
        final List<Planned> planned = planDocks(requests);
        final List<Planned> sorted = new ArrayList<>(planned);
        JsSort.sort(sorted, (left, right) -> {
            int c = JsMath.sign(right.request.strokeWidth() - left.request.strokeWidth());
            if (c != 0) return c;
            c = JsMath.sign(t.longestFirst() ? routeSpan(right) - routeSpan(left) : routeSpan(left) - routeSpan(right));
            if (c != 0) return c;
            c = JsMath.sign(left.request.order() - right.request.order());
            if (c != 0) return c;
            return left.edgeId()
                .compareTo(right.edgeId());
        });

        final List<RouteState> states = new ArrayList<>();
        for (final PinnedRoute pin : pinned) {
            final GridRoutedEdge route = pin.route();
            final Planned request = new Planned(pin.request());
            if (route.vertices() != null && route.source() != null && route.target() != null) {
                final RouteState state = install(
                    context,
                    request,
                    new RouteFound(route.source(), route.target(), route.vertices(), 0));
                state.pinned = true;
                states.add(state);
            } else if (route.points()
                .size() >= 2) {
                    // A boxed-in L held as it was: searching again would find no way either.
                    final RouteState state = new RouteState(request, null, route, 0, false);
                    state.pinned = true;
                    states.add(state);
                } else {
                    final RouteState state = routeOne(context, request, null);
                    state.pinned = true;
                    states.add(state);
                }
        }
        for (final Planned request : sorted) states.add(routeOne(context, request, null));

        final double firstPassMs = clock.nowMs() - startedAt;
        // The measure of the board as it stands, kept until a wire's route changes (every states.set clears it): the
        // website measures the same unchanged board up to four times a solve, and a measure costs as much as the
        // board is big. Same routes in, same measure out, so reusing it changes nothing.
        final Measured now = new Measured(states);
        final List<GridRoutedEdge>[] best = new List[] { routedOf(states) };
        final WireMetrics.Measure[] bestMeasure = { now.get() };
        final double[] bestPoints = { WireMetrics.points(bestMeasure[0], t) };
        // The board kept is the one with the fewest points.
        final Runnable retainBest = () -> {
            final List<GridRoutedEdge> routes = routedOf(states);
            final WireMetrics.Measure measure = now.get();
            final double points = WireMetrics.points(measure, t);
            if (points < bestPoints[0]) {
                best[0] = routes;
                bestPoints[0] = points;
                bestMeasure[0] = measure;
            }
        };
        int rerouted = 0;
        // Budgets count the wires that can move: pinned ones never yield.
        final int movable = states.size() - pinned.size();
        double budget = Math.max(Math.min(12, 4 * movable), JsMath.round(t.negotiationBudget() * movable));
        final double popCap = NEGOTIATION_POPS_PER_WIRE * movable;
        context.widePopCap = popCap;
        readCrossings(states, now.get());
        for (int round = 0; round < t.negotiationRounds() && budget > 0 && workPops < popCap; round += 1) {
            final List<Integer> unhappy = new ArrayList<>();
            for (int i = 0; i < states.size(); i += 1) {
                final RouteState state = states.get(i);
                state.stuck = false;
                if (state.found != null && !state.pinned && (state.crossings > 0 || state.overflowed)) unhappy.add(i);
            }
            if (unhappy.isEmpty()) break;
            context.occupancy.escalate();
            for (final int index : unhappy) {
                if (budget == 0 || workPops >= popCap) break;
                final RouteState state = states.get(index);
                ripUp(context, state);
                final RouteState again = routeOne(context, state.request, state.found);
                if (state.found != null && again.found != null && sameRoute(state.found, again.found))
                    again.stuck = true;
                states.set(index, again);
                now.stale();
                rerouted += 1;
                budget -= 1;
            }
            int remaining = readCrossings(states, now.get());
            retainBest.run();
            if (remaining == 0) break;
            // SWAPS: two wires leaving one card that still cross trade planned docks; kept only if the board's
            // crossings fall.
            for (int i = 0; i < states.size() && budget >= 2 && workPops < popCap; i += 1) {
                final RouteState a = states.get(i);
                if (a.found == null || a.pinned || a.crossings == 0) continue;
                for (int k = i + 1; k < states.size() && budget >= 2; k += 1) {
                    final RouteState b = states.get(k);
                    if (b.found == null || b.pinned || b.crossings == 0 || !sharesCard(a.request, b.request))
                        continue;
                    final RouteFound foundA = a.found;
                    final RouteFound foundB = b.found;
                    swapPlans(a.request, b.request);
                    ripUp(context, a);
                    ripUp(context, b);
                    final RouteState nextA = routeOne(context, a.request, foundA);
                    final RouteState nextB = routeOne(context, b.request, foundB);
                    budget -= 2;
                    rerouted += 2;
                    states.set(i, nextA);
                    states.set(k, nextB);
                    now.stale();
                    final int after = readCrossings(states, now.get());
                    if (after < remaining) {
                        remaining = after;
                        retainBest.run();
                        break;
                    }
                    // No better: put both back exactly as they were.
                    ripUp(context, nextA);
                    ripUp(context, nextB);
                    swapPlans(a.request, b.request);
                    states.set(i, install(context, a.request, foundA));
                    states.set(k, install(context, b.request, foundB));
                    now.stale();
                    readCrossings(states, now.get());
                }
            }
            if (remaining == 0) break;
        }

        // Lanes are packed as wires arrive; once every route is final, pack them all again on a clean board.
        if (rerouted > 0) {
            context.occupancy = new LaneOccupancy(bounds);
            for (int i = 0; i < states.size(); i += 1) {
                final RouteState state = states.get(i);
                if (state.found != null) {
                    final boolean pinnedState = state.pinned;
                    final RouteState again = install(context, state.request, state.found);
                    again.pinned = pinnedState;
                    states.set(i, again);
                    now.stale();
                }
            }
        }

        retainBest.run();
        final Map<String, GridRoutedEdge> results = new LinkedHashMap<>();
        for (final GridRoutedEdge route : best[0]) results.put(route.edgeId(), route);
        if (stats != null) {
            int fallbacks = 0;
            for (final RouteState state : states) {
                if (state.found == null && !state.routed.points()
                    .isEmpty()) fallbacks += 1;
            }
            stats.crossings = bestMeasure[0].crossings();
            stats.rerouted = rerouted;
            stats.fallbacks = fallbacks;
            stats.pops = workPops;
            stats.priced = workPriced;
            stats.searches = workSearches;
            stats.firstPassMs = JsMath.round(firstPassMs);
            stats.perEdge.clear();
            for (final RouteState state : states) stats.perEdge.put(
                state.request.edgeId(),
                new GridSolveStats.PerEdge(state.crossings, state.overflowed, state.found == null));
            stats.negotiationMs = JsMath.round(clock.nowMs() - startedAt - firstPassMs);
        }
        return results;
    }

    private static List<GridRoutedEdge> routedOf(final List<RouteState> states) {
        final List<GridRoutedEdge> routes = new ArrayList<>(states.size());
        for (final RouteState state : states) routes.add(state.routed);
        return routes;
    }

    /** The board's extent in cells, padded past the largest search window a wire can grow to. */
    private CellBounds boardBounds(final List<GridObstacle> obstacles, final List<GridRouteRequest> requests) {
        final double[] box = { Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY };
        for (final GridObstacle obstacle : obstacles) {
            take(box, obstacle.left(), obstacle.top());
            take(box, obstacle.right(), obstacle.bottom());
        }
        for (final GridRouteRequest request : requests) {
            for (final GridEndpoint endpoint : request.sources()) take(box, endpoint.x(), endpoint.y());
            for (final GridEndpoint endpoint : request.targets()) take(box, endpoint.x(), endpoint.y());
            if (request.waypoints() != null)
                for (final GridPoint stop : request.waypoints()) take(box, stop.x(), stop.y());
        }
        if (box[0] == Double.POSITIVE_INFINITY) return new CellBounds(0, 0, 1, 1);
        final int padCells = (int) Math.ceil(t.windowPad() * WINDOW_GROWTH) + 2;
        final int x0 = (int) Math.floor(box[0] / BOARD_GRID) - padCells;
        final int y0 = (int) Math.floor(box[2] / BOARD_GRID) - padCells;
        final int x1 = (int) Math.ceil(box[1] / BOARD_GRID) + padCells;
        final int y1 = (int) Math.ceil(box[3] / BOARD_GRID) + padCells;
        return new CellBounds(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
    }

    private static void take(final double[] box, final double x, final double y) {
        if (!isFinite(x) || !isFinite(y)) return;
        if (x < box[0]) box[0] = x;
        if (x > box[1]) box[1] = x;
        if (y < box[2]) box[2] = y;
        if (y > box[3]) box[3] = y;
    }

    /** The measure of the solve's wires as they stand, worked out once until one of them changes. */
    private static final class Measured {

        private final List<RouteState> states;
        @Nullable
        private WireMetrics.Measure measure;

        Measured(final List<RouteState> states) {
            this.states = states;
        }

        WireMetrics.Measure get() {
            if (measure == null) measure = WireMetrics.measureRoutes(routedOf(states));
            return measure;
        }

        /** A route changed: measure again when next asked. */
        void stale() {
            measure = null;
        }
    }

    /** Re-reads every placed wire's crossings from their measure; returns the total. */
    private static int readCrossings(final List<RouteState> states, final WireMetrics.Measure measured) {
        final Map<String, RouteState> byId = new HashMap<>();
        for (final RouteState state : states) {
            state.crossings = 0;
            byId.put(state.request.edgeId(), state);
        }
        for (final WireMetrics.Crossing event : measured.events()) {
            byId.get(event.a()).crossings++;
            byId.get(event.b()).crossings++;
        }
        return measured.crossings();
    }

    private static boolean sharesCard(final Planned a, final Planned b) {
        return a.sourceCardId() != null && a.sourceCardId()
            .equals(b.sourceCardId())
            || a.targetCardId() != null && a.targetCardId()
                .equals(b.targetCardId());
    }

    /** Trades the planned docks on every card two wires share. */
    private static void swapPlans(final Planned a, final Planned b) {
        if (a.sourceCardId() != null && a.sourceCardId()
            .equals(b.sourceCardId())) {
            final List<GridEndpoint> sources = a.sources;
            a.sources = b.sources;
            b.sources = sources;
        }
        if (a.targetCardId() != null && a.targetCardId()
            .equals(b.targetCardId())) {
            final List<GridEndpoint> targets = a.targets;
            a.targets = b.targets;
            b.targets = targets;
        }
    }

    private static boolean sameRoute(final RouteFound a, final RouteFound b) {
        if (a.vertices.size() != b.vertices.size()) return false;
        for (int i = 0; i < a.vertices.size(); i += 1) {
            if (a.vertices.get(i)
                .x()
                != b.vertices.get(i)
                    .x()
                || a.vertices.get(i)
                    .y()
                    != b.vertices.get(i)
                        .y())
                return false;
        }
        return true;
    }

    /** Manhattan distance between a wire's two candidate sets, for the order. */
    private static double routeSpan(final Planned request) {
        final GridPoint source = endpointsCentre(request.sources);
        final GridPoint target = endpointsCentre(request.targets);
        if (source == null || target == null) return 0;
        return Math.abs(source.x() - target.x()) + Math.abs(source.y() - target.y());
    }

    /** left, top, right, bottom of the finite endpoints; null for none. */
    @Nullable
    private static double[] endpointsRect(final List<GridEndpoint> endpoints) {
        double left = Double.POSITIVE_INFINITY, right = Double.NEGATIVE_INFINITY, top = Double.POSITIVE_INFINITY,
            bottom = Double.NEGATIVE_INFINITY;
        for (final GridEndpoint endpoint : endpoints) {
            if (!isFiniteEndpoint(endpoint)) continue;
            if (endpoint.x() < left) left = endpoint.x();
            if (endpoint.x() > right) right = endpoint.x();
            if (endpoint.y() < top) top = endpoint.y();
            if (endpoint.y() > bottom) bottom = endpoint.y();
        }
        if (left == Double.POSITIVE_INFINITY) return null;
        return new double[] { left, top, right, bottom };
    }

    @Nullable
    private static GridPoint endpointsCentre(final List<GridEndpoint> endpoints) {
        final double[] rect = endpointsRect(endpoints);
        return rect == null ? null : new GridPoint((rect[0] + rect[2]) / 2, (rect[1] + rect[3]) / 2);
    }

    /** Forgets everything a wire holds: lanes, passers and both docks. */
    private static void ripUp(final SolveContext context, final RouteState state) {
        final String edgeId = state.request.edgeId();
        context.occupancy.release(edgeId);
        context.usedDocks.values()
            .removeIf(holder -> holder.equals(edgeId));
    }

    // endregion

    // region Dock planning

    /** A card's perimeter as one line: the distance clockwise from the top-left corner. */
    private static double perimeterParam(final GridSide side, final double x, final double y, final double[] rect) {
        final double width = rect[2] - rect[0];
        final double height = rect[3] - rect[1];
        final double at = switch (side) {
            case TOP -> x - rect[0];
            case RIGHT -> width + (y - rect[1]);
            case BOTTOM -> width + height + (rect[2] - x);
            case LEFT -> 2 * width + height + (rect[3] - y);
        };
        return at;
    }

    private static final class DockEnd {

        final Planned request;
        final boolean source;
        final String cardId;
        @Nullable
        final String farCardId;
        final double[] rect;
        final double ideal, angle;
        double planned;

        DockEnd(final Planned request, final boolean source, final String cardId, @Nullable final String farCardId,
            final double[] rect, final double ideal, final double angle) {
            this.request = request;
            this.source = source;
            this.cardId = cardId;
            this.farCardId = farCardId;
            this.rect = rect;
            this.ideal = ideal;
            this.angle = angle;
            this.planned = ideal;
        }

        List<GridEndpoint> own() {
            return source ? request.sources : request.targets;
        }
    }

    /**
     * Plans where every wire leaves and arrives, one card at a time ({@code planDocks}): each end's ideal exit is the
     * rim point nearest the far end (or its first or last dot); siblings on one card are sorted round the rim and
     * matched onto real docks in that order, so none crosses another to leave; two wires between the same pair of
     * cards are ordered one way at one card and the other way at the other. The plan becomes each candidate's
     * penalty, docks far from it set aside.
     */
    private List<Planned> planDocks(final List<GridRouteRequest> requests) {
        final Map<String, List<DockEnd>> byCard = new LinkedHashMap<>();
        final List<Planned> copies = new ArrayList<>(requests.size());
        for (final GridRouteRequest request : requests) copies.add(new Planned(request));
        for (final Planned request : copies) {
            for (final boolean source : new boolean[] { true, false }) {
                final String cardId = source ? request.sourceCardId() : request.targetCardId();
                final List<GridEndpoint> own = source ? request.sources : request.targets;
                if (cardId == null || own.size() < 2) continue;
                final double[] rect = endpointsRect(own);
                final GridPoint far = endpointsCentre(source ? request.targets : request.sources);
                if (rect == null || rect[2] - rect[0] <= 0 || rect[3] - rect[1] <= 0 || far == null) continue;
                final List<GridPoint> stops = request.waypoints();
                final GridPoint heading = !stops.isEmpty() ? source ? stops.get(0) : stops.get(stops.size() - 1)
                    : far;
                final double centreX = (rect[0] + rect[2]) / 2;
                final double centreY = (rect[1] + rect[3]) / 2;
                final double angle = com.gtnhplanner.core.base.JsMath.rem(
                    ExactMath.atan2(heading.y() - centreY, heading.x() - centreX) + Math.PI * 1.25 + Math.PI * 4,
                    Math.PI * 2);
                final double ideal = exitParam(rect, heading);
                byCard.computeIfAbsent(cardId, k -> new ArrayList<>())
                    .add(
                        new DockEnd(
                            request,
                            source,
                            cardId,
                            source ? request.targetCardId() : request.sourceCardId(),
                            rect,
                            ideal,
                            angle));
            }
        }

        for (final Map.Entry<String, List<DockEnd>> entry : byCard.entrySet()) {
            final String cardId = entry.getKey();
            final List<DockEnd> ends = entry.getValue();
            JsSort.sort(ends, (left, right) -> {
                if (left.ideal != right.ideal) return JsMath.sign(left.ideal - right.ideal);
                if (left.angle != right.angle) return JsMath.sign(left.angle - right.angle);
                final int compared = left.request.edgeId()
                    .compareTo(right.request.edgeId());
                final int byEdge = compared < 0 ? -1 : compared > 0 ? 1 : 0;
                // Same far card at both: mirror the order at the far side.
                if (left.farCardId != null && left.farCardId.equals(right.farCardId))
                    return cardId.compareTo(left.farCardId) < 0 ? byEdge : -byEdge;
                return byEdge;
            });
            final double[] rect = ends.get(0).rect;
            final double perimeter = 2 * (rect[2] - rect[0] + (rect[3] - rect[1]));
            // The docks this card actually offers, once round the rim.
            final Set<Double> dockParams = new LinkedHashSet<>();
            for (final DockEnd dockEnd : ends) {
                for (final GridEndpoint endpoint : dockEnd.own()) {
                    // + 0.0 turns -0 into 0, which a JavaScript Set holds as one.
                    dockParams.add(perimeterParam(endpoint.side(), endpoint.x(), endpoint.y(), dockEnd.rect) + 0.0);
                }
            }
            final double[] docks = new double[dockParams.size()];
            int n = 0;
            for (final double d : dockParams) docks[n++] = d;
            Arrays.sort(docks);
            assignDocks(ends, docks);
            for (final DockEnd dockEnd : ends) {
                final List<GridEndpoint> priced = new ArrayList<>();
                for (final GridEndpoint endpoint : dockEnd.own()) {
                    final double at = perimeterParam(endpoint.side(), endpoint.x(), endpoint.y(), dockEnd.rect);
                    final double gap = Math.abs(at - dockEnd.planned);
                    final double distance = Math.min(gap, perimeter - gap);
                    if (distance <= t.dockPlanWindow() * BOARD_GRID + 1e-6)
                        priced.add(endpoint.withPenalty(distance * t.dockPlanBias()));
                }
                if (priced.isEmpty()) continue;
                if (dockEnd.source) dockEnd.request.sources = priced;
                else dockEnd.request.targets = priced;
            }
        }
        return copies;
    }

    /** Nearby cards cannot earn diagonals by docking on their far sides. */
    private static double endpointGapCells(final List<GridEndpoint> sources, final List<GridEndpoint> targets) {
        final double[] a = endpointsRect(finite(sources));
        final double[] b = endpointsRect(finite(targets));
        if (a == null || b == null) return 0;
        return Math.max(Math.max(Math.max(Math.max(0, a[0] - b[2]), b[0] - a[2]), a[1] - b[3]), b[1] - a[3])
            / BOARD_GRID;
    }

    /** Soft cost for a bent route whose docks sit fewer than {@code dockTravelCells} apart. */
    private double crampedDockCost(final Planned request, final GridEndpoint source, final GridEndpoint target) {
        if (request.allSources.size() < 2 && request.allTargets.size() < 2 || !request.waypoints()
            .isEmpty() || request.selfLoop()) return 0;
        final double travel = (Math.abs(source.x() - target.x()) + Math.abs(source.y() - target.y())) / BOARD_GRID;
        return Math.max(0, t.dockTravelCells() - travel) * (t.turn90() + t.earlyTurn() + BOARD_GRID);
    }

    /** The rim point nearest {@code heading}: the side the destination is beyond, at its clamped coordinate. */
    private static double exitParam(final double[] rect, final GridPoint heading) {
        final double clampX = Math.min(Math.max(heading.x(), rect[0]), rect[2]);
        final double clampY = Math.min(Math.max(heading.y(), rect[1]), rect[3]);
        if (heading.y() < rect[1]) return perimeterParam(GridSide.TOP, clampX, rect[1], rect);
        if (heading.y() > rect[3]) return perimeterParam(GridSide.BOTTOM, clampX, rect[3], rect);
        if (heading.x() < rect[0]) return perimeterParam(GridSide.LEFT, rect[0], clampY, rect);
        if (heading.x() > rect[2]) return perimeterParam(GridSide.RIGHT, rect[2], clampY, rect);
        final double halfWidth = (rect[2] - rect[0]) / 2;
        final double halfHeight = (rect[3] - rect[1]) / 2;
        final double centreX = rect[0] + halfWidth;
        final double centreY = rect[1] + halfHeight;
        final double dx = heading.x() - centreX;
        final double dy = heading.y() - centreY;
        if (dx == 0 && dy == 0) return perimeterParam(GridSide.RIGHT, rect[2], centreY, rect);
        if (Math.abs(dx) * halfHeight >= Math.abs(dy) * halfWidth) {
            final double y = centreY + dy * halfWidth / Math.abs(dx);
            return dx > 0 ? perimeterParam(GridSide.RIGHT, rect[2], y, rect)
                : perimeterParam(GridSide.LEFT, rect[0], y, rect);
        }
        final double x = centreX + dx * halfHeight / Math.abs(dy);
        return dy > 0 ? perimeterParam(GridSide.BOTTOM, x, rect[3], rect)
            : perimeterParam(GridSide.TOP, x, rect[1], rect);
    }

    /**
     * Hands each of a card's wires (sorted round the rim) its own real dock, in that order, minimising total distance
     * from their ideals: a monotone matching by dynamic programming. More wires than docks: the overflow takes its
     * nearest dock and shares.
     */
    private static void assignDocks(final List<DockEnd> ends, final double[] docks) {
        final int n = ends.size();
        final int m = docks.length;
        if (m == 0) return;
        final int matched = Math.min(n, m);
        final double[][] dp = new double[matched + 1][m + 1];
        for (int i = 1; i <= matched; i += 1) Arrays.fill(dp[i], Double.POSITIVE_INFINITY);
        for (int i = 1; i <= matched; i += 1) {
            for (int j = i; j <= m; j += 1) {
                final double skip = dp[i][j - 1];
                final double take = dp[i - 1][j - 1] + Math.abs(docks[j - 1] - ends.get(i - 1).ideal);
                dp[i][j] = skip < take ? skip : take;
            }
        }
        int j = m;
        for (int i = matched; i >= 1; i -= 1) {
            while (j > i && dp[i][j - 1] <= dp[i][j]) j -= 1;
            ends.get(i - 1).planned = docks[j - 1];
            j -= 1;
        }
        for (int i = matched; i < n; i += 1) {
            double best = docks[0];
            for (final double dock : docks) {
                if (Math.abs(dock - ends.get(i).ideal) < Math.abs(best - ends.get(i).ideal)) best = dock;
            }
            ends.get(i).planned = best;
        }
    }

    // endregion

    // region One wire

    /** Routes one wire against the board and installs it; {@code previous} goes back if nothing better is found. */
    private RouteState routeOne(final SolveContext context, final Planned request,
        @Nullable final RouteFound previous) {
        RouteFound found = findRoute(context, request);
        if (found == null) found = previous;
        if (found != null) return install(context, request, found);
        return new RouteState(request, null, fallbackRoute(context, request), 0, false);
    }

    /** Claims the found route's lanes and docks and draws its polyline. */
    private RouteState install(final SolveContext context, final Planned request, final RouteFound found) {
        context.usedDocks.put(dockKey(found.source), request.edgeId());
        context.usedDocks.put(dockKey(found.target), request.edgeId());
        final Assembled assembled = claimAndAssemble(context, request, found);
        return new RouteState(
            request,
            found,
            new GridRoutedEdge(
                request.edgeId(),
                assembled.points,
                request.request.strokeWidth(),
                found.vertices,
                found.source,
                found.target),
            assembled.crossings,
            assembled.overflowed);
    }

    /** The boxed-in L: the wire still exists, and may cross things. */
    private static GridRoutedEdge fallbackRoute(final SolveContext context, final Planned request) {
        if (request.allSources.isEmpty() || request.allTargets.isEmpty())
            return new GridRoutedEdge(request.edgeId(), List.of(), request.request.strokeWidth(), null, null, null);
        final GridEndpoint source = request.allSources.get(0);
        final GridEndpoint target = request.allTargets.get(0);
        context.usedDocks.put(dockKey(source), request.edgeId());
        context.usedDocks.put(dockKey(target), request.edgeId());
        final GridPoint sourceApron = apronPoint(source);
        final GridPoint targetApron = apronPoint(target);
        return new GridRoutedEdge(
            request.edgeId(),
            compactPoints(
                List.of(
                    new GridPoint(source.x(), source.y()),
                    sourceApron,
                    new GridPoint(sourceApron.x(), targetApron.y()),
                    targetApron,
                    new GridPoint(target.x(), target.y()))),
            request.request.strokeWidth(),
            null,
            null,
            null);
    }

    private static boolean contains(@Nullable final List<String> ids, final String id) {
        return ids != null && ids.contains(id);
    }

    @Nullable
    private RouteFound findRoute(final SolveContext context, final Planned request) {
        if (request.allSources.isEmpty() || request.allTargets.isEmpty()) return null;
        // The frames this wire lives inside are not obstacles to it.
        final List<String> exempt = request.request.exemptObstacleIds();
        final List<String> home = request.request.homeObstacleIds();
        final boolean hasExemptIds = exempt != null && !exempt.isEmpty();
        final List<GridObstacle> obstacles;
        final List<GridObstacle> exemptRects = new ArrayList<>();
        if (hasExemptIds) {
            obstacles = new ArrayList<>();
            for (final GridObstacle o : context.obstacles) {
                if (!exempt.contains(o.id())) obstacles.add(o);
                if (exempt.contains(o.id()) && !contains(home, o.id())) exemptRects.add(o);
            }
        } else obstacles = context.obstacles;
        final List<GridObstacle> homeRects = new ArrayList<>();
        if (home != null && !home.isEmpty())
            for (final GridObstacle o : context.obstacles) if (home.contains(o.id())) homeRects.add(o);
        final List<GridPoint> waypoints = new ArrayList<>();
        for (final GridPoint p : request.waypoints()) if (isFinite(p.x()) && isFinite(p.y())) waypoints.add(p);

        // The planned docks first; the whole rim if those cannot route.
        final List<List<GridEndpoint>[]> menus = new ArrayList<>();
        menus.add(new List[] { request.sources, request.targets });
        if (request.allSources.size() > request.sources.size() || request.allTargets.size() > request.targets.size())
            menus.add(new List[] { request.allSources, request.allTargets });
        final RouteFound[] best = { null };
        final boolean selfLoop = request.selfLoop();
        if (waypoints.isEmpty() && !selfLoop) {
            final RouteFound direct = directDock(
                context,
                priceTaken(context, request.allSources),
                priceTaken(context, request.allTargets));
            if (direct != null && consider(best, direct)) return direct;
        }
        // Two rungs: the planned docks inside the wire's own box, then the whole rim across the whole board.
        final List<Object[]> rungs = new ArrayList<>();
        rungs.add(new Object[] { request.sources, request.targets, t.windowPad() * BOARD_GRID, 1.0 });
        final List<GridEndpoint>[] last = menus.get(menus.size() - 1);
        if (t.wideRungCells() > t.windowPad())
            rungs.add(new Object[] { last[0], last[1], t.wideRungCells() * BOARD_GRID, BOARD_RUNG_WEIGHT });
        // Unreachable dots must not cost the wire its route: try again without.
        final List<List<GridPoint>> stopSets = new ArrayList<>();
        if (!waypoints.isEmpty()) {
            stopSets.add(waypoints);
            stopSets.add(List.of());
        } else stopSets.add(List.of());
        for (final List<GridPoint> stops : stopSets) {
            for (final Object[] rung : rungs) {
                final double pad = (Double) rung[2];
                if (pad > t.windowPad() * BOARD_GRID && workPops >= context.widePopCap) break;
                @SuppressWarnings("unchecked")
                final RouteFound routed = new Window(
                    context,
                    obstacles,
                    exemptRects,
                    homeRects,
                    request,
                    priceTaken(context, (List<GridEndpoint>) rung[0]),
                    priceTaken(context, (List<GridEndpoint>) rung[1]),
                    stops,
                    pad,
                    (Double) rung[3]).route();
                if (routed == SEALED) break;
                if (routed != null && consider(best, routed)) return routed;
            }
            if (best[0] != null) break;
        }
        return best[0];
    }

    /** A dock another wire already uses costs {@code dockShare} more but stays on the menu. */
    private List<GridEndpoint> priceTaken(final SolveContext context, final List<GridEndpoint> endpoints) {
        final List<GridEndpoint> out = new ArrayList<>(endpoints.size());
        for (final GridEndpoint endpoint : endpoints) out.add(
            context.usedDocks.containsKey(dockKey(endpoint)) ? endpoint.withPenalty(endpoint.penalty() + t.dockShare())
                : endpoint);
        return out;
    }

    /** Keeps the cheapest; true when this one paid for no crossing (the search can stop). */
    private boolean consider(final RouteFound[] best, final RouteFound found) {
        if (best[0] == null || found.cost < best[0].cost) best[0] = found;
        return found.cost < t.crossing();
    }

    /**
     * Touching docks: a source dock whose apron is a facing target dock (or, for borders less than a cell apart, the
     * same point) connects straight across without a search.
     */
    @Nullable
    private RouteFound directDock(final SolveContext context, final List<GridEndpoint> sources,
        final List<GridEndpoint> targets) {
        final Map<String, List<GridEndpoint>> targetsAt = new HashMap<>();
        for (final GridEndpoint target : targets)
            targetsAt.computeIfAbsent(dockKey(target), k -> new ArrayList<>())
                .add(target);
        RouteFound best = null;
        for (final GridEndpoint source : sources) {
            final int dir = source.side().outward;
            for (final int reach : new int[] { BOARD_GRID, 0 }) {
                final double x = source.x() + DIR_DX[dir] * reach;
                final double y = source.y() + DIR_DY[dir] * reach;
                final List<GridEndpoint> at = targetsAt.get(dockKey(x, y));
                if (at == null) continue;
                for (final GridEndpoint target : at) {
                    if ((dir + 4) % 8 != target.side().outward) continue;
                    if (reach == 0 && source.stubDepth() + target.stubDepth() <= 0) continue;
                    final int vx = (int) JsMath.round(x / BOARD_GRID), vy = (int) JsMath.round(y / BOARD_GRID);
                    final double cost = reach + source.penalty()
                        + target.penalty()
                        + context.occupancy.stepCrossings(DIR_AXIS[dir], vx, vy, vx, vy, false) * t.crossing();
                    if (best == null || cost < best.cost)
                        best = new RouteFound(source, target, List.of(new GridPoint(vx, vy)), cost);
                }
            }
        }
        return best;
    }

    // endregion

    // region The search

    /** One end's place in the window: the apron, the clean point, the stub's direction and what they cost. */
    private record EndVertex(int endpointIndex, int apron, int clean, int outward, double cleanCost,
        double exitCost) {}

    private record LegSeed(int state, double g, int startIndex) {}

    private record LegGoal(int vertex, int dir, double penalty, int endpointIndex, int[] tail, boolean landing) {}

    private record LegResult(int startIndex, int arrivalDir, int[] vertices, LegGoal goal, double cost) {}

    /** One wire's search window ({@code routeWithinWindow}): the cells round its ends, priced for that wire. */
    private final class Window {

        private final SolveContext context;
        private final List<GridObstacle> obstacles, exemptRects, homeRects;
        private final Planned request;
        private final List<GridEndpoint> sources, targets;
        private final List<GridPoint> waypoints;
        private final double pad, weight;
        private int cx0, cy0, width, height, vertexCount, stateCount, maxPops, stepGeneration;
        private boolean straightOnly, hasExempt, hasHome, selfLoop;
        private byte[] blocked, exemptFlag, homeFlag;
        private List<EndVertex> starts;
        /**
         * The clean zone: per vertex where it is stamped {@code zoneGeneration}, the start variants whose clean run
         * passes it, in the order added ({@code zoneVariants[zonePoolIndex[vertex]]}, {@code zoneSizes} long).
         */
        private int zoneGeneration, zoneCount;
        private int[][] zoneVariants = new int[0][];
        private int[] zoneSizes = new int[0];
        private double goalLeft, goalRight, goalTop, goalBottom, minTurn;

        Window(final SolveContext context, final List<GridObstacle> obstacles, final List<GridObstacle> exemptRects,
            final List<GridObstacle> homeRects, final Planned request, final List<GridEndpoint> sources,
            final List<GridEndpoint> targets, final List<GridPoint> waypoints, final double pad, final double weight) {
            this.context = context;
            this.obstacles = obstacles;
            this.exemptRects = exemptRects;
            this.homeRects = homeRects;
            this.request = request;
            this.sources = sources;
            this.targets = targets;
            this.waypoints = waypoints;
            this.pad = pad;
            this.weight = weight;
        }

        private int toCell(final double value) {
            return (int) JsMath.round(value / BOARD_GRID);
        }

        private int vertexAt(final int xi, final int yi) {
            return xi * height + yi;
        }

        private boolean inWindow(final int xi, final int yi) {
            return xi >= 0 && xi < width && yi >= 0 && yi < height;
        }

        private void fillRect(final byte[] flags, final GridObstacle rect, final double inflate,
            final boolean strict) {
            final double epsilon = strict ? 1e-6 : -1e-6;
            final int xa = (int) Math.max(Math.ceil((rect.left() - inflate + epsilon) / BOARD_GRID), cx0);
            final int xb = (int) Math.min(Math.floor((rect.right() + inflate - epsilon) / BOARD_GRID), cx0 + width - 1);
            final int ya = (int) Math.max(Math.ceil((rect.top() - inflate + epsilon) / BOARD_GRID), cy0);
            final int yb = (int) Math.min(
                Math.floor((rect.bottom() + inflate - epsilon) / BOARD_GRID),
                cy0 + height - 1);
            for (int x = xa; x <= xb; x += 1) {
                for (int y = ya; y <= yb; y += 1) flags[vertexAt(x - cx0, y - cy0)] = 1;
            }
        }

        /** Cost of the step from (xi, yi) in direction dir, or {@link #STEP_BLOCKED}; priced once per attempt. */
        private double priceStep(final int xi, final int yi, final int dir) {
            final int nxi = xi + DIR_DX[dir];
            final int nyi = yi + DIR_DY[dir];
            if (!inWindow(nxi, nyi)) return STEP_BLOCKED;
            // Canonical orientation: E, SE, S, SW from the lower vertex.
            final int stepIndex = dir < 4 ? vertexAt(xi, yi) * 4 + dir : vertexAt(nxi, nyi) * 4 + (dir - 4);
            final double[] pool = stepPool;
            if (pool[2 * stepIndex] == stepGeneration) return pool[2 * stepIndex + 1];
            return priceNewStep(xi, yi, dir, nxi, nyi, stepIndex);
        }

        /**
         * {@link #priceStep} the first time a step is asked for in this window. Kept apart so the cached path stays
         * small enough for the JIT to inline into the search loop.
         */
        private double priceNewStep(final int xi, final int yi, final int dir, final int nxi, final int nyi,
            final int stepIndex) {
            final double[] pool = stepPool;
            pool[2 * stepIndex] = stepGeneration;
            workPriced += 1;
            final int a = vertexAt(xi, yi);
            final int b = vertexAt(nxi, nyi);
            boolean free = blocked[a] == 0 && blocked[b] == 0 && (t.diagonals() || (dir & 1) == 0);
            if (free && (dir & 1) == 1) {
                // No corner cutting: a diagonal needs both orthogonal neighbours free.
                free = blocked[vertexAt(nxi, yi)] == 0 && blocked[vertexAt(xi, nyi)] == 0;
            }
            if (!free) {
                pool[2 * stepIndex + 1] = STEP_BLOCKED;
                return STEP_BLOCKED;
            }
            final int axis = DIR_AXIS[dir];
            final double capacity = axis >= 2 ? t.diagonalLaneCapacity() : LANE_CAPACITY;
            final double used = context.occupancy.usedWidth(axis, xi + cx0, yi + cy0, nxi + cx0, nyi + cy0);
            final boolean fits = used == 0 || used + LANE_GAP + request.request.strokeWidth() <= capacity;
            final double factor = used == 0 ? t.costEmpty() : fits ? t.costShared() : t.costOverflow();
            double framePenalty = 1;
            if (hasExempt && (exemptFlag[a] == 1 || exemptFlag[b] == 1)) framePenalty = t.costInsideExempt();
            else if (hasHome && (homeFlag[a] == 0 || homeFlag[b] == 0)) framePenalty = t.costOutsideHome();
            // Only centre crossings are undirected; vertex crossings are charged in the search.
            final int crossings = context.occupancy.centreCrossings(axis, xi + cx0, yi + cy0, nxi + cx0, nyi + cy0);
            final double cost = dirLength[dir] * factor * framePenalty + crossings * t.crossing();
            pool[2 * stepIndex + 1] = cost;
            return cost;
        }

        private List<EndVertex> endVertices(final List<GridEndpoint> endpoints, final boolean square) {
            final List<EndVertex> list = new ArrayList<>();
            for (int i = 0; i < endpoints.size(); i += 1) {
                final int normal = endpoints.get(i)
                    .side().outward;
                final int dockX = toCell(
                    endpoints.get(i)
                        .x())
                    - cx0;
                final int dockY = toCell(
                    endpoints.get(i)
                        .y())
                    - cy0;
                final int[] outwards = straightOnly || square ? new int[] { normal }
                    : new int[] { normal, (normal + 1) % 8, (normal + 7) % 8 };
                for (final int outward : outwards) {
                    final int apronX = dockX + DIR_DX[outward];
                    final int apronY = dockY + DIR_DY[outward];
                    if (!inWindow(apronX, apronY)) continue;
                    final int apron = vertexAt(apronX, apronY);
                    if (blocked[apron] == 1) continue;
                    int clean = apron;
                    double cleanCost = 0;
                    final double cleanCells = outward == normal ? t.cleanCells() : Math.max(2, t.cleanCells());
                    for (int step = 1; step < cleanCells && clean != -1; step += 1) {
                        final int xi = clean / height;
                        final int yi = clean % height;
                        final double cost = priceStep(xi, yi, outward);
                        if (cost == STEP_BLOCKED) clean = -1;
                        else {
                            clean = vertexAt(xi + DIR_DX[outward], yi + DIR_DY[outward]);
                            cleanCost += cost;
                        }
                    }
                    // Never offer a one-cell diagonal stub, even in a tight corridor.
                    if (outward != normal && clean == -1) continue;
                    list.add(
                        new EndVertex(
                            i,
                            apron,
                            clean == apron ? -1 : clean,
                            outward,
                            cleanCost,
                            outward == normal ? 0 : t.turn45()));
                }
            }
            return list;
        }

        /** Cost-free four-connected flood over free steps: reached any wanted, and touched the window border. */
        private boolean[] flood(final Set<Integer> seedVertices, final Set<Integer> wanted) {
            // The wanted vertices, stamped in the goal pool (free until the first leg's search takes it).
            final int wantedGeneration = acquireGoalGeneration(vertexCount);
            final int[] wantedStamp = goalPoolStamp;
            for (final int w : wanted) wantedStamp[w] = wantedGeneration;
            final int generation = acquireFloodGeneration(vertexCount);
            final int[] stamp = floodPoolStamp;
            final int[] queue = floodPoolQueue;
            int head = 0, tail = 0;
            boolean touchedBorder = false;
            for (final int seed : seedVertices) {
                if (stamp[seed] != generation) {
                    stamp[seed] = generation;
                    queue[tail] = seed;
                    tail += 1;
                }
            }
            while (head < tail) {
                final int vertex = queue[head];
                head += 1;
                if (wantedStamp[vertex] == wantedGeneration) return new boolean[] { true, touchedBorder };
                final int xi = vertex / height;
                final int yi = vertex % height;
                if (xi == 0 || yi == 0 || xi == width - 1 || yi == height - 1) touchedBorder = true;
                for (int dir = 0; dir < 8; dir += 2) {
                    if (priceStep(xi, yi, dir) == STEP_BLOCKED) continue;
                    final int next = vertexAt(xi + DIR_DX[dir], yi + DIR_DY[dir]);
                    if (stamp[next] != generation) {
                        stamp[next] = generation;
                        queue[tail] = next;
                        tail += 1;
                    }
                }
            }
            return new boolean[] { false, touchedBorder };
        }

        private boolean landsTooClose(final int startIndex, final LegGoal goal) {
            if (!selfLoop || !goal.landing) return false;
            if (startIndex < 0 || startIndex >= starts.size()) return false;
            final EndVertex start = starts.get(startIndex);
            final int dx = start.apron / height - goal.vertex / height;
            final int dy = start.apron % height - goal.vertex % height;
            return Math.max(Math.abs(dx), Math.abs(dy)) < 1;
        }

        /**
         * Octile distance to the goal set's bounding box, plus one bend when the box is not straight ahead: a lower
         * bound on any path's cost.
         */
        private double heuristic(final int state) {
            final int vertex = state >> 3;
            final int dir = state & 7;
            final int xi = vertex / height;
            final int yi = vertex % height;
            final double dx = xi < goalLeft ? goalLeft - xi : xi > goalRight ? xi - goalRight : 0;
            final double dy = yi < goalTop ? goalTop - yi : yi > goalBottom ? yi - goalBottom : 0;
            final double diagonal = dx < dy ? dx : dy;
            final double straight = (dx > dy ? dx : dy) - diagonal;
            double cost = (straight * BOARD_GRID + diagonal * diagonalLength) * t.costEmpty() * weight;
            if ((dx != 0 || dy != 0) && turnsAway(xi, yi, dir)) cost += minTurn;
            return cost;
        }

        /** The heuristic's bend: whether the goal box is not straight ahead along {@code dir}. */
        private boolean turnsAway(final int xi, final int yi, final int dir) {
            final int ddx = DIR_DX[dir];
            final int ddy = DIR_DY[dir];
            double lo = 0;
            double hi = Double.POSITIVE_INFINITY;
            if (ddx == 0) {
                if (xi < goalLeft || xi > goalRight) hi = -1;
            } else {
                final double a = (goalLeft - xi) / ddx;
                final double b = (goalRight - xi) / ddx;
                lo = Math.max(lo, Math.min(a, b));
                hi = Math.min(hi, Math.max(a, b));
            }
            if (ddy == 0) {
                if (yi < goalTop || yi > goalBottom) hi = -1;
            } else {
                final double a = (goalTop - yi) / ddy;
                final double b = (goalBottom - yi) / ddy;
                lo = Math.max(lo, Math.min(a, b));
                hi = Math.min(hi, Math.max(a, b));
            }
            return hi < lo;
        }

        private int heapSize, seq;
        private double popF, popG;
        private int popState;

        /** The open set's order: (f, g, seq), seq unique, so the pop order is total. */
        private boolean before(final double f, final double g, final int s, final int i) {
            final double fi = searchHeapFG[2 * i];
            if (f != fi) return f < fi;
            final double gi = searchHeapFG[2 * i + 1];
            if (g != gi) return g < gi;
            return s < searchHeapStateSeq[2 * i + 1];
        }

        private void push(final double f, final double g, final int state) {
            ensureSearchHeapCapacity(heapSize + 1);
            final double[] fg = searchHeapFG;
            final int[] ss = searchHeapStateSeq;
            int i = heapSize;
            heapSize += 1;
            final int s = seq += 1;
            while (i > 0) {
                final int parent = (i - 1) >> 1;
                if (!before(f, g, s, parent)) break;
                fg[2 * i] = fg[2 * parent];
                fg[2 * i + 1] = fg[2 * parent + 1];
                ss[2 * i] = ss[2 * parent];
                ss[2 * i + 1] = ss[2 * parent + 1];
                i = parent;
            }
            fg[2 * i] = f;
            fg[2 * i + 1] = g;
            ss[2 * i] = state;
            ss[2 * i + 1] = s;
        }

        private boolean pop() {
            if (heapSize == 0) return false;
            final double[] fg = searchHeapFG;
            final int[] ss = searchHeapStateSeq;
            popF = fg[0];
            popG = fg[1];
            popState = ss[0];
            heapSize -= 1;
            if (heapSize > 0) {
                final double f = fg[2 * heapSize];
                final double g = fg[2 * heapSize + 1];
                final int state = ss[2 * heapSize];
                final int s = ss[2 * heapSize + 1];
                int i = 0;
                for (;;) {
                    int child = i * 2 + 1;
                    if (child >= heapSize) break;
                    final int right = child + 1;
                    if (right < heapSize && before(fg[2 * right], fg[2 * right + 1], ss[2 * right + 1], child))
                        child = right;
                    if (before(f, g, s, child)) break;
                    fg[2 * i] = fg[2 * child];
                    fg[2 * i + 1] = fg[2 * child + 1];
                    ss[2 * i] = ss[2 * child];
                    ss[2 * i + 1] = ss[2 * child + 1];
                    i = child;
                }
                fg[2 * i] = f;
                fg[2 * i + 1] = g;
                ss[2 * i] = state;
                ss[2 * i + 1] = s;
            }
            return true;
        }

        private void touch(final int state, final int generation) {
            if (searchPoolStampG[2 * state] != generation) {
                searchPoolStampG[2 * state] = generation;
                searchPoolStampG[2 * state + 1] = Double.POSITIVE_INFINITY;
                searchPoolLinks[2 * state] = -1;
                searchPoolLinks[2 * state + 1] = -1;
            }
        }

        /**
         * One A* leg over (vertex, heading) states: seeds with starting costs, goals with a required arrival heading
         * (or any) and a finishing penalty. The best (g + penalty) goal wins, not the first popped.
         */
        @Nullable
        private LegResult searchLeg(final List<LegSeed> seeds, final List<LegGoal> goals) {
            workSearches += 1;
            // Goals by vertex, in the order given.
            final int goalGeneration = acquireGoalGeneration(vertexCount);
            final int[] goalStamp = goalPoolStamp, goalIndex = goalPoolIndex;
            final List<List<LegGoal>> goalLists = new ArrayList<>();
            goalLeft = Double.POSITIVE_INFINITY;
            goalRight = Double.NEGATIVE_INFINITY;
            goalTop = Double.POSITIVE_INFINITY;
            goalBottom = Double.NEGATIVE_INFINITY;
            for (final LegGoal goal : goals) {
                if (goalStamp[goal.vertex] != goalGeneration) {
                    goalStamp[goal.vertex] = goalGeneration;
                    goalIndex[goal.vertex] = goalLists.size();
                    goalLists.add(new ArrayList<>());
                }
                goalLists.get(goalIndex[goal.vertex])
                    .add(goal);
                final int xi = goal.vertex / height;
                final int yi = goal.vertex % height;
                if (xi < goalLeft) goalLeft = xi;
                if (xi > goalRight) goalRight = xi;
                if (yi < goalTop) goalTop = yi;
                if (yi > goalBottom) goalBottom = yi;
            }
            minTurn = Math.min(t.turn45(), t.turn90());

            final int generation = acquireSearchGeneration(stateCount);
            // Per state s: g at stampG[2s + 1], came-from at links[2s], start index at links[2s + 1].
            final double[] stampG = searchPoolStampG;
            final int[] links = searchPoolLinks;
            heapSize = 0;
            seq = 0;

            for (final LegSeed seed : seeds) {
                touch(seed.state, generation);
                if (seed.g < stampG[2 * seed.state + 1]) {
                    stampG[2 * seed.state + 1] = seed.g;
                    links[2 * seed.state + 1] = seed.startIndex;
                    push(seed.g + heuristic(seed.state), seed.g, seed.state);
                }
            }
            final LaneOccupancy occupancy = context.occupancy;

            int goalState = -1;
            double goalCost = Double.POSITIVE_INFINITY;
            LegGoal goalHit = null;
            int pops = 0;
            while (pop()) {
                final double currentF = popF;
                final double currentG = popG;
                final int currentState = popState;
                if (currentF >= goalCost) break;
                if (currentG > stampG[2 * currentState + 1] + 1e-9) continue;
                pops += 1;
                workPops += 1;
                if (pops > maxPops) return null;
                final int vertex = currentState >> 3;
                final int currentDir = currentState & 7;
                final int currentStart = links[2 * currentState + 1];
                final List<LegGoal> here = goalStamp[vertex] == goalGeneration ? goalLists.get(goalIndex[vertex]) : null;
                if (here != null) {
                    for (final LegGoal goal : here) {
                        if (goal.dir != -1 && goal.dir != currentDir) continue;
                        if (landsTooClose(currentStart, goal)) continue;
                        final GridEndpoint source = sources.get(starts.get(currentStart).endpointIndex);
                        final GridEndpoint target = targets.get(goal.endpointIndex);
                        double dockCost = goal.landing ? crampedDockCost(request, source, target) : 0;
                        if (dockCost > 0) {
                            final int normal = source.side().outward;
                            final boolean aligned = DIR_DX[normal] == 0 ? source.x() == target.x()
                                : source.y() == target.y();
                            if (aligned && target.side().outward == (normal + 4) % 8) {
                                // Exempt the actual straight path, not just aligned docks.
                                int state = currentState;
                                while (state >= 0 && (state & 7) == normal) state = links[2 * state];
                                if (state < 0) dockCost = 0;
                            }
                        }
                        final double cost = currentG + goal.penalty + dockCost;
                        if (cost < goalCost) {
                            goalCost = cost;
                            goalState = currentState;
                            goalHit = goal;
                        }
                    }
                    // No break: goal vertices are ordinary vertices other routes travel through.
                }

                final int xi = vertex / height;
                final int yi = vertex % height;
                final int cell = occupancy.cellOf(xi + cx0, yi + cy0);
                // Leaving an occupied lane here is half a crossing; joining one is the other half.
                final double leaveWeave = occupancy.occupiedAt(DIR_AXIS[currentDir], cell) ? t.crossing() / 2 : 0;
                boolean inZone = false;
                if (zonePoolStamp[vertex] == zoneGeneration) {
                    final int at = zonePoolIndex[vertex];
                    final int[] variants = zoneVariants[at];
                    for (int k = 0; k < zoneSizes[at]; k++) if (variants[k] == currentStart) {
                        inZone = true;
                        break;
                    }
                }
                final double earlyTurn = inZone ? t.earlyTurn() : 0;
                // No passer and no bend here: every turn crosses nothing.
                final boolean noCorner = occupancy.noCorner(xi + cx0, yi + cy0);
                for (int dir = 0; dir < 8; dir += 1) {
                    if (straightOnly && (dir & 1) == 1) continue;
                    final double turn = turnCosts[currentDir * 8 + dir];
                    if (Double.isNaN(turn)) continue;
                    double stepCost = priceStep(xi, yi, dir);
                    if (stepCost == STEP_BLOCKED) continue;
                    // Enter a diagonal with two checked cells, then extend it one cell at a time.
                    final int steps = (dir & 1) == 1 && dir != currentDir ? 2 : 1;
                    if (steps == 2) {
                        final double secondCost = priceStep(xi + DIR_DX[dir], yi + DIR_DY[dir], dir);
                        if (secondCost == STEP_BLOCKED) continue;
                        stepCost += secondCost;
                    }
                    double weave = 0;
                    if (dir != currentDir) {
                        weave = leaveWeave + earlyTurn;
                        if (occupancy.occupiedAt(DIR_AXIS[dir], cell)) weave += t.crossing() / 2;
                    }
                    final int nextState = (vertexAt(xi + DIR_DX[dir] * steps, yi + DIR_DY[dir] * steps) << 3) | dir;
                    final double cornerCost = (noCorner ? 0
                        : occupancy.cornerCrossings(xi + cx0, yi + cy0, currentDir, dir)) * t.crossing();
                    final double nextG = currentG + stepCost + turn + weave + cornerCost;
                    touch(nextState, generation);
                    if (nextG < stampG[2 * nextState + 1] - 1e-9) {
                        stampG[2 * nextState + 1] = nextG;
                        links[2 * nextState] = currentState;
                        links[2 * nextState + 1] = currentStart;
                        push(nextG + heuristic(nextState), nextG, nextState);
                    }
                }
            }

            if (goalState < 0 || goalHit == null) return null;
            int length = 0;
            for (int state = goalState; state >= 0; state = links[2 * state]) length++;
            final int[] vertices = new int[length];
            int at = length;
            for (int state = goalState; state >= 0; state = links[2 * state]) vertices[--at] = state >> 3;
            return new LegResult(links[2 * goalState + 1], goalState & 7, vertices, goalHit, goalCost);
        }

        @Nullable
        RouteFound route() {
            // A card wired to itself routes with 90 degree turns only.
            selfLoop = request.selfLoop();
            straightOnly = !t.diagonals() || request.distanceCells < t.diagonalDistanceCells() || selfLoop;
            final List<GridPoint> sourceAprons = new ArrayList<>();
            for (final GridEndpoint e : sources) sourceAprons.add(apronPoint(e));
            final List<GridPoint> targetAprons = new ArrayList<>();
            for (final GridEndpoint e : targets) targetAprons.add(apronPoint(e));
            final List<GridPoint> stops = new ArrayList<>();
            for (final GridPoint p : waypoints) stops.add(new GridPoint(snapLine(p.x()), snapLine(p.y())));

            double left = Double.POSITIVE_INFINITY, right = Double.NEGATIVE_INFINITY, top = Double.POSITIVE_INFINITY,
                bottom = Double.NEGATIVE_INFINITY;
            final List<GridPoint> all = new ArrayList<>(sourceAprons);
            all.addAll(targetAprons);
            all.addAll(stops);
            for (final GridPoint point : all) {
                if (point.x() < left) left = point.x();
                if (point.x() > right) right = point.x();
                if (point.y() < top) top = point.y();
                if (point.y() > bottom) bottom = point.y();
            }
            // The window is the ends' own box plus the pad, clamped to the board's extent.
            final CellBounds board = context.board;
            cx0 = Math.max(toCell(left - pad), board.x0);
            final int cx1 = Math.min(toCell(right + pad), board.x0 + board.width - 1);
            cy0 = Math.max(toCell(top - pad), board.y0);
            final int cy1 = Math.min(toCell(bottom + pad), board.y0 + board.height - 1);
            width = cx1 - cx0 + 1;
            height = cy1 - cy0 + 1;
            vertexCount = width * height;
            stateCount = vertexCount * 8;
            maxPops = Math.max(MIN_ASTAR_POPS, stateCount * ASTAR_POPS_PER_STATE);

            // Blocked: strictly inside a margin-inflated card. Exempt/home: on or inside a frame.
            ensureCellPool(vertexCount);
            blocked = cellBlocked;
            exemptFlag = cellExempt;
            homeFlag = cellHome;
            Arrays.fill(blocked, 0, vertexCount, (byte) 0);
            hasExempt = !exemptRects.isEmpty();
            hasHome = !homeRects.isEmpty();
            if (hasExempt) Arrays.fill(exemptFlag, 0, vertexCount, (byte) 0);
            if (hasHome) Arrays.fill(homeFlag, 0, vertexCount, (byte) 0);
            for (final GridObstacle obstacle : obstacles) fillRect(blocked, obstacle, WIRE_NODE_MARGIN, true);
            for (final GridObstacle rect : exemptRects) fillRect(exemptFlag, rect, 0, false);
            for (final GridObstacle rect : homeRects) fillRect(homeFlag, rect, 0, false);

            stepGeneration = acquireStepGeneration(vertexCount * 4);
            zoneGeneration = acquireZoneGeneration(vertexCount);
            zoneCount = 0;

            starts = endVertices(sources, request.request.sourceSquare());
            final List<EndVertex> ends = endVertices(targets, request.request.targetSquare());
            if (starts.isEmpty() || ends.isEmpty()) return null;
            final List<Integer> stopVertices = new ArrayList<>();
            for (final GridPoint stop : stops) {
                final int xi = toCell(stop.x()) - cx0;
                final int yi = toCell(stop.y()) - cy0;
                if (!inWindow(xi, yi)) return null;
                final int vertex = vertexAt(xi, yi);
                if (blocked[vertex] == 1) return null;
                stopVertices.add(vertex);
            }

            // A direct wire is checked for reachability before the A* spends anything on it.
            if (stopVertices.isEmpty()) {
                final Set<Integer> startSet = new LinkedHashSet<>();
                for (final EndVertex start : starts) startSet.add(start.apron);
                final Set<Integer> goalSet = new LinkedHashSet<>();
                for (final EndVertex end : ends) goalSet.add(end.apron);
                final boolean[] fromStarts = flood(startSet, goalSet);
                if (!fromStarts[0]) {
                    if (!fromStarts[1]) return SEALED;
                    final boolean[] fromGoals = flood(goalSet, startSet);
                    return fromGoals[1] ? null : SEALED;
                }
            }

            // Seeds: leaving straight along the port's normal; the clean run is where an early turn costs.
            List<LegSeed> seeds = new ArrayList<>();
            for (int variant = 0; variant < starts.size(); variant++) {
                final EndVertex start = starts.get(variant);
                final int apronX = start.apron / height + cx0;
                final int apronY = start.apron % height + cy0;
                final double stacked = context.occupancy
                    .usedWidth(DIR_AXIS[start.outward], apronX, apronY, apronX, apronY) > 0 ? t.crossing() / 2 : 0;
                final double penalty = sources.get(start.endpointIndex)
                    .penalty() + stacked
                    + start.exitCost;
                final boolean diagonal = (start.outward & 1) == 1;
                seeds.add(
                    new LegSeed(
                        ((diagonal ? start.clean : start.apron) << 3) | start.outward,
                        penalty + (diagonal ? start.cleanCost : 0),
                        variant));
                int zx = start.apron / height;
                int zy = start.apron % height;
                for (int cell = 1; cell < t.cleanCells() && inWindow(zx, zy); cell += 1) {
                    addToZone(vertexAt(zx, zy), variant);
                    zx += DIR_DX[start.outward];
                    zy += DIR_DY[start.outward];
                }
            }

            final List<Integer> mergedVertices = new ArrayList<>();
            int sourceIndex = -1;
            double totalCost = 0;
            for (final int stopVertex : stopVertices) {
                final LegResult leg = searchLeg(
                    seeds,
                    List.of(new LegGoal(stopVertex, -1, 0, 0, new int[0], false)));
                if (leg == null) return null;
                if (sourceIndex < 0) sourceIndex = leg.startIndex;
                takeLeg(mergedVertices, leg);
                totalCost += leg.cost;
                // Keep the arrival direction: the next search prices the actual turn at the dot.
                seeds = List.of(new LegSeed((stopVertex << 3) | leg.arrivalDir, 0, leg.startIndex));
            }

            // Goals: landing straight. The clean point must be reached heading in along the normal; the apron takes
            // any arrival, dearer by the early-turn cost.
            final List<LegGoal> goals = new ArrayList<>();
            for (final EndVertex end : ends) {
                final int apronX = end.apron / height + cx0;
                final int apronY = end.apron % height + cy0;
                final double stacked = context.occupancy
                    .usedWidth(DIR_AXIS[end.outward], apronX, apronY, apronX, apronY) > 0 ? t.crossing() / 2 : 0;
                final double penalty = targets.get(end.endpointIndex)
                    .penalty() + stacked
                    + end.exitCost;
                final boolean diagonal = (end.outward & 1) == 1;
                goals.add(
                    new LegGoal(
                        diagonal ? end.clean : end.apron,
                        -1,
                        penalty + t.earlyTurn() + (diagonal ? end.cleanCost : 0),
                        end.endpointIndex,
                        diagonal ? new int[] { end.apron } : new int[0],
                        true));
                if (end.clean == -1) {
                    // No room for a clean run: arriving straight is still clean.
                    goals.add(
                        new LegGoal(end.apron, (end.outward + 4) % 8, penalty, end.endpointIndex, new int[0], true));
                } else {
                    goals.add(
                        new LegGoal(
                            end.clean,
                            (end.outward + 4) % 8,
                            penalty + end.cleanCost,
                            end.endpointIndex,
                            new int[] { end.apron },
                            true));
                }
            }
            final LegResult finalLeg = searchLeg(seeds, goals);
            if (finalLeg == null) return null;
            if (sourceIndex < 0) sourceIndex = finalLeg.startIndex;
            takeLeg(mergedVertices, finalLeg);
            totalCost += finalLeg.cost;
            for (final int v : finalLeg.goal.tail) mergedVertices.add(v);

            final EndVertex startVariant = sourceIndex >= 0 && sourceIndex < starts.size() ? starts.get(sourceIndex)
                : null;
            if (startVariant != null && (startVariant.outward & 1) == 1) mergedVertices.add(0, startVariant.apron);
            final int sourceAt = startVariant != null ? startVariant.endpointIndex : 0;
            final GridEndpoint source = sourceAt < sources.size() ? sources.get(sourceAt) : sources.get(0);
            final GridEndpoint target = finalLeg.goal.endpointIndex < targets.size()
                ? targets.get(finalLeg.goal.endpointIndex)
                : targets.get(0);
            final List<GridPoint> points = new ArrayList<>(mergedVertices.size());
            for (final int vertex : mergedVertices) points.add(new GridPoint(vertex / height + cx0, vertex % height + cy0));
            return new RouteFound(source, target, compactPoints(points), totalCost);
        }

        /** Adds a start variant to a vertex's clean zone. */
        private void addToZone(final int vertex, final int variant) {
            final int at;
            if (zonePoolStamp[vertex] != zoneGeneration) {
                zonePoolStamp[vertex] = zoneGeneration;
                at = zoneCount++;
                zonePoolIndex[vertex] = at;
                if (at >= zoneSizes.length) {
                    final int capacity = Math.max(8, 2 * zoneSizes.length);
                    zoneSizes = Arrays.copyOf(zoneSizes, capacity);
                    zoneVariants = Arrays.copyOf(zoneVariants, capacity);
                }
                zoneSizes[at] = 0;
                if (zoneVariants[at] == null) zoneVariants[at] = new int[4];
            } else at = zonePoolIndex[vertex];
            if (zoneSizes[at] == zoneVariants[at].length)
                zoneVariants[at] = Arrays.copyOf(zoneVariants[at], 2 * zoneVariants[at].length);
            zoneVariants[at][zoneSizes[at]++] = variant;
        }

        /** A leg after the first starts where the last one ended. */
        private void takeLeg(final List<Integer> merged, final LegResult leg) {
            final boolean continuing = !merged.isEmpty();
            for (int i = continuing ? 1 : 0; i < leg.vertices.length; i++) merged.add(leg.vertices[i]);
        }
    }

    // endregion

    // region Lane claiming and the final polyline

    private record Assembled(List<GridPoint> points, int crossings, boolean overflowed) {}

    /** A straight run of a route: axis, unit direction, ends in cells, and its drawn line's offset in px. */
    private static final class Run {

        final int axis, dx, dy, x0, y0, x1, y1;
        double offset;

        Run(final int axis, final int dx, final int dy, final int x0, final int y0, final int x1, final int y1) {
            this.axis = axis;
            this.dx = dx;
            this.dy = dy;
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
        }
    }

    /** A point on a run's drawn line, from a vertex on its grid line. */
    private static GridPoint drawnPoint(final Run run, final int x, final int y) {
        final double length = JsMath.hypot(run.dx, run.dy);
        final double nx = -run.dy / length;
        final double ny = run.dx / length;
        return new GridPoint(x * (double) BOARD_GRID + nx * run.offset, y * (double) BOARD_GRID + ny * run.offset);
    }

    /** Where two drawn lines meet; null when parallel. */
    @Nullable
    private static GridPoint intersectLines(final GridPoint p, final double ux, final double uy, final GridPoint q,
        final double vx, final double vy) {
        final double denominator = ux * vy - uy * vx;
        if (Math.abs(denominator) < 1e-9) return null;
        final double s = ((q.x() - p.x()) * vy - (q.y() - p.y()) * vx) / denominator;
        return new GridPoint(p.x() + ux * s, p.y() + uy * s);
    }

    private static int dirIndex(final int dx, final int dy) {
        for (int d = 0; d < 8; d++) if (DIR_DX[d] == dx && DIR_DY[d] == dy) return d;
        return -1;
    }

    /**
     * Turns the vertex chain into the drawn polyline: every straight run claims its slice of its lane and shifts
     * sideways to it, corners re-join where neighbouring drawn lines meet, and the true anchors go on the ends.
     */
    private Assembled claimAndAssemble(final SolveContext context, final Planned request, final RouteFound found) {
        final GridEndpoint source = found.source, target = found.target;
        final List<GridPoint> vertices = found.vertices;
        if (vertices.isEmpty()) return new Assembled(List.of(), 0, false);
        final GridPoint firstVertex = vertices.get(0);
        final GridPoint lastVertex = vertices.get(vertices.size() - 1);
        final int fvx = (int) firstVertex.x(), fvy = (int) firstVertex.y();
        final int lvx = (int) lastVertex.x(), lvy = (int) lastVertex.y();
        int sourceDir = dirIndex(
            JsMath.sign(fvx - JsMath.round(source.x() / BOARD_GRID)),
            JsMath.sign(fvy - JsMath.round(source.y() / BOARD_GRID)));
        if (sourceDir < 0) sourceDir = source.side().outward;
        int targetDir = dirIndex(
            JsMath.sign(lvx - JsMath.round(target.x() / BOARD_GRID)),
            JsMath.sign(lvy - JsMath.round(target.y() / BOARD_GRID)));
        if (targetDir < 0) targetDir = target.side().outward;
        final int sourceAxis = DIR_AXIS[sourceDir];
        final int targetAxis = DIR_AXIS[targetDir];
        final GridPoint sourceTip = stubTip(source);
        final GridPoint targetTip = stubTip(target);
        final GridPoint sourceApron = new GridPoint(fvx * (double) BOARD_GRID, fvy * (double) BOARD_GRID);
        final GridPoint targetApron = new GridPoint(lvx * (double) BOARD_GRID, lvy * (double) BOARD_GRID);

        // The stubs pass straight through their apron vertices.
        int crossings = 0;
        crossings += context.occupancy.stepCrossings(sourceAxis, fvx, fvy, fvx, fvy, false);
        crossings += context.occupancy.stepCrossings(targetAxis, lvx, lvy, lvx, lvy, false);
        final String edgeId = request.edgeId();
        context.occupancy.markStub(sourceAxis, fvx, fvy, edgeId);
        context.occupancy.markStub(targetAxis, lvx, lvy, edgeId);

        // No moves at all: the two aprons share a vertex (adjacent ports).
        if (vertices.size() == 1) {
            return new Assembled(
                compactPoints(
                    List.of(
                        sourceTip,
                        new GridPoint(source.x(), source.y()),
                        sourceApron,
                        new GridPoint(target.x(), target.y()),
                        targetTip)),
                crossings,
                false);
        }

        final List<Run> runs = new ArrayList<>();
        for (int i = 0; i + 1 < vertices.size(); i += 1) {
            final int ax = (int) vertices.get(i)
                .x(),
                ay = (int) vertices.get(i)
                    .y();
            final int bx = (int) vertices.get(i + 1)
                .x(),
                by = (int) vertices.get(i + 1)
                    .y();
            final int dx = Integer.signum(bx - ax);
            final int dy = Integer.signum(by - ay);
            final int axis = dy == 0 ? 0 : dx == 0 ? 1 : dx == dy ? 2 : 3;
            runs.add(new Run(axis, dx, dy, ax, ay, bx, by));
        }

        boolean overflowed = false;
        for (int index = 0; index < runs.size(); index++) {
            final Run run = runs.get(index);
            final ClaimedRun claimed = new ClaimedRun(run.axis, run.x0, run.y0, run.x1, run.y1);
            final int steps = Math.max(Math.abs(run.x1 - run.x0), Math.abs(run.y1 - run.y0));
            for (int i = 1; i <= steps; i += 1) {
                crossings += context.occupancy.stepCrossings(
                    run.axis,
                    run.x0 + run.dx * (i - 1),
                    run.y0 + run.dy * (i - 1),
                    run.x0 + run.dx * i,
                    run.y0 + run.dy * i,
                    false);
            }
            final List<LaneClaim> existing = context.occupancy.claimsAlong(claimed);
            final Packed packed = packIntoLane(
                existing,
                request.request.strokeWidth(),
                departureFor(runs, index, sourceTip, sourceApron, targetTip, targetApron),
                run.axis >= 2 ? t.diagonalLaneCapacity() : LANE_CAPACITY);
            if (packed.overflowed) overflowed = true;
            run.offset = (packed.claim.lo + packed.claim.hi) / 2;
            context.occupancy.claim(claimed, packed.claim, edgeId);
            context.occupancy.markRun(claimed, edgeId);
        }

        final List<GridPoint> points = new ArrayList<>();
        points.add(sourceTip);

        for (int i = 1; i < runs.size(); i++) {
            final Run prev = runs.get(i - 1), next = runs.get(i);
            context.occupancy.markCorner(next.x0, next.y0, dirIndex(prev.dx, prev.dy), dirIndex(next.dx, next.dy), edgeId);
        }

        // Source stub onto the first run.
        final Run first = runs.get(0);
        final int outX = DIR_DX[sourceDir];
        final int outY = DIR_DY[sourceDir];
        if (first.dx == outX && first.dy == outY) {
            points.add(sourceApron);
            points.add(drawnPoint(first, first.x0, first.y0));
        } else {
            final GridPoint met = intersectLines(
                new GridPoint(source.x(), source.y()),
                outX,
                outY,
                drawnPoint(first, first.x0, first.y0),
                first.dx,
                first.dy);
            points.add(met != null ? met : drawnPoint(first, first.x0, first.y0));
        }

        // Interior corners: where neighbouring drawn lines meet; a reversal turns at its vertex with a small jog.
        for (int i = 0; i + 1 < runs.size(); i += 1) {
            final Run runA = runs.get(i);
            final Run runB = runs.get(i + 1);
            final GridPoint met = intersectLines(
                drawnPoint(runA, runA.x0, runA.y0),
                runA.dx,
                runA.dy,
                drawnPoint(runB, runB.x0, runB.y0),
                runB.dx,
                runB.dy);
            if (met != null) points.add(met);
            else {
                points.add(drawnPoint(runA, runA.x1, runA.y1));
                points.add(drawnPoint(runB, runB.x0, runB.y0));
            }
        }

        // Target stub off the last run, mirroring the source.
        final Run last = runs.get(runs.size() - 1);
        final int inX = -DIR_DX[targetDir];
        final int inY = -DIR_DY[targetDir];
        if (last.dx == inX && last.dy == inY) {
            points.add(drawnPoint(last, last.x1, last.y1));
            points.add(targetApron);
        } else {
            final GridPoint met = intersectLines(
                new GridPoint(target.x(), target.y()),
                inX,
                inY,
                drawnPoint(last, last.x1, last.y1),
                last.dx,
                last.dy);
            points.add(met != null ? met : drawnPoint(last, last.x1, last.y1));
        }
        points.add(targetTip);

        return new Assembled(compactPoints(points), crossings, overflowed);
    }

    /**
     * Which side of its lane a run sits on: the side of the turn at its end (the last run turns toward its target
     * anchor), else the side it arrived from.
     */
    private static int departureFor(final List<Run> runs, final int index, final GridPoint sourceTip,
        final GridPoint sourceApron, final GridPoint targetTip, final GridPoint targetApron) {
        final Run run = runs.get(index);
        final double nx, ny;
        if (index + 1 < runs.size()) {
            nx = runs.get(index + 1).dx;
            ny = runs.get(index + 1).dy;
        } else {
            nx = targetTip.x() - targetApron.x();
            ny = targetTip.y() - targetApron.y();
        }
        final int departure = JsMath.sign(run.dx * ny - run.dy * nx);
        final double px, py;
        if (index > 0) {
            px = runs.get(index - 1).dx;
            py = runs.get(index - 1).dy;
        } else {
            px = sourceApron.x() - sourceTip.x();
            py = sourceApron.y() - sourceTip.y();
        }
        final int arrival = -JsMath.sign(run.dx * py - run.dy * px);
        if (departure == 0) return arrival;
        return departure;
    }

    /** The drawn anchor: the endpoint pushed {@code stubDepth} further into the card. */
    private static GridPoint stubTip(final GridEndpoint endpoint) {
        final double depth = endpoint.stubDepth();
        if (depth <= 0) return new GridPoint(endpoint.x(), endpoint.y());
        final int dir = endpoint.side().outward;
        return new GridPoint(endpoint.x() - DIR_DX[dir] * depth, endpoint.y() - DIR_DY[dir] * depth);
    }

    /**
     * Drops zero-length and collinear intermediate points, but only where the direction of travel holds: a point
     * where the wire reverses along the same line (a dot's excursion) is a real turnaround.
     */
    public static List<GridPoint> compactPoints(final List<GridPoint> points) {
        final List<GridPoint> kept = new ArrayList<>(points.size());
        for (final GridPoint point : points) {
            if (!kept.isEmpty()) {
                final GridPoint prev = kept.get(kept.size() - 1);
                if (Math.abs(prev.x() - point.x()) < 0.01 && Math.abs(prev.y() - point.y()) < 0.01) continue;
            }
            kept.add(point);
            while (kept.size() >= 3) {
                final GridPoint a = kept.get(kept.size() - 3);
                final GridPoint b = kept.get(kept.size() - 2);
                final GridPoint c = kept.get(kept.size() - 1);
                final double abx = b.x() - a.x();
                final double aby = b.y() - a.y();
                final double bcx = c.x() - b.x();
                final double bcy = c.y() - b.y();
                final boolean collinear = Math.abs(abx * bcy - aby * bcx) < 0.01;
                final boolean sameDirection = abx * bcx + aby * bcy > 0;
                if (!collinear || !sameDirection) break;
                kept.remove(kept.size() - 2);
            }
        }
        return kept;
    }

    // endregion
}
