package com.gtnhplanner.core.layout.arrange;

import static com.gtnhplanner.core.layout.arrange.Proxy.BOARD_GRID;
import static com.gtnhplanner.core.layout.arrange.Proxy.pathBends;
import static com.gtnhplanner.core.layout.arrange.Proxy.pathBlocked;
import static com.gtnhplanner.core.layout.arrange.Proxy.pathCrossings;
import static com.gtnhplanner.core.layout.arrange.Proxy.pathLength;
import static com.gtnhplanner.core.layout.arrange.Proxy.proxyPath;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.ToDoubleFunction;

import javax.annotation.Nullable;

import com.gtnhplanner.core.layout.GridEdgeRouter;
import com.gtnhplanner.core.layout.GridEndpoint;
import com.gtnhplanner.core.layout.GridObstacle;
import com.gtnhplanner.core.layout.GridPoint;
import com.gtnhplanner.core.layout.GridRouteRequest;
import com.gtnhplanner.core.layout.GridRoutedEdge;
import com.gtnhplanner.core.layout.GridSide;
import com.gtnhplanner.core.layout.JsMath;
import com.gtnhplanner.core.layout.RouterTuning;
import com.gtnhplanner.core.layout.WireMetrics;
import com.gtnhplanner.core.layout.arrange.Proxy.Rect;
import com.gtnhplanner.core.layout.arrange.Proxy.Rng;

/**
 * The arranger's proxy score and search, the website's {@code board-arrange-optimize.ts}: takes an island the layered
 * pass has placed and rearranges its cards to lower the score the router's wires would get.
 *
 * <p>
 * Every wire gets a PROXY route shaped like the router's ({@link Proxy}), scored in the router's own points: length,
 * bends at turn45/turn90, crossings at the crossing dial weighing the heavier wire, all times wireWeight, plus a detour
 * estimate for each card a proxy path runs through.
 *
 * <p>
 * The SEARCH keeps the column discipline: a state is the card order per column, each column's vertical offset, the air
 * above each card, and each satellite's slide along its machine's side. A placer derives positions from that, so every
 * trial is tidy. Simulated annealing over swaps, column hops flow allows, moves to a partner's side and offset nudges
 * lowers the proxy; the real router then judges the best few and the fewest points wins.
 *
 * <p>
 * Pure and deterministic: the RNG is seeded from the island's ids. The annealing's temperature uses
 * {@link StrictMath#pow} and {@link StrictMath#exp} (fdlibm, as V8's Math.pow and Math.exp).
 */
public final class Optimize {

    private Optimize() {}

    /** {@code OptimizeCard}: {@code satellite} null when the card rides nothing. */
    public record OptimizeCard(String id, double width, double height, boolean storage, double layer, double seq,
        @Nullable Double section, @Nullable Satellite satellite) {}

    /** A drawer pinned to one machine's side: {@code left} true for "left". */
    public record Satellite(String anchorId, boolean left) {}

    /** {@code OptimizeWire}. */
    public record OptimizeWire(String source, String target, @Nullable Double weight, @Nullable Double width) {}

    /**
     * {@code OptimizeOptions}. The judge has three states as on the website: {@code judgeOff} true is
     * {@code judge: false}; otherwise {@code judge} null is absent (the stand-in routes plain rim docks with the real
     * router).
     */
    public record OptimizeOptions(@Nullable Double rowGapCells, @Nullable Double sectionGapCells,
        @Nullable Double columnGapCells, @Nullable Double satellitePadCells, @Nullable Double trials, boolean judgeOff,
        @Nullable ToDoubleFunction<Map<String, GridPoint>> judge, @Nullable RouterTuning prices,
        @Nullable ToDoubleFunction<Map<String, GridPoint>> air, @Nullable Steps onProgress) {}

    /** {@code OptimizeResult}. */
    public record OptimizeResult(List<GridPoint> positions, double before, double after, @Nullable Double points,
        @Nullable List<Finalist> finalists) {}

    public record Finalist(double score, double proxyCrossings, double points) {}

    /** Per pixel of the layout's bounding box perimeter: tidy is compact. */
    private static final double SPRAWL = 0.4;
    /**
     * Whether the search may hop cards between columns (within what flow allows, see {@code mayStandIn}). Hops toward
     * partners shorten long wires: longest-path ranking spreads a board wide.
     */
    private static final boolean ALLOW_COLUMN_MOVES = true;

    /** {@code optimizeIslandLayout(cards, wires, options)}. */
    public static OptimizeResult optimizeIslandLayout(final List<OptimizeCard> cards, final List<OptimizeWire> wires,
        final OptimizeOptions options) {
        return new Search(cards, wires, options).run();
    }

    /** A wire between two cards of the island, by index. */
    private record Link(int a, int b, double weight, double width) {}

    /** The layout state the search permutes. */
    private static final class State {

        /** Card indices per column, top to bottom. Satellites are not here. */
        List<List<Integer>> columns;
        /** Vertical offset of each column, in cells. */
        double[] columnOffset;
        /** Extra corridor before each column, in cells: how islands part sideways. */
        double[] columnPad;
        /** Extra air above each card, in cells. */
        double[] padBefore;
        /** Satellite: offset of its top from its anchor's top, in cells. */
        double[] satelliteOffset;
    }

    private static final class Candidate {

        double score;
        State state;
        final String key;

        Candidate(final double score, final State state, final String key) {
            this.score = score;
            this.state = state;
            this.key = key;
        }
    }

    private static List<List<Integer>> copyColumns(final List<List<Integer>> columns) {
        final List<List<Integer>> out = new ArrayList<>(columns.size());
        for (final List<Integer> column : columns) out.add(new ArrayList<>(column));
        return out;
    }

    private static boolean sameSection(@Nullable final Double a, @Nullable final Double b) {
        if (a == null || b == null) return a == null && b == null;
        return a.doubleValue() == b.doubleValue();
    }

    /** One call of optimizeIslandLayout: the website's closures share these. */
    private static final class Search {

        private final List<OptimizeCard> cards;
        private final OptimizeOptions options;
        private final int n;
        private final RouterTuning prices;
        private final ToDoubleFunction<Map<String, GridPoint>> air;
        private final List<Link> links = new ArrayList<>();
        private final double rowGap, sectionGap, columnGap, satellitePad;
        private final int[] anchorOf;
        private final Map<Integer, List<Integer>> satellitesOf = new LinkedHashMap<>();
        private final int layerCount;
        private final State state = new State();
        private final double[] posX, posY;
        private final Rect[] rect;
        private final double[][] paths;
        private final List<List<Integer>> linksOf;
        private final double[] lengthOf, bendsOf, blockedOf;
        private final int[] pairCross;
        private int lastProxyCrossings = 0;
        private final double[] previousX, previousY;
        private final List<Candidate> candidates = new ArrayList<>();
        private double current;
        private boolean improved;

        Search(final List<OptimizeCard> cards, final List<OptimizeWire> wires, final OptimizeOptions options) {
            this.cards = cards;
            this.options = options;
            n = cards.size();
            prices = options.prices() != null ? options.prices() : RouterTuning.DEFAULT;
            air = options.air() != null ? options.air() : positions -> 0;
            final Map<String, Integer> index = new HashMap<>();
            for (int i = 0; i < n; i++) index.put(
                cards.get(i)
                    .id(),
                i);
            for (final OptimizeWire wire : wires) {
                final Integer a = index.get(wire.source());
                final Integer b = index.get(wire.target());
                if (a == null || b == null || a.intValue() == b.intValue()) continue;
                links.add(
                    new Link(
                        a,
                        b,
                        // The router's own weight when the width is known; the caller's scale (flow, log-compressed)
                        // otherwise.
                        wire.width() != null ? WireMetrics.wireWeight(wire.width())
                            : Math.max(wire.weight() != null ? wire.weight() : 1, 0.01),
                        wire.width() != null ? wire.width() : 6));
            }
            rowGap = (options.rowGapCells() != null ? options.rowGapCells() : 2) * BOARD_GRID;
            sectionGap = (options.sectionGapCells() != null ? options.sectionGapCells()
                : options.rowGapCells() != null ? options.rowGapCells() : 2) * BOARD_GRID;
            columnGap = (options.columnGapCells() != null ? options.columnGapCells() : 3) * BOARD_GRID;
            satellitePad = (options.satellitePadCells() != null ? options.satellitePadCells() : 2) * BOARD_GRID;

            // Satellites hang off anchors; everything else lives in the columns.
            anchorOf = new int[n];
            Arrays.fill(anchorOf, -1);
            for (int i = 0; i < n; i++) {
                final OptimizeCard card = cards.get(i);
                if (card.satellite() == null) continue;
                final Integer anchor = index.get(
                    card.satellite()
                        .anchorId());
                if (anchor == null || cards.get(anchor)
                    .satellite() != null) continue;
                anchorOf[i] = anchor;
                satellitesOf.computeIfAbsent(anchor, k -> new ArrayList<>())
                    .add(i);
            }

            double maxLayer = 0;
            for (final OptimizeCard card : cards) maxLayer = Math.max(maxLayer, card.layer());
            layerCount = (int) (maxLayer + 1);
            state.columns = new ArrayList<>(layerCount);
            for (int layer = 0; layer < layerCount; layer++) state.columns.add(new ArrayList<>());
            state.columnOffset = new double[layerCount];
            state.columnPad = new double[layerCount];
            state.padBefore = new double[n];
            state.satelliteOffset = new double[n];
            for (int i = 0; i < n; i++) {
                if (anchorOf[i] < 0) state.columns.get((int) cards.get(i)
                    .layer())
                    .add(i);
            }
            for (final List<Integer> column : state.columns) column.sort(this::bySeq);
            // Satellites start stacked in seq order along their side.
            for (final List<Integer> sats : satellitesOf.values()) {
                sats.sort(this::bySeq);
                double offset = 0;
                for (final int sat : sats) {
                    state.satelliteOffset[sat] = offset;
                    offset += Math.ceil(
                        cards.get(sat)
                            .height() / BOARD_GRID)
                        + 1;
                }
            }

            posX = new double[n];
            posY = new double[n];
            rect = new Rect[n];
            for (int i = 0; i < n; i++) rect[i] = new Rect(0, 0, 0, 0);

            paths = new double[links.size()][];
            for (int l = 0; l < links.size(); l++) paths[l] = new double[0];
            linksOf = new ArrayList<>(n);
            for (int i = 0; i < n; i++) linksOf.add(new ArrayList<>());
            for (int l = 0; l < links.size(); l++) {
                linksOf.get(
                    links.get(l)
                        .a())
                    .add(l);
                linksOf.get(
                    links.get(l)
                        .b())
                    .add(l);
            }
            lengthOf = new double[links.size()];
            bendsOf = new double[links.size()];
            blockedOf = new double[links.size()];
            pairCross = new int[links.size() * links.size()];
            previousX = new double[n];
            previousY = new double[n];
            Arrays.fill(previousX, Double.NaN);
            Arrays.fill(previousY, Double.NaN);
        }

        private int bySeq(final int a, final int b) {
            final int c = JsMath.sign(
                cards.get(a)
                    .seq()
                    - cards.get(b)
                        .seq());
            return c != 0 ? c : JsMath.sign(a - b);
        }

        private double width(final int i) {
            return cards.get(i)
                .width();
        }

        private double height(final int i) {
            return cards.get(i)
                .height();
        }

        private boolean leftSide(final int sat) {
            return cards.get(sat)
                .satellite()
                .left();
        }

        private List<Integer> satellites(final int i) {
            final List<Integer> list = satellitesOf.get(i);
            return list != null ? list : List.of();
        }

        private static double snap(final double value) {
            return JsMath.round(value / BOARD_GRID) * BOARD_GRID;
        }

        /** Derives every card's top-left from the state. */
        private void place() {
            // Column widths include the satellites riding on either side.
            final double[] leftPad = new double[layerCount];
            final double[] rightPad = new double[layerCount];
            final double[] widths = new double[layerCount];
            for (int layer = 0; layer < layerCount; layer++) {
                for (final int i : state.columns.get(layer)) {
                    widths[layer] = Math.max(widths[layer], width(i));
                    for (final int sat : satellites(i)) {
                        final double need = width(sat) + satellitePad;
                        if (leftSide(sat)) leftPad[layer] = Math.max(leftPad[layer], need);
                        else rightPad[layer] = Math.max(rightPad[layer], need);
                    }
                }
            }
            double x = 0;
            final double[] columnX = new double[layerCount];
            for (int layer = 0; layer < layerCount; layer += 1) {
                x += leftPad[layer] + state.columnPad[layer] * BOARD_GRID;
                columnX[layer] = x;
                x += widths[layer] + rightPad[layer] + columnGap;
            }
            for (int layer = 0; layer < layerCount; layer++) {
                double y = state.columnOffset[layer] * BOARD_GRID;
                int previous = -1;
                for (final int i : state.columns.get(layer)) {
                    if (previous >= 0 && !sameSection(
                        cards.get(previous)
                            .section(),
                        cards.get(i)
                            .section())) {
                        y += sectionGap - rowGap;
                    }
                    previous = i;
                    y += state.padBefore[i] * BOARD_GRID;
                    // A machine with satellites on a side needs room above for the ones that ride higher than it.
                    double rise = 0;
                    for (final int sat : satellites(i)) {
                        rise = Math.max(rise, -state.satelliteOffset[sat] * BOARD_GRID);
                    }
                    y += rise;
                    posX[i] = snap(columnX[layer] + (widths[layer] - width(i)) / 2);
                    posY[i] = snap(y);
                    double bottom = y + height(i);
                    for (final int sat : satellites(i)) {
                        posX[sat] = snap(leftSide(sat) ? posX[i] - satellitePad - width(sat) : posX[i] + width(i) + satellitePad);
                        posY[sat] = snap(y + state.satelliteOffset[sat] * BOARD_GRID);
                        bottom = Math.max(bottom, posY[sat] + height(sat));
                    }
                    y = bottom + rowGap;
                }
            }
            for (int i = 0; i < n; i += 1) {
                rect[i].left = posX[i];
                rect[i].top = posY[i];
                rect[i].right = posX[i] + width(i);
                rect[i].bottom = posY[i] + height(i);
            }
        }

        /** Satellites on one side must not overlap each other. */
        private boolean satellitesLegal(final int anchor) {
            final List<Integer> sats = satellites(anchor);
            for (int i = 0; i < sats.size(); i += 1) {
                for (int k = i + 1; k < sats.size(); k += 1) {
                    final int a = sats.get(i);
                    final int b = sats.get(k);
                    if (leftSide(a) != leftSide(b)) continue;
                    final double aTop = state.satelliteOffset[a];
                    final double aBottom = aTop + Math.ceil(height(a) / BOARD_GRID);
                    final double bTop = state.satelliteOffset[b];
                    final double bBottom = bTop + Math.ceil(height(b) / BOARD_GRID);
                    if (aTop < bBottom + 1 && bTop < aBottom + 1) return false;
                }
            }
            return true;
        }

        // Scoring, incremental: paths, lengths, bends, blocks and the pairwise crossing matrix are kept, and a trial
        // re-scores only what its moved cards touched. The stranger and sprawl terms are cheap and global.

        private void scoreLink(final int l) {
            final Link link = links.get(l);
            paths[l] = proxyPath(rect[link.a()], rect[link.b()]);
            lengthOf[l] = pathLength(paths[l]) * link.weight();
            bendsOf[l] = pathBends(paths[l], prices) * link.weight();
            blockedOf[l] = pathBlocked(paths[l], rect, link.a(), link.b(), prices) * link.weight();
        }

        private void crossRow(final int l) {
            final int size = links.size();
            for (int m = 0; m < size; m += 1) {
                if (m == l) continue;
                // A Uint8Array cell on the website.
                final int c = pathCrossings(paths[l], paths[m]) & 0xff;
                pairCross[l * size + m] = c;
                pairCross[m * size + l] = c;
            }
        }

        private double globalTerms() {
            double sum = 0;
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < n; i += 1) {
                minX = Math.min(minX, rect[i].left);
                minY = Math.min(minY, rect[i].top);
                maxX = Math.max(maxX, rect[i].right);
                maxY = Math.max(maxY, rect[i].bottom);
            }
            sum += (maxX - minX + (maxY - minY)) * SPRAWL;
            // The air owed between strangers: what makes islands (Air).
            sum += air.applyAsDouble(positionMap());
            return sum;
        }

        private Map<String, GridPoint> positionMap() {
            final Map<String, GridPoint> out = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) out.put(
                cards.get(i)
                    .id(),
                new GridPoint(posX[i], posY[i]));
            return out;
        }

        private double total() {
            double sum = globalTerms();
            int crossings = 0;
            final int size = links.size();
            for (int l = 0; l < size; l += 1) {
                sum += lengthOf[l] + bendsOf[l] + blockedOf[l];
                for (int m = l + 1; m < size; m += 1) {
                    final int count = pairCross[l * size + m];
                    if (count == 0) continue;
                    crossings += count;
                    // A crossing weighs the heavier of its two wires, as in the points.
                    sum += count * prices.crossing() * Math.max(
                        links.get(l)
                            .weight(),
                        links.get(m)
                            .weight());
                }
            }
            lastProxyCrossings = crossings;
            return sum;
        }

        /** Scores the current state; only what moved since the last call is redone. */
        private double score() {
            place();
            final List<Integer> moved = new ArrayList<>();
            for (int i = 0; i < n; i += 1) {
                if (posX[i] != previousX[i] || posY[i] != previousY[i]) {
                    moved.add(i);
                    previousX[i] = posX[i];
                    previousY[i] = posY[i];
                }
            }
            if (moved.isEmpty()) {
                return total();
            }
            final Set<Integer> touched = new LinkedHashSet<>();
            for (final int i : moved) {
                touched.addAll(linksOf.get(i));
            }
            for (final int l : touched) {
                scoreLink(l);
            }
            for (final int l : touched) {
                crossRow(l);
            }
            // A moved card may block, or unblock, wires that never touch it.
            if (moved.size() < n) {
                for (int l = 0; l < links.size(); l += 1) {
                    if (!touched.contains(l)) {
                        final Link link = links.get(l);
                        blockedOf[l] = pathBlocked(paths[l], rect, link.a(), link.b(), prices) * link.weight();
                    }
                }
            }
            return total();
        }

        private State snapshot() {
            final State saved = new State();
            saved.columns = copyColumns(state.columns);
            saved.columnOffset = state.columnOffset.clone();
            saved.columnPad = state.columnPad.clone();
            saved.padBefore = state.padBefore.clone();
            saved.satelliteOffset = state.satelliteOffset.clone();
            return saved;
        }

        private void restore(final State saved) {
            state.columns = copyColumns(saved.columns);
            state.columnOffset = saved.columnOffset.clone();
            state.columnPad = saved.columnPad.clone();
            state.padBefore = saved.padBefore.clone();
            state.satelliteOffset = saved.satelliteOffset.clone();
        }

        private List<GridPoint> normalise() {
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                minX = Math.min(minX, posX[i]);
                minY = Math.min(minY, posY[i]);
            }
            final List<GridPoint> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) out.add(new GridPoint(posX[i] - minX, posY[i] - minY));
            return out;
        }

        /**
         * Finalists are STRUCTURALLY distinct: one per column arrangement (which card stands in which column, in what
         * order), the best-scoring state of each. Six near-copies of one layout differing by a nudge told the router
         * nothing; six different layouts give it a real choice.
         */
        private String structure() {
            final StringBuilder s = new StringBuilder();
            for (int layer = 0; layer < state.columns.size(); layer++) {
                if (layer > 0) s.append('|');
                final List<Integer> column = state.columns.get(layer);
                for (int k = 0; k < column.size(); k++) {
                    if (k > 0) s.append(',');
                    s.append(column.get(k)
                        .intValue());
                }
            }
            return s.toString();
        }

        private void sortCandidates() {
            candidates.sort((a, b) -> JsMath.sign(a.score - b.score));
        }

        private void remember(final double value) {
            final String key = structure();
            Candidate known = null;
            for (final Candidate c : candidates) {
                if (c.key.equals(key)) {
                    known = c;
                    break;
                }
            }
            if (known != null) {
                if (value < known.score - 1e-9) {
                    known.score = value;
                    known.state = snapshot();
                    sortCandidates();
                }
                return;
            }
            candidates.add(new Candidate(value, snapshot(), key));
            sortCandidates();
            if (candidates.size() > Math.max(1, prices.finalists())) candidates.remove(candidates.size() - 1);
        }

        /** Which column a card (or, for a satellite, its anchor) stands in now; -1 for none. */
        private int layerOfCard(final int i) {
            final int who = anchorOf[i] >= 0 ? anchorOf[i] : i;
            for (int layer = 0; layer < layerCount; layer += 1) {
                if (state.columns.get(layer)
                    .contains(who)) return layer;
            }
            return -1;
        }

        /**
         * May {@code card}, standing in {@code from}, stand in column {@code to}? A machine keeps flow reading left to
         * right: every feeder in an earlier column, every taker in a later one (a wire already running backwards does
         * not constrain). A DRAWER may stand anywhere between its first and last partner's column, inclusive, so it can
         * sit between two machines stacked in one column.
         */
        private boolean mayStandIn(final int card, final int from, final int to) {
            if (cards.get(card)
                .storage()) {
                double lo = Double.POSITIVE_INFINITY;
                double hi = Double.NEGATIVE_INFINITY;
                for (final int l : linksOf.get(card)) {
                    final Link link = links.get(l);
                    final int other = link.a() == card ? link.b() : link.a();
                    final int otherLayer = layerOfCard(other);
                    if (otherLayer < 0) continue;
                    lo = Math.min(lo, otherLayer);
                    hi = Math.max(hi, otherLayer);
                }
                return lo == Double.POSITIVE_INFINITY || (to >= lo && to <= hi);
            }
            for (final int l : linksOf.get(card)) {
                final Link link = links.get(l);
                final int other = link.a() == card ? link.b() : link.a();
                final int otherLayer = layerOfCard(other);
                if (otherLayer < 0) continue;
                // A machine may share a column with a drawer it trades with but never pass beyond it; it may not even
                // draw level with a partner machine.
                final int reach = cards.get(other)
                    .storage() ? 0 : 1;
                if (link.a() == card) {
                    if (otherLayer > from && otherLayer < to + reach) return false;
                } else if (otherLayer < from && otherLayer > to - reach) {
                    return false;
                }
            }
            return true;
        }

        private int columnOfCard(final int card) {
            for (int layer = 0; layer < layerCount; layer += 1) {
                if (state.columns.get(layer)
                    .contains(card)) return layer;
            }
            return -1;
        }

        private void attempt(final BooleanSupplier apply) {
            final State saved = snapshot();
            if (!apply.getAsBoolean()) {
                restore(saved);
                return;
            }
            final double next = score();
            if (next < current - 1e-9) {
                current = next;
                improved = true;
            } else {
                restore(saved);
            }
        }

        private static final int[] POLISH_STEPS = { -1, 1, -2, 2, -4, 4 };
        private static final int[] SATELLITE_STEPS = { -1, 1, -2, 2 };

        /**
         * The finisher: from the best state, every single-step move that helps is taken until none does. Annealing gets
         * close; this lands it, and it is what makes tight spacing come out tight every time.
         */
        private void polish(final List<Integer> columnCards) {
            for (int round = 0; round < 40; round += 1) {
                improved = false;
                for (int layer = 0; layer < layerCount; layer += 1) {
                    final int at = layer;
                    for (final int step : POLISH_STEPS) {
                        attempt(() -> {
                            state.columnOffset[at] += step;
                            return true;
                        });
                        attempt(() -> {
                            final double next = state.columnPad[at] + step;
                            if (next < 0) return false;
                            state.columnPad[at] = next;
                            return true;
                        });
                    }
                    // Read once: after a refused swap restores the state, the swaps left in this column act on this
                    // detached list, as the website's closures do.
                    final List<Integer> column = state.columns.get(layer);
                    for (int i = 0; i + 1 < column.size(); i += 1) {
                        final int k = i;
                        attempt(() -> {
                            final Integer tmp = column.get(k);
                            column.set(k, column.get(k + 1));
                            column.set(k + 1, tmp);
                            return true;
                        });
                    }
                }
                for (final int i : columnCards) {
                    for (final int step : POLISH_STEPS) {
                        attempt(() -> {
                            final double next = state.padBefore[i] + step;
                            if (next < 0) return false;
                            state.padBefore[i] = next;
                            return true;
                        });
                    }
                }
                for (int i = 0; i < n; i += 1) {
                    if (anchorOf[i] < 0) continue;
                    final int sat = i;
                    for (final int step : SATELLITE_STEPS) {
                        attempt(() -> {
                            state.satelliteOffset[sat] += step;
                            return satellitesLegal(anchorOf[sat]);
                        });
                    }
                }
                if (!improved) break;
            }
        }

        OptimizeResult run() {
            current = score();
            final double before = current;
            if (n < 2 || links.isEmpty()) {
                return new OptimizeResult(normalise(), before, before, null, null);
            }
            double best = current;
            State bestState = snapshot();
            candidates.add(new Candidate(current, snapshot(), structure()));

            final List<String> ids = new ArrayList<>(n);
            for (final OptimizeCard card : cards) ids.add(card.id());
            final Rng random = new Rng(Proxy.hashIds(ids));
            final double trials = options.trials() != null ? options.trials()
                : Math.min(prices.searchTrials(), 600 * n + 3000);
            // Starts at half the ROUTER's crossing price, not the arrange's dearer one: the search's reach was tuned
            // there, and a hotter start wanders off good layouts it had found.
            final double startTemperature = RouterTuning.DEFAULT.crossing() / 2;
            final double endTemperature = 4;
            final List<Integer> columnCards = new ArrayList<>();
            for (final List<Integer> column : state.columns) columnCards.addAll(column);
            if (columnCards.isEmpty()) {
                return new OptimizeResult(normalise(), before, before, null, null);
            }

            for (int trial = 0; trial < trials; trial += 1) {
                if (options.onProgress() != null && trial % 250 == 0) {
                    options.onProgress()
                        .at(trial, trials);
                }
                final double temperature = startTemperature
                    * StrictMath.pow(endTemperature / startTemperature, trial / trials);
                final State saved = snapshot();
                final double kind = random.next();
                boolean legal = true;
                if (kind < 0.25) {
                    // Swap two cards in one column.
                    final int layer = (int) Math.floor(random.next() * layerCount);
                    final List<Integer> column = state.columns.get(layer);
                    if (column.size() < 2) continue;
                    final int i = (int) Math.floor(random.next() * column.size());
                    int k = (int) Math.floor(random.next() * column.size());
                    if (k == i) k = (i + 1) % column.size();
                    final Integer tmp = column.get(i);
                    column.set(i, column.get(k));
                    column.set(k, tmp);
                } else if (kind < 0.4 && ALLOW_COLUMN_MOVES) {
                    // Move a card into any column flow allows.
                    final int layer = (int) Math.floor(random.next() * layerCount);
                    final List<Integer> column = state.columns.get(layer);
                    if (column.isEmpty()) continue;
                    final int i = (int) Math.floor(random.next() * column.size());
                    final int to = (int) Math.floor(random.next() * layerCount);
                    if (to == layer) continue;
                    final int card = column.get(i);
                    if (!mayStandIn(card, layer, to)) continue;
                    column.remove(i);
                    final List<Integer> target = state.columns.get(to);
                    final int at = (int) Math.floor(random.next() * (target.size() + 1));
                    target.add(at, card);
                } else if (kind < 0.5) {
                    // Set a card down beside one of its partners: the partner's column, right above or below it.
                    final int card = columnCards.get((int) Math.floor(random.next() * columnCards.size()));
                    final List<Integer> own = linksOf.get(card);
                    if (own.isEmpty()) continue;
                    final Link link = links.get(own.get((int) Math.floor(random.next() * own.size())));
                    final int partner = link.a() == card ? link.b() : link.a();
                    final int who = anchorOf[partner] >= 0 ? anchorOf[partner] : partner;
                    if (who == card) continue;
                    final int from = columnOfCard(card);
                    final int to = columnOfCard(who);
                    if (from < 0 || to < 0) continue;
                    if (to != from && !mayStandIn(card, from, to)) continue;
                    final List<Integer> source = state.columns.get(from);
                    source.remove(source.indexOf(card));
                    final List<Integer> target = state.columns.get(to);
                    final int at = target.indexOf(who) + (random.next() < 0.5 ? 0 : 1);
                    target.add(at, card);
                } else if (kind < 0.62) {
                    // Nudge a column up or down.
                    final int layer = (int) Math.floor(random.next() * layerCount);
                    final double reach = 1 + Math.floor(random.next() * 6);
                    state.columnOffset[layer] += random.next() < 0.5 ? -reach : reach;
                } else if (kind < 0.7) {
                    // Widen or narrow the corridor before a column: islands part sideways.
                    final int layer = (int) Math.floor(random.next() * layerCount);
                    final double reach = 1 + Math.floor(random.next() * 4);
                    state.columnPad[layer] = Math
                        .max(0, state.columnPad[layer] + (random.next() < 0.5 ? -reach : reach));
                } else if (kind < 0.76) {
                    // Shift a card AND its direct partners down (or up) together: a cluster moves as one instead of
                    // one card at a time.
                    final int card = columnCards.get((int) Math.floor(random.next() * columnCards.size()));
                    final Set<Integer> group = new LinkedHashSet<>();
                    group.add(card);
                    for (final int l : linksOf.get(card)) {
                        final Link link = links.get(l);
                        final int other = link.a() == card ? link.b() : link.a();
                        group.add(anchorOf[other] >= 0 ? anchorOf[other] : other);
                    }
                    final double magnitude = 1 + Math.floor(random.next() * 4);
                    final double step = magnitude * (random.next() < 0.5 ? -1 : 1);
                    for (final int member : group) {
                        if (anchorOf[member] >= 0) continue;
                        state.padBefore[member] = Math.max(0, state.padBefore[member] + step);
                    }
                } else if (kind < 0.88) {
                    // More or less air above a card.
                    final int i = columnCards.get((int) Math.floor(random.next() * columnCards.size()));
                    final double reach = 1 + Math.floor(random.next() * 4);
                    state.padBefore[i] = Math.max(0, state.padBefore[i] + (random.next() < 0.5 ? -reach : reach));
                } else {
                    // Slide a satellite along its machine's side.
                    final List<Integer> sats = new ArrayList<>();
                    for (int i = 0; i < n; i++) if (anchorOf[i] >= 0) sats.add(i);
                    if (sats.isEmpty()) continue;
                    final int sat = sats.get((int) Math.floor(random.next() * sats.size()));
                    final int anchor = anchorOf[sat];
                    final double reach = 1 + Math.floor(random.next() * 4);
                    final double limit = Math.ceil(height(anchor) / BOARD_GRID);
                    state.satelliteOffset[sat] = Math.max(
                        -2,
                        Math.min(limit, state.satelliteOffset[sat] + (random.next() < 0.5 ? -reach : reach)));
                    legal = satellitesLegal(anchor);
                }
                if (!legal) {
                    restore(saved);
                    continue;
                }
                final double next = score();
                final double delta = next - current;
                if (delta <= 0 || random.next() < StrictMath.exp(-delta / temperature)) {
                    current = next;
                    if (current < best - 1e-9) {
                        best = current;
                        bestState = snapshot();
                    }
                    remember(current);
                } else {
                    restore(saved);
                }
            }

            restore(bestState);
            current = score();
            polish(columnCards);
            if (current < best - 1e-9) {
                best = current;
                bestState = snapshot();
                remember(current);
            }

            // The router judges the finalists: fewest real points wins.
            State winner = bestState;
            Double points = null;
            final List<Finalist> finalists = new ArrayList<>();
            if (!options.judgeOff() && links.size() <= 120) {
                double bestReal = Double.POSITIVE_INFINITY;
                double bestScore = Double.POSITIVE_INFINITY;
                for (final Candidate candidate : candidates) {
                    restore(candidate.state);
                    score();
                    final double real = options.judge() != null ? options.judge()
                        .applyAsDouble(positionMap()) : judgeWithRouter(cards, links, posX, posY, prices) + air.applyAsDouble(positionMap());
                    finalists.add(new Finalist(candidate.score, lastProxyCrossings, real));
                    if (real < bestReal || (real == bestReal && candidate.score < bestScore)) {
                        bestReal = real;
                        bestScore = candidate.score;
                        winner = candidate.state;
                    }
                }
                points = bestReal;
            }
            restore(winner);
            place();
            return new OptimizeResult(normalise(), before, best, points, finalists);
        }
    }

    /**
     * {@code scoreLayoutProxy(cards, wires, positions, prices, air)}: the proxy score of a finished layout - the same
     * wire terms the search uses, plus the air - for choosing between layouts when no router judge is at hand.
     * {@code air} null when absent.
     */
    public static double scoreLayoutProxy(final List<ArrangeCard> cards, final List<OptimizeWire> wires,
        final Map<String, GridPoint> positions, final RouterTuning prices,
        @Nullable final ToDoubleFunction<Map<String, GridPoint>> air) {
        final Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < cards.size(); i++) index.put(
            cards.get(i)
                .id(),
            i);
        final Rect[] rects = new Rect[cards.size()];
        for (int i = 0; i < cards.size(); i++) {
            final ArrangeCard card = cards.get(i);
            final GridPoint at = positions.get(card.id());
            final GridPoint p = at != null ? at : new GridPoint(0, 0);
            rects[i] = new Rect(p.x(), p.y(), p.x() + card.width(), p.y() + card.height());
        }
        final List<double[]> paths = new ArrayList<>();
        final List<Double> weights = new ArrayList<>();
        double sum = 0;
        for (final OptimizeWire wire : wires) {
            final Integer a = index.get(wire.source());
            final Integer b = index.get(wire.target());
            if (a == null || b == null || a.intValue() == b.intValue()) continue;
            final double[] path = proxyPath(rects[a], rects[b]);
            final double weight = wire.width() != null ? WireMetrics.wireWeight(wire.width())
                : Math.max(wire.weight() != null ? wire.weight() : 1, 0.01);
            paths.add(path);
            weights.add(weight);
            sum += (pathLength(path) + pathBends(path, prices) + pathBlocked(path, rects, a, b, prices)) * weight;
        }
        for (int i = 0; i < paths.size(); i += 1) {
            for (int j = i + 1; j < paths.size(); j += 1) {
                sum += pathCrossings(paths.get(i), paths.get(j)) * prices.crossing()
                    * Math.max(weights.get(i), weights.get(j));
            }
        }
        return sum + (air != null ? air.applyAsDouble(positions) : 0);
    }

    /** Rim docks the way the board offers them in free-dock mode. */
    private static List<GridEndpoint> rim(final GridObstacle rect) {
        final List<GridEndpoint> out = new ArrayList<>();
        final double kx = keepOut(rect.right() - rect.left());
        final double ky = keepOut(rect.bottom() - rect.top());
        for (double x = rect.left() + kx; x <= rect.right() - kx; x += BOARD_GRID) {
            out.add(new GridEndpoint(x, rect.top(), GridSide.TOP));
            out.add(new GridEndpoint(x, rect.bottom(), GridSide.BOTTOM));
        }
        for (double y = rect.top() + ky; y <= rect.bottom() - ky; y += BOARD_GRID) {
            out.add(new GridEndpoint(rect.left(), y, GridSide.LEFT));
            out.add(new GridEndpoint(rect.right(), y, GridSide.RIGHT));
        }
        return out;
    }

    private static double keepOut(final double span) {
        return span >= 2 * BOARD_GRID ? BOARD_GRID : 0;
    }

    /** Runs the real router over a candidate and scores its points. */
    private static double judgeWithRouter(final List<OptimizeCard> cards, final List<Link> links, final double[] posX,
        final double[] posY, final RouterTuning prices) {
        final List<GridObstacle> obstacles = new ArrayList<>(cards.size());
        for (int i = 0; i < cards.size(); i++) {
            final OptimizeCard card = cards.get(i);
            obstacles.add(new GridObstacle(card.id(), posX[i], posY[i], posX[i] + card.width(), posY[i] + card.height()));
        }
        final List<GridRouteRequest> requests = new ArrayList<>(links.size());
        for (int i = 0; i < links.size(); i++) {
            final Link link = links.get(i);
            requests.add(
                new GridRouteRequest(
                    "w" + i,
                    i,
                    rim(obstacles.get(link.a())),
                    rim(obstacles.get(link.b())),
                    cards.get(link.a())
                        .id(),
                    cards.get(link.b())
                        .id(),
                    link.width()));
        }
        final Map<String, GridRoutedEdge> solved = new GridEdgeRouter().solve(obstacles, requests, null, prices);
        return WireMetrics.points(WireMetrics.measureRoutes(solved.values()), prices);
    }
}
