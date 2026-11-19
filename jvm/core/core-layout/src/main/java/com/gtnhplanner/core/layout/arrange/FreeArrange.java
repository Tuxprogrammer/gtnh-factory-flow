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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleConsumer;
import java.util.function.IntPredicate;
import java.util.function.ToDoubleFunction;

import javax.annotation.Nullable;

import com.gtnhplanner.core.layout.JsSort;
import com.gtnhplanner.core.layout.GridPoint;
import com.gtnhplanner.core.layout.JsMath;
import com.gtnhplanner.core.layout.RouterTuning;
import com.gtnhplanner.core.layout.WireMetrics;
import com.gtnhplanner.core.layout.arrange.Proxy.Rect;
import com.gtnhplanner.core.layout.arrange.Proxy.Rng;

/**
 * FREE PLACEMENT, the website's {@code board-arrange-free.ts}: the arranger's third candidate, with no layers or bands.
 * Deterministic, in three stages:
 *
 * <ol>
 * <li>STRESS. Every pair of cards gets an ideal distance (shortest wire path, each hop worth the two cards' sizes plus
 * a gap), and stress SGD (Zheng, Pawar and Goodman, 2018) finds continuous positions matching them: partners touch,
 * cards ten hops apart stand ten cards apart.
 * <li>THE GRID. Overlaps are pushed apart along least penetration, then cards are set on the cell grid one at a time,
 * nearest the centre first, each on the free spot closest to its stress position.
 * <li>THE SEARCH. Simulated annealing over free moves (beside a partner with port rows aligned, nudge, swap, a machine
 * with its drawers, a whole side of a bridge wire), scored in the router's points ({@link Proxy}) plus stranger air
 * ({@link Air}) and the FREE_DIALS terms. Scoring is incremental: a trial re-prices only wires the moved cards touch or
 * cross.
 * </ol>
 *
 * <p>
 * MACHINES STAND IN COLUMNS at a pitch of one machine width plus a drawer corridor; rows are any cell. The search runs
 * twice, like a chip placer: free to any cell first (finds the structure), then machines are legalised onto columns and
 * a cooler search repairs the damage.
 *
 * <p>
 * DRAWERS ARE PLACED BY PATTERN, NOT SEARCHED: a drawer wired to ONE machine is its bud and stands in a touching line
 * on its side (supplies left, products right, port order, centred on the ports); a drawer wired to exactly TWO machines
 * stands in a line between them (the corridor when side by side, a row in the gap when stacked). The search moves
 * machines (and drawers with three or more partners) and the lines follow.
 *
 * <p>
 * Wired components are laid out one at a time and packed side by side; unwired cards go on a shelf underneath.
 *
 * <p>
 * The website keeps the gap and beside cells in module variables set per call; here they are fields of the call's own
 * object. The circle start uses {@link StrictMath#cos}/{@link StrictMath#sin} and the schedules {@link StrictMath#exp}
 * and {@link StrictMath#log} (fdlibm, as V8's).
 */
public final class FreeArrange {

    /** Least air between any two cards, in cells; set per call from the taste. */
    private final double gapCells;
    /** Air a card keeps from the partner it is set beside, in cells (columns). */
    private final double besideCells;

    private FreeArrange(final double gapCells, final double besideCells) {
        this.gapCells = gapCells;
        this.besideCells = besideCells;
    }

    /** {@code FreeOptions}: null fields are absent. */
    public record FreeOptions(RouterTuning prices, @Nullable Double trials, @Nullable Double gapCells,
        @Nullable Double besideCells, @Nullable Steps onProgress) {}

    /** {@code FreeResult}: positions by card id, in the order the website's Map holds them. */
    public record FreeResult(Map<String, GridPoint> positions, List<ArrangeResult.Island> islands) {}

    /** Every term of the free placer's score ({@code explainFreeScore}). */
    public record FreeScore(double own, double flow, double crossings, double air, double sprawl, double tidy,
        double total) {}

    private static double cells(final double n) {
        return n * BOARD_GRID;
    }

    private static double snap(final double v) {
        return JsMath.round(v / BOARD_GRID) * BOARD_GRID;
    }

    /** Air between two wired components, and between them and the shelf. */
    private static final double COMPONENT_GAP_CELLS = 8;
    /** Stress SGD iterations. */
    private static final int STRESS_ITERATIONS = 40;

    // The readability terms the router does not price (FREE_DIALS).
    //
    // TIDY: points per pixel a card's edge misses lining up with a neighbour's (stacked cards want a shared left/right
    // edge, side-by-side cards a shared top/bottom or port row), capped per axis at TIDY_CAP_CELLS. Rows and columns
    // emerge because agreeing on edges is cheaper.
    //
    // FLOW: points per pixel a wire's target centre falls short of one card width right of its source's (outputs leave
    // right, inputs enter left). A backward wire also pays a flat price (FLOW_FLAT, FLOW_FLAT_COLUMNS when both ends are
    // column cards): without it a chain folded into a triangle and a fan-out ringed its hub because those wires were
    // shorter. A wire inside a cycle (both ends in one strongly connected component) pays only FLOW_FLAT_CYCLE, since a
    // recycle loop must run backwards somewhere.
    //
    // SPRAWL: points per pixel of the layout's bounding-box perimeter.
    public static final double TIDY = 1;
    public static final double TIDY_CAP_CELLS = 4;
    public static final double FLOW = 0.35;
    public static final double FLOW_FLAT = 400;
    public static final double FLOW_FLAT_COLUMNS = 1500;
    public static final double FLOW_FLAT_CYCLE = 150;
    public static final double SPRAWL = 0.4;
    /** How far a neighbour may be, beyond the edges, and still be aligned with. */
    private static final double TIDY_REACH = cells(12);

    /** A wire inside one component, by local index. */
    private record LocalWire(int a, int b, ArrangeWire wire) {}

    private static double weightOf(final ArrangeWire wire) {
        return wire.width() != null ? WireMetrics.wireWeight(wire.width())
            : Math.max(wire.weight() != null ? wire.weight() : 1, 0.01);
    }

    private static double minOf(final double[] values) {
        double out = Double.POSITIVE_INFINITY;
        for (final double v : values) out = Math.min(out, v);
        return out;
    }

    private static double maxOf(final double[] values) {
        double out = Double.NEGATIVE_INFINITY;
        for (final double v : values) out = Math.max(out, v);
        return out;
    }

    /**
     * {@code arrangeFree(cards, wires, options)}: lays the board out. Returns every card's new top-left, normalised so
     * the bounding box starts at 0,0.
     */
    public static FreeResult arrangeFree(final List<ArrangeCard> cards, final List<ArrangeWire> wires,
        final FreeOptions options) {
        return new FreeArrange(
            options.gapCells() != null ? options.gapCells() : 1,
            options.besideCells() != null ? options.besideCells() : 2).arrange(cards, wires, options);
    }

    private FreeResult arrange(final List<ArrangeCard> cards, final List<ArrangeWire> wires,
        final FreeOptions options) {
        final Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < cards.size(); i++) index.put(
            cards.get(i)
                .id(),
            i);
        final List<List<Integer>> adjacency = new ArrayList<>(cards.size());
        for (int i = 0; i < cards.size(); i++) adjacency.add(new ArrayList<>());
        final List<LocalWire> usable = new ArrayList<>();
        for (final ArrangeWire wire : wires) {
            final Integer a = index.get(wire.source());
            final Integer b = index.get(wire.target());
            if (a == null || b == null || a.intValue() == b.intValue()) continue;
            adjacency.get(a)
                .add(b);
            adjacency.get(b)
                .add(a);
            usable.add(new LocalWire(a, b, wire));
        }
        // Components: wired webs, and the shelf of cards with no wires at all.
        final int[] component = new int[cards.size()];
        Arrays.fill(component, -1);
        final List<List<Integer>> components = new ArrayList<>();
        for (int i = 0; i < cards.size(); i += 1) {
            if (component[i] >= 0 || adjacency.get(i)
                .isEmpty()) continue;
            final List<Integer> members = new ArrayList<>();
            members.add(i);
            component[i] = components.size();
            for (int head = 0; head < members.size(); head += 1) {
                for (final int next : adjacency.get(members.get(head))) {
                    if (component[next] < 0) {
                        component[next] = components.size();
                        members.add(next);
                    }
                }
            }
            components.add(members);
        }
        final List<Integer> shelf = new ArrayList<>();
        for (int i = 0; i < cards.size(); i++) if (component[i] < 0) shelf.add(i);
        // Bigger components first: they set the row, and they get the trials.
        JsSort.sort(components, (a, b) -> {
            final int c = JsMath.sign(b.size() - a.size());
            return c != 0 ? c : JsMath.sign(a.get(0) - b.get(0));
        });
        int memberCount = 0;
        for (final List<Integer> members : components) memberCount += members.size();
        final double totalCards = memberCount != 0 ? memberCount : 1;
        final double trials = options.trials() != null ? options.trials() : 20000;
        final double[] progressDone = { 0 };
        final List<Block> blocks = new ArrayList<>();
        for (final List<Integer> members : components) {
            final List<ArrangeCard> local = new ArrayList<>(members.size());
            for (final int i : members) local.add(cards.get(i));
            final Map<Integer, Integer> localIndex = new HashMap<>();
            for (int k = 0; k < members.size(); k++) localIndex.put(members.get(k), k);
            final List<LocalWire> localWires = new ArrayList<>();
            for (final LocalWire w : usable) {
                if (!localIndex.containsKey(w.a())) continue;
                localWires.add(new LocalWire(localIndex.get(w.a()), localIndex.get(w.b()), w.wire()));
            }
            final double share = Math.max(200, JsMath.round((trials * members.size()) / totalCards));
            final GridPoint[] positions = layoutComponent(local, localWires, options.prices(), share, done -> {
                if (options.onProgress() != null) options.onProgress()
                    .at(progressDone[0] + done, trials);
            });
            progressDone[0] += share;
            final double[] xs = new double[positions.length], ys = new double[positions.length];
            for (int k = 0; k < positions.length; k++) {
                xs[k] = positions[k].x();
                ys[k] = positions[k].y();
            }
            final double left = minOf(xs);
            final double top = minOf(ys);
            final GridPoint[] normalised = new GridPoint[positions.length];
            final double[] rights = new double[positions.length], bottoms = new double[positions.length];
            for (int k = 0; k < positions.length; k++) {
                normalised[k] = new GridPoint(positions[k].x() - left, positions[k].y() - top);
                rights[k] = normalised[k].x() + local.get(k)
                    .width();
                bottoms[k] = normalised[k].y() + local.get(k)
                    .height();
            }
            blocks.add(new Block(members, normalised, maxOf(rights), maxOf(bottoms)));
        }
        // Pack the components in a row, wrapping like text past a wide page.
        final Map<String, GridPoint> out = new LinkedHashMap<>();
        final List<ArrangeResult.Island> islands = new ArrayList<>();
        final double gap = cells(COMPONENT_GAP_CELLS);
        double pageWidth = cells(240);
        for (final Block block : blocks) pageWidth = Math.max(pageWidth, block.width);
        double cursorX = 0;
        double cursorY = 0;
        double rowHeight = 0;
        for (final Block block : blocks) {
            if (cursorX > 0 && cursorX + block.width > pageWidth) {
                cursorX = 0;
                cursorY += rowHeight + gap;
                rowHeight = 0;
            }
            for (int k = 0; k < block.ids.size(); k++) {
                out.put(
                    cards.get(block.ids.get(k))
                        .id(),
                    new GridPoint(cursorX + block.positions[k].x(), cursorY + block.positions[k].y()));
            }
            islands.add(new ArrangeResult.Island(cursorX, cursorY, block.width, block.height, true));
            cursorX += block.width + gap;
            rowHeight = Math.max(rowHeight, block.height);
        }
        // The shelf: unwired cards in a row underneath, in their input order.
        if (!shelf.isEmpty()) {
            double x = 0;
            final double y = !blocks.isEmpty() ? cursorY + rowHeight + gap : 0;
            double height = 0;
            for (final int i : shelf) {
                out.put(
                    cards.get(i)
                        .id(),
                    new GridPoint(x, y));
                x += cards.get(i)
                    .width() + cells(2);
                height = Math.max(
                    height,
                    cards.get(i)
                        .height());
            }
            islands.add(new ArrangeResult.Island(0, y, Math.max(0, x - cells(2)), height, false));
        }
        return new FreeResult(out, islands);
    }

    private record Block(List<Integer> ids, GridPoint[] positions, double width, double height) {}

    // region One wired component.

    private GridPoint[] layoutComponent(final List<ArrangeCard> cards, final List<LocalWire> wires,
        final RouterTuning prices, final double trials, final DoubleConsumer onProgress) {
        final List<String> ids = new ArrayList<>(cards.size());
        for (final ArrangeCard card : cards) ids.add(card.id());
        final Rng random = new Rng(Proxy.hashIds(ids));
        final int n = cards.size();
        final Lattice lattice = columnLattice(cards);
        final Lattice anyCell = new Lattice(BOARD_GRID, null);
        final GridPoint[] continuous = orientForFlow(wires, stressLayout(cards, wires, random));
        if (n < 2) {
            return settleOnGrid(cards, continuous, lattice);
        }
        // The global search runs with every drawer FREE: the patterns need the column corridors to exist, and off the
        // lattice a drawer line collides with whatever stands beside its machine, so most trials were refused and the
        // search went nowhere. The patterns come in with the columns.
        final Patterns patterns = planPatterns(cards, wires, lattice);
        final Patterns loose = new Patterns(n);
        final GridPoint[] grid = settleOnGrid(cards, continuous, anyCell);
        final double globalTrials = JsMath.round(trials * 0.6);
        final GridPoint[] placed = new Annealer(cards, wires, grid, prices, random, anyCell, loose)
            .run(globalTrials, onProgress, 1);
        final GridPoint[] legal = settleWithPatterns(cards, legalise(cards, placed, lattice), patterns);
        final GridPoint[] done = new Annealer(cards, wires, legal, prices, random, lattice, patterns)
            .run(trials - globalTrials, d -> onProgress.accept(globalTrials + d), 0.15);
        return settleWithPatterns(cards, done, patterns);
    }

    /**
     * The column lattice: machines' left edges sit on multiples of the pitch. The pitch is the widest common machine
     * width plus a corridor for the widest drawer with a cell of air each side (two cells of air when there are no
     * drawers at all). {@code column} null: no column cards (any cell).
     */
    private record Lattice(double pitch, @Nullable boolean[] column) {

        boolean isColumnCard(final int i) {
            return column != null && column[i];
        }
    }

    private Lattice columnLattice(final List<ArrangeCard> cards) {
        final Map<Double, Integer> widths = new LinkedHashMap<>();
        for (final ArrangeCard card : cards) {
            if (card.storage()) continue;
            final Double key = card.width() + 0.0;
            final Integer count = widths.get(key);
            widths.put(key, (count != null ? count : 0) + 1);
        }
        final List<Map.Entry<Double, Integer>> entries = new ArrayList<>(widths.entrySet());
        JsSort.sort(entries, (a, b) -> {
            final int c = JsMath.sign(b.getValue() - a.getValue());
            return c != 0 ? c : JsMath.sign(b.getKey() - a.getKey());
        });
        final double machineWidth = !entries.isEmpty() ? entries.get(0)
            .getKey() : cells(22);
        // The corridor: a drawer, standing BESIDE cells from the machine it is wired to on either side (closer and the
        // wire between them is hidden).
        double widestDrawer = Double.NEGATIVE_INFINITY;
        boolean anyDrawer = false;
        for (final ArrangeCard card : cards) {
            if (card.width() < 0.6 * machineWidth) {
                anyDrawer = true;
                widestDrawer = Math.max(widestDrawer, card.width());
            }
        }
        final double corridor = anyDrawer ? widestDrawer + 2 * cells(besideCells) : cells(besideCells);
        final double pitch = snap(machineWidth + corridor);
        // Column cards are the wide ones, by size rather than by role: a small tile with no role stamped on it still
        // lives in the corridors.
        final boolean[] column = new boolean[cards.size()];
        for (int i = 0; i < cards.size(); i++) column[i] = cards.get(i)
            .width() >= 0.6 * machineWidth;
        return new Lattice(pitch, column);
    }

    /** A card's position quantised to what it may stand on: column or cell. */
    private static GridPoint quantise(final Lattice lattice, final int i, final GridPoint p) {
        return new GridPoint(
            lattice.isColumnCard(i) ? JsMath.round(p.x() / lattice.pitch()) * lattice.pitch() : snap(p.x()),
            snap(p.y()));
    }

    private static Rect rectAt(final GridPoint p, final ArrangeCard card) {
        return new Rect(p.x(), p.y(), p.x() + card.width(), p.y() + card.height());
    }

    /** One card's order key for settling: its index, whether it stands in a column (0) or not (1), its distance. */
    private record Order(int i, int column, double d) {}

    /**
     * Machines onto their columns, nearest the middle first, each on the free lattice spot closest to where the global
     * search left it; drawers keep their cells unless a machine now needs the spot.
     */
    private GridPoint[] legalise(final List<ArrangeCard> cards, final GridPoint[] positions, final Lattice lattice) {
        final int n = cards.size();
        final double gap = cells(gapCells);
        double sx = 0, sy = 0;
        for (int i = 0; i < n; i++) sx = sx + positions[i].x() + cards.get(i)
            .width() / 2;
        for (int i = 0; i < n; i++) sy = sy + positions[i].y() + cards.get(i)
            .height() / 2;
        final double cx = sx / n;
        final double cy = sy / n;
        final List<Order> order = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            final ArrangeCard card = cards.get(i);
            order.add(
                new Order(
                    i,
                    lattice.isColumnCard(i) ? 0 : 1,
                    JsMath.hypot(positions[i].x() + card.width() / 2 - cx, positions[i].y() + card.height() / 2 - cy)));
        }
        JsSort.sort(order, (a, b) -> {
            int c = JsMath.sign(a.column() - b.column());
            if (c != 0) return c;
            c = JsMath.sign(a.d() - b.d());
            return c != 0 ? c : JsMath.sign(a.i() - b.i());
        });
        final List<Rect> placed = new ArrayList<>();
        final GridPoint[] out = new GridPoint[n];
        for (final Order entry : order) {
            final int i = entry.i();
            final GridPoint want = quantise(lattice, i, positions[i]);
            final double stepX = lattice.isColumnCard(i) ? lattice.pitch() : BOARD_GRID;
            final GridPoint found = nearestFree(want, cards.get(i), placed, gap, 60, null, stepX);
            final GridPoint spot = found != null ? found : want;
            out[i] = spot;
            placed.add(rectAt(spot, cards.get(i)));
        }
        return out;
    }

    // endregion

    // region Drawer patterns: buds and shared drawers, placed by rule.

    /** {@code Placement}: a bud on one machine's side, or a drawer shared by machines a and b. */
    private record Placement(boolean bud, int machine, boolean right, double port, int a, int b, double portA,
        double portB) {

        static Placement bud(final int machine, final boolean right, final double port) {
            return new Placement(true, machine, right, port, -1, -1, 0, 0);
        }

        static Placement pair(final int a, final int b, final double portA, final double portB) {
            return new Placement(false, -1, false, 0, a, b, portA, portB);
        }
    }

    private static final class Patterns {

        /** How each card is placed; null for a card the search moves. */
        final Placement[] placement;
        /** The derived drawers attached to each machine (its buds, its shared drawers). */
        final List<List<Integer>> attached;
        /** The machines each machine shares a drawer with. */
        final List<List<Integer>> pairPartners;

        Patterns(final int n) {
            placement = new Placement[n];
            attached = new ArrayList<>(n);
            pairPartners = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                attached.add(new ArrayList<>());
                pairPartners.add(new ArrayList<>());
            }
        }
    }

    private static List<List<Integer>> wiresOf(final int n, final List<LocalWire> wires) {
        final List<List<Integer>> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new ArrayList<>());
        for (int w = 0; w < wires.size(); w++) {
            out.get(
                wires.get(w)
                    .a())
                .add(w);
            out.get(
                wires.get(w)
                    .b())
                .add(w);
        }
        return out;
    }

    private static double mean(final List<Double> list) {
        double s = 0;
        for (final double v : list) s += v;
        return s / list.size();
    }

    private static Patterns planPatterns(final List<ArrangeCard> cards, final List<LocalWire> wires,
        final Lattice lattice) {
        final int n = cards.size();
        final Patterns patterns = new Patterns(n);
        final List<List<Integer>> wiresOf = wiresOf(n, wires);
        for (int d = 0; d < n; d += 1) {
            if (lattice.isColumnCard(d) || wiresOf.get(d)
                .isEmpty()) continue;
            final Map<Integer, List<Double>> partners = new LinkedHashMap<>();
            boolean foreign = false;
            for (final int w : wiresOf.get(d)) {
                final LocalWire wire = wires.get(w);
                final int other = wire.a() == d ? wire.b() : wire.a();
                if (!lattice.isColumnCard(other)) {
                    foreign = true;
                    break;
                }
                // The port row on the machine's side, the machine's mid-height when unmeasured.
                final Double measured = wire.a() == other ? wire.wire()
                    .sourcePortY()
                    : wire.wire()
                        .targetPortY();
                final double port = measured != null ? measured
                    : cards.get(other)
                        .height() / 2;
                partners.computeIfAbsent(other, k -> new ArrayList<>())
                    .add(port);
            }
            if (foreign) continue;
            if (partners.size() == 1) {
                final Map.Entry<Integer, List<Double>> only = partners.entrySet()
                    .iterator()
                    .next();
                final int machine = only.getKey();
                // A supply (every wire runs drawer -> machine) stands on the left.
                boolean supply = true;
                for (final int w : wiresOf.get(d)) if (wires.get(w)
                    .a() != d) supply = false;
                patterns.placement[d] = Placement.bud(machine, !supply, mean(only.getValue()));
                patterns.attached.get(machine)
                    .add(d);
            } else if (partners.size() == 2) {
                final List<Map.Entry<Integer, List<Double>>> both = new ArrayList<>(partners.entrySet());
                final int a = both.get(0)
                    .getKey(), b = both.get(1)
                        .getKey();
                patterns.placement[d] = Placement.pair(
                    a,
                    b,
                    mean(
                        both.get(0)
                            .getValue()),
                    mean(
                        both.get(1)
                            .getValue()));
                patterns.attached.get(a)
                    .add(d);
                patterns.attached.get(b)
                    .add(d);
                if (!patterns.pairPartners.get(a)
                    .contains(b))
                    patterns.pairPartners.get(a)
                        .add(b);
                if (!patterns.pairPartners.get(b)
                    .contains(a))
                    patterns.pairPartners.get(b)
                        .add(a);
            }
        }
        return patterns;
    }

    private record Keyed(int d, double key) {}

    private static int byKey(final Keyed p, final Keyed q) {
        final int c = JsMath.sign(p.key() - q.key());
        return c != 0 ? c : JsMath.sign(p.d() - q.d());
    }

    private static void add(final Map<String, List<Keyed>> map, final String key, final int d, final double order) {
        map.computeIfAbsent(key, k -> new ArrayList<>())
            .add(new Keyed(d, order));
    }

    /**
     * Where the drawers attached to {@code machines} (and to the machines they share drawers with) stand, given every
     * card's position through {@code at}. Lines are built whole: a machine's right line holds its product buds and the
     * shared drawers of every partner standing to its right, in port order, touching, centred on the ports they serve
     * and kept within the machine's height; a row between a machine and the one below it likewise.
     */
    private Map<Integer, GridPoint> derivePatterns(final List<ArrangeCard> cards, final Patterns patterns,
        final List<Integer> machines, final java.util.function.IntFunction<GridPoint> at) {
        final Map<Integer, GridPoint> out = new LinkedHashMap<>();
        final Set<Integer> scope = new LinkedHashSet<>();
        for (final int m : machines) {
            scope.add(m);
            scope.addAll(patterns.pairPartners.get(m));
        }
        final java.util.function.IntFunction<Rect> rectOf = i -> rectAt(at.apply(i), cards.get(i));
        final Map<String, List<Keyed>> lines = new LinkedHashMap<>();
        final Map<String, List<Keyed>> rows = new LinkedHashMap<>();
        final Set<Integer> seen = new HashSet<>();
        for (final int m : scope) {
            for (final int d : patterns.attached.get(m)) {
                if (seen.contains(d)) continue;
                seen.add(d);
                final Placement place = patterns.placement[d];
                if (place.bud()) {
                    add(lines, place.machine() + ":" + (place.right() ? "right" : "left"), d, place.port());
                    continue;
                }
                final Rect ra = rectOf.apply(place.a());
                final Rect rb = rectOf.apply(place.b());
                if (rb.left >= ra.right) add(lines, place.a() + ":right", d, place.portA());
                else if (ra.left >= rb.right) add(lines, place.b() + ":right", d, place.portB());
                else if (rb.top >= ra.bottom) add(rows, place.a() + ":" + place.b(), d, place.portA());
                else if (ra.top >= rb.bottom) add(rows, place.b() + ":" + place.a(), d, place.portB());
                else if ((ra.left + ra.right) / 2 <= (rb.left + rb.right) / 2)
                    add(lines, place.a() + ":right", d, place.portA());
                else add(lines, place.b() + ":right", d, place.portB());
            }
        }
        for (final Map.Entry<String, List<Keyed>> entry : lines.entrySet()) {
            final String[] parts = entry.getKey()
                .split(":");
            final int m = Integer.parseInt(parts[0]);
            final boolean right = parts[1].equals("right");
            final Rect r = rectOf.apply(m);
            final List<Keyed> members = entry.getValue();
            JsSort.sort(members, FreeArrange::byKey);
            double height = 0;
            double width = Double.NEGATIVE_INFINITY;
            double portSum = 0;
            for (final Keyed k : members) height = height + cards.get(k.d())
                .height();
            for (final Keyed k : members) width = Math.max(
                width,
                cards.get(k.d())
                    .width());
            for (final Keyed k : members) portSum = portSum + k.key();
            final double meanPort = portSum / members.size();
            final double y0 = snap(
                Math.min(Math.max(r.top + meanPort - height / 2, r.top), Math.max(r.top, r.bottom - height)));
            // Right: beside the machine. Left: at the far side of the corridor, the same x the previous column's right
            // line uses, so two lines in one corridor can only clash in y.
            final double x = right ? r.right + cells(besideCells) : r.left - width - cells(besideCells);
            double y = y0;
            for (final Keyed k : members) {
                out.put(k.d(), new GridPoint(snap(x), y));
                // Drawers in one line keep a cell of air: touching, their keys and the wires between are hidden.
                y += cards.get(k.d())
                    .height() + cells(gapCells);
            }
        }
        for (final Map.Entry<String, List<Keyed>> entry : rows.entrySet()) {
            final String[] parts = entry.getKey()
                .split(":");
            final Rect upper = rectOf.apply(Integer.parseInt(parts[0]));
            final Rect lower = rectOf.apply(Integer.parseInt(parts[1]));
            final List<Keyed> members = entry.getValue();
            JsSort.sort(members, FreeArrange::byKey);
            double width = 0;
            for (final Keyed k : members) width = width + cards.get(k.d())
                .width();
            final double left = Math.max(upper.left, lower.left);
            final double right = Math.min(upper.right, lower.right);
            final double x0 = snap(
                right > left ? Math.min(Math.max((left + right) / 2 - width / 2, left), Math.max(left, right - width))
                    : left);
            final double y = snap(upper.bottom + cells(besideCells));
            double x = x0;
            for (final Keyed k : members) {
                out.put(k.d(), new GridPoint(x, y));
                x += cards.get(k.d())
                    .width() + cells(gapCells);
            }
        }
        return out;
    }

    /**
     * Every derived drawer put where its pattern says, from the machines as they stand; a drawer whose place is taken
     * (two lines meeting in one corridor, a row with no room) is freed - handed the nearest empty spot and moved by the
     * search like any other card from then on.
     */
    private GridPoint[] settleWithPatterns(final List<ArrangeCard> cards, final GridPoint[] positions,
        final Patterns patterns) {
        final GridPoint[] out = positions.clone();
        final List<Integer> machines = new ArrayList<>();
        for (int i = 0; i < cards.size(); i++) if (!patterns.attached.get(i)
            .isEmpty()) machines.add(i);
        final Map<Integer, GridPoint> derived = derivePatterns(cards, patterns, machines, i -> out[i]);
        for (final Map.Entry<Integer, GridPoint> entry : derived.entrySet()) out[entry.getKey()] = entry.getValue();
        final double gap = cells(gapCells);
        // Lines that meet in one corridor STACK: two machines side by side put their products and supplies in the same
        // corridor, and the later line slides down until it clears the one above rather than being broken up.
        {
            final Map<Double, List<Integer>> byCorridor = new LinkedHashMap<>();
            for (final int d : derived.keySet()) {
                // + 0.0: a Map key as the website's (0 and -0 are one key there).
                byCorridor.computeIfAbsent(out[d].x() + 0.0, k -> new ArrayList<>())
                    .add(d);
            }
            for (final List<Integer> list : byCorridor.values()) {
                JsSort.sort(list, (a, b) -> {
                    final int c = JsMath.sign(out[a].y() - out[b].y());
                    return c != 0 ? c : JsMath.sign(a - b);
                });
                double floor = Double.NEGATIVE_INFINITY;
                for (final int d : list) {
                    if (out[d].y() < floor) out[d] = new GridPoint(out[d].x(), floor);
                    floor = out[d].y() + cards.get(d)
                        .height() + gap;
                }
            }
        }
        // Conflicts, in a fixed order: the later drawer of a clashing pair is freed.
        final List<Integer> ids = new ArrayList<>(derived.keySet());
        JsSort.sort(ids, (a, b) -> JsMath.sign(a - b));
        for (final int d : ids) {
            if (patterns.placement[d] == null) continue;
            final Rect mine = rectAt(out[d], cards.get(d));
            boolean clash = false;
            for (int o = 0; o < cards.size(); o += 1) {
                if (o == d) continue;
                if (overlaps(
                    mine,
                    rectAt(out[o], cards.get(o)),
                    patterns.placement[o] != null && derived.containsKey(o) ? 0 : gap)) {
                    clash = true;
                    break;
                }
            }
            if (!clash) continue;
            freePlacement(patterns, d);
            final List<Rect> others = new ArrayList<>();
            for (int o = 0; o < cards.size(); o++) if (o != d) others.add(rectAt(out[o], cards.get(o)));
            final GridPoint found = nearestFree(out[d], cards.get(d), others, gap, 80, null, BOARD_GRID);
            if (found != null) out[d] = found;
        }
        return out;
    }

    private static List<Integer> without(final List<Integer> list, final int d) {
        final List<Integer> out = new ArrayList<>(list.size());
        for (final int x : list) if (x != d) out.add(x);
        return out;
    }

    /** A drawer leaves its pattern and becomes a card the search moves. */
    private static void freePlacement(final Patterns patterns, final int d) {
        final Placement place = patterns.placement[d];
        if (place == null) return;
        patterns.placement[d] = null;
        if (place.bud()) {
            patterns.attached.set(place.machine(), without(patterns.attached.get(place.machine()), d));
            return;
        }
        patterns.attached.set(place.a(), without(patterns.attached.get(place.a()), d));
        patterns.attached.set(place.b(), without(patterns.attached.get(place.b()), d));
        boolean stillShared = false;
        for (final int x : patterns.attached.get(place.a())) {
            final Placement p = patterns.placement[x];
            if (p != null && !p.bud() && (p.a() == place.b() || p.b() == place.b())) {
                stillShared = true;
                break;
            }
        }
        if (!stillShared) {
            patterns.pairPartners.set(place.a(), without(patterns.pairPartners.get(place.a()), place.b()));
            patterns.pairPartners.set(place.b(), without(patterns.pairPartners.get(place.b()), place.a()));
        }
    }

    // endregion

    // region Stage 1: stress.

    /** Ideal centre-to-centre distance of two cards that share a wire. */
    private double pitch(final ArrangeCard a, final ArrangeCard b) {
        return (Math.max(a.width(), a.height()) + Math.max(b.width(), b.height())) / 2 + cells(besideCells);
    }

    private record Edge(int to, double length) {}

    /** Card CENTRES matching graph distance as closely as stress SGD can. */
    private GridPoint[] stressLayout(final List<ArrangeCard> cards, final List<LocalWire> wires, final Rng random) {
        final int n = cards.size();
        final List<List<Edge>> edges = new ArrayList<>(n);
        for (int i = 0; i < n; i++) edges.add(new ArrayList<>());
        for (final LocalWire w : wires) {
            final double length = pitch(cards.get(w.a()), cards.get(w.b()));
            edges.get(w.a())
                .add(new Edge(w.b(), length));
            edges.get(w.b())
                .add(new Edge(w.a(), length));
        }
        // All-pairs shortest paths by Dijkstra from each card (n is small).
        final double[][] distance = new double[n][];
        for (int source = 0; source < n; source += 1) {
            final double[] dist = new double[n];
            Arrays.fill(dist, Double.POSITIVE_INFINITY);
            final boolean[] done = new boolean[n];
            dist[source] = 0;
            for (int round = 0; round < n; round += 1) {
                int best = -1;
                for (int i = 0; i < n; i += 1) {
                    if (!done[i] && dist[i] < Double.POSITIVE_INFINITY && (best < 0 || dist[i] < dist[best])) best = i;
                }
                if (best < 0) break;
                done[best] = true;
                for (final Edge edge : edges.get(best)) {
                    final double through = dist[best] + edge.length();
                    if (through < dist[edge.to()]) dist[edge.to()] = through;
                }
            }
            distance[source] = dist;
        }
        // Start on a circle in breadth-first order from the best-connected card, never from where the cards stand:
        // the answer for a board must not depend on how it was left, or two players with one plan would get two
        // arrangements. The seeded shuffles below are the only randomness.
        final double[] x = new double[n];
        final double[] y = new double[n];
        {
            final List<Integer> order = new ArrayList<>();
            final boolean[] seen = new boolean[n];
            final List<Integer> byDegree = new ArrayList<>(n);
            for (int i = 0; i < n; i++) byDegree.add(i);
            JsSort.sort(byDegree, (a, b) -> {
                final int c = JsMath.sign(
                    edges.get(b)
                        .size()
                        - edges.get(a)
                            .size());
                return c != 0 ? c : JsMath.sign(a - b);
            });
            for (final int root : byDegree) {
                if (seen[root]) continue;
                seen[root] = true;
                order.add(root);
                for (int head = order.size() - 1; head < order.size(); head += 1) {
                    for (final Edge edge : edges.get(order.get(head))) {
                        if (!seen[edge.to()]) {
                            seen[edge.to()] = true;
                            order.add(edge.to());
                        }
                    }
                }
            }
            double step = 0;
            for (final LocalWire w : wires) step += pitch(cards.get(w.a()), cards.get(w.b()));
            step = !wires.isEmpty() ? step / wires.size() : cells(24);
            final double radius = Math.max(step, (n * step) / (2 * Math.PI));
            for (int k = 0; k < order.size(); k++) {
                final int i = order.get(k);
                final double angle = (2 * Math.PI * k) / n;
                x[i] = radius * StrictMath.cos(angle);
                y[i] = radius * StrictMath.sin(angle);
            }
        }
        final List<int[]> pairs = new ArrayList<>();
        double wMin = Double.POSITIVE_INFINITY;
        double wMax = 0;
        for (int i = 0; i < n; i += 1) {
            for (int k = i + 1; k < n; k += 1) {
                final double d = distance[i][k];
                if (!Double.isFinite(d) || d <= 0) continue;
                pairs.add(new int[] { i, k });
                final double w = 1 / (d * d);
                wMin = Math.min(wMin, w);
                wMax = Math.max(wMax, w);
            }
        }
        if (pairs.isEmpty()) {
            final GridPoint[] out = new GridPoint[n];
            for (int i = 0; i < n; i++) out[i] = new GridPoint(x[i], y[i]);
            return out;
        }
        final double etaMax = 1 / wMin;
        final double etaMin = 0.1 / wMax;
        final double lambda = StrictMath.log(etaMax / etaMin) / Math.max(1, STRESS_ITERATIONS - 1);
        for (int t = 0; t < STRESS_ITERATIONS; t += 1) {
            final double eta = etaMax * StrictMath.exp(-lambda * t);
            // Fisher-Yates with the seeded generator: deterministic shuffles.
            for (int i = pairs.size() - 1; i > 0; i -= 1) {
                final int j = (int) Math.floor(random.next() * (i + 1));
                final int[] swap = pairs.get(i);
                pairs.set(i, pairs.get(j));
                pairs.set(j, swap);
            }
            for (final int[] pair : pairs) {
                final int i = pair[0], k = pair[1];
                final double d = distance[i][k];
                final double mu = Math.min(1, eta / (d * d));
                final double dx = x[i] - x[k];
                final double dy = y[i] - y[k];
                final double h = JsMath.hypot(dx, dy);
                final double mag = h == 0 || Double.isNaN(h) ? 1e-6 : h;
                final double r = ((mag - d) / 2) * mu;
                final double rx = (dx / mag) * r;
                final double ry = (dy / mag) * r;
                x[i] -= rx;
                y[i] -= ry;
                x[k] += rx;
                y[k] += ry;
            }
        }
        final GridPoint[] out = new GridPoint[n];
        for (int i = 0; i < n; i++) out[i] = new GridPoint(x[i], y[i]);
        return out;
    }

    /** One of the eight reflections and rotations. */
    private static GridPoint variant(final int v, final GridPoint p) {
        return switch (v) {
            case 0 -> new GridPoint(p.x(), p.y());
            case 1 -> new GridPoint(-p.x(), p.y());
            case 2 -> new GridPoint(p.x(), -p.y());
            case 3 -> new GridPoint(-p.x(), -p.y());
            case 4 -> new GridPoint(p.y(), p.x());
            case 5 -> new GridPoint(-p.y(), p.x());
            case 6 -> new GridPoint(p.y(), -p.x());
            default -> new GridPoint(-p.y(), -p.x());
        };
    }

    /**
     * Stress is blind to direction: any reflection or rotation scores the same. Of the eight, keep the one where the
     * fewest wires run backwards (weighted), so the search starts from a board that already flows.
     */
    private static GridPoint[] orientForFlow(final List<LocalWire> wires, final GridPoint[] centres) {
        GridPoint[] best = centres;
        double bestCost = Double.POSITIVE_INFINITY;
        for (int v = 0; v < 8; v++) {
            final GridPoint[] mapped = new GridPoint[centres.length];
            for (int i = 0; i < centres.length; i++) mapped[i] = variant(v, centres[i]);
            double cost = 0;
            for (final LocalWire w : wires) {
                final double weight = weightOf(w.wire());
                cost += weight * Math.max(0, mapped[w.a()].x() - mapped[w.b()].x());
            }
            if (cost < bestCost - 1e-9) {
                bestCost = cost;
                best = mapped;
            }
        }
        return best;
    }

    // endregion

    // region Stage 2: the grid.

    private static boolean overlaps(final Rect a, final Rect b, final double gap) {
        return a.left < b.right + gap && b.left < a.right + gap && a.top < b.bottom + gap && b.top < a.bottom + gap;
    }

    /** Continuous centres -> non-overlapping grid top-lefts. */
    private GridPoint[] settleOnGrid(final List<ArrangeCard> cards, final GridPoint[] centres, final Lattice lattice) {
        final int n = cards.size();
        final double gap = cells(gapCells);
        final double[] tlx = new double[n], tly = new double[n];
        for (int i = 0; i < n; i++) {
            tlx[i] = centres[i].x() - cards.get(i)
                .width() / 2;
            tly[i] = centres[i].y() - cards.get(i)
                .height() / 2;
        }
        // Push overlapping pairs apart along their least penetration.
        for (int pass = 0; pass < 400; pass += 1) {
            boolean moved = false;
            for (int i = 0; i < n; i += 1) {
                for (int k = i + 1; k < n; k += 1) {
                    final ArrangeCard ci = cards.get(i), ck = cards.get(k);
                    final Rect a = new Rect(tlx[i], tly[i], tlx[i] + ci.width(), tly[i] + ci.height());
                    final Rect b = new Rect(tlx[k], tly[k], tlx[k] + ck.width(), tly[k] + ck.height());
                    if (!overlaps(a, b, gap)) continue;
                    final double px = Math.min(a.right + gap - b.left, b.right + gap - a.left);
                    final double py = Math.min(a.bottom + gap - b.top, b.bottom + gap - a.top);
                    moved = true;
                    if (px < py) {
                        final double dir = (a.left + a.right) / 2 <= (b.left + b.right) / 2 ? 1 : -1;
                        tlx[i] -= (dir * px) / 2;
                        tlx[k] += (dir * px) / 2;
                    } else {
                        final double dir = (a.top + a.bottom) / 2 <= (b.top + b.bottom) / 2 ? 1 : -1;
                        tly[i] -= (dir * py) / 2;
                        tly[k] += (dir * py) / 2;
                    }
                }
            }
            if (!moved) break;
        }
        // Onto the grid: nearest the middle first, each on the closest free spot.
        double sx = 0, sy = 0;
        for (int i = 0; i < n; i++) sx = sx + tlx[i] + cards.get(i)
            .width() / 2;
        for (int i = 0; i < n; i++) sy = sy + tly[i] + cards.get(i)
            .height() / 2;
        final double cx = sx / n;
        final double cy = sy / n;
        final List<Order> order = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            final ArrangeCard card = cards.get(i);
            order.add(new Order(i, 0, JsMath.hypot(tlx[i] + card.width() / 2 - cx, tly[i] + card.height() / 2 - cy)));
        }
        JsSort.sort(order, (a, b) -> {
            final int c = JsMath.sign(a.d() - b.d());
            return c != 0 ? c : JsMath.sign(a.i() - b.i());
        });
        final List<Rect> placed = new ArrayList<>();
        final GridPoint[] out = new GridPoint[n];
        for (final Order entry : order) {
            final int i = entry.i();
            final GridPoint want = quantise(lattice, i, new GridPoint(tlx[i], tly[i]));
            final double stepX = lattice.isColumnCard(i) ? lattice.pitch() : BOARD_GRID;
            final GridPoint found = nearestFree(want, cards.get(i), placed, gap, 60, null, stepX);
            final GridPoint spot = found != null ? found : want;
            out[i] = spot;
            placed.add(rectAt(spot, cards.get(i)));
        }
        return out;
    }

    private static final int[] ROW_ZERO = { 0 };
    private static final int[] ROW_BOTH = { -1, 1 };

    /**
     * The free grid spot nearest {@code want} for a card of this size, within {@code radius} cells; {@code skip} (by
     * index into {@code others}) passes over some of them. Null when there is none.
     */
    @Nullable
    private static GridPoint nearestFree(final GridPoint want, final ArrangeCard card, final List<Rect> others,
        final double gap, final int radius, @Nullable final IntPredicate skip, final double stepX) {
        if (fits(want.x(), want.y(), card, others, gap, skip)) return want;
        for (int r = 1; r <= radius; r += 1) {
            // Ring r, nearest points first: a diamond by Manhattan distance.
            GridPoint best = null;
            double bestD = Double.POSITIVE_INFINITY;
            for (int dx = -r; dx <= r; dx += 1) {
                final int dy = r - Math.abs(dx);
                for (final int sy : dy == 0 ? ROW_ZERO : ROW_BOTH) {
                    final double x = want.x() + (double) dx * stepX;
                    final double y = want.y() + (double) (sy * dy) * BOARD_GRID;
                    final double d = dx * dx + dy * dy;
                    if (d < bestD && fits(x, y, card, others, gap, skip)) {
                        best = new GridPoint(x, y);
                        bestD = d;
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    private static boolean fits(final double x, final double y, final ArrangeCard card, final List<Rect> others,
        final double gap, @Nullable final IntPredicate skip) {
        final Rect rect = new Rect(x, y, x + card.width(), y + card.height());
        for (int o = 0; o < others.size(); o++) {
            if (skip != null && skip.test(o)) continue;
            if (overlaps(rect, others.get(o), gap)) return false;
        }
        return true;
    }

    // endregion

    // region The terms, standalone, so the search and the explainer price alike.

    private static List<Map<Integer, double[]>> portRowsOf(final int n, final List<LocalWire> wires) {
        final List<Map<Integer, double[]>> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) rows.add(new HashMap<>());
        for (final LocalWire w : wires) {
            final Double s = w.wire()
                .sourcePortY(),
                t = w.wire()
                    .targetPortY();
            if (s != null && t != null) {
                rows.get(w.a())
                    .put(w.b(), new double[] { s, t });
                rows.get(w.b())
                    .put(w.a(), new double[] { t, s });
            }
        }
        return rows;
    }

    /** The tidiness owed by card k at these rects (TIDY). */
    private static double tidyAt(final int k, final Rect[] rects, final List<Map<Integer, double[]>> portRows) {
        final Rect me = rects[k];
        double misX = Double.POSITIVE_INFINITY;
        double misY = Double.POSITIVE_INFINITY;
        for (int c = 0; c < rects.length; c += 1) {
            if (c == k) continue;
            final Rect other = rects[c];
            // Stacked: x-ranges overlap, within reach vertically -> share a left/right edge.
            if (me.left < other.right && other.left < me.right) {
                final double gapY = Math.max(other.top - me.bottom, me.top - other.bottom);
                if (gapY <= TIDY_REACH) {
                    misX = Math.min(Math.min(misX, Math.abs(me.left - other.left)), Math.abs(me.right - other.right));
                }
            }
            // Beside: y-ranges overlap, within reach horizontally -> share a top/bottom edge or a port row.
            if (me.top < other.bottom && other.top < me.bottom) {
                final double gapX = Math.max(other.left - me.right, me.left - other.right);
                if (gapX <= TIDY_REACH) {
                    misY = Math.min(Math.min(misY, Math.abs(me.top - other.top)), Math.abs(me.bottom - other.bottom));
                    final double[] rows = portRows.get(k)
                        .get(c);
                    if (rows != null) misY = Math.min(misY, Math.abs(me.top + rows[0] - (other.top + rows[1])));
                }
            }
        }
        final double cap = cells(TIDY_CAP_CELLS);
        final double x = misX == Double.POSITIVE_INFINITY ? 0 : Math.min(misX, cap);
        final double y = misY == Double.POSITIVE_INFINITY ? 0 : Math.min(misY, cap);
        return TIDY * (x + y);
    }

    /** The flow price of a wire from rect a to rect b (FLOW). */
    private static double flowAt(final Rect a, final Rect b, final double gap, final double flat) {
        final double forward = (a.right - a.left + (b.right - b.left)) / 2 + gap;
        final double ahead = (b.left + b.right) / 2 - (a.left + a.right) / 2;
        return FLOW * Math.max(0, forward - ahead) + (ahead <= 0 ? flat : 0);
    }

    /** Tarjan's strongly connected components over the DIRECTED wires. */
    private static final class Tarjan {

        final List<List<Integer>> out;
        final int[] index, low, component;
        final boolean[] onStack;
        final List<Integer> stack = new ArrayList<>();
        int next = 0;
        int count = 0;

        Tarjan(final int n, final List<LocalWire> wires) {
            out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) out.add(new ArrayList<>());
            for (final LocalWire w : wires) out.get(w.a())
                .add(w.b());
            index = new int[n];
            Arrays.fill(index, -1);
            low = new int[n];
            onStack = new boolean[n];
            component = new int[n];
            Arrays.fill(component, -1);
            for (int v = 0; v < n; v += 1) if (index[v] < 0) strong(v);
        }

        void strong(final int v) {
            index[v] = low[v] = next++;
            stack.add(v);
            onStack[v] = true;
            for (final int w : out.get(v)) {
                if (index[w] < 0) {
                    strong(w);
                    low[v] = Math.min(low[v], low[w]);
                } else if (onStack[w]) {
                    low[v] = Math.min(low[v], index[w]);
                }
            }
            if (low[v] == index[v]) {
                int w;
                do {
                    w = stack.remove(stack.size() - 1);
                    onStack[w] = false;
                    component[w] = count;
                } while (w != v);
                count += 1;
            }
        }
    }

    /**
     * Each wire's flat backward price: the cycle price for a wire whose ends share a strongly connected component, else
     * the column or plain price.
     */
    private static double[] flatPrices(final int n, final List<LocalWire> wires, final Lattice lattice) {
        final int[] component = new Tarjan(n, wires).component;
        final double[] out = new double[wires.size()];
        for (int w = 0; w < wires.size(); w++) {
            final int a = wires.get(w)
                .a(), b = wires.get(w)
                    .b();
            out[w] = component[a] == component[b] ? FLOW_FLAT_CYCLE
                : lattice.isColumnCard(a) && lattice.isColumnCard(b) ? FLOW_FLAT_COLUMNS : FLOW_FLAT;
        }
        return out;
    }

    private static double sprawlOf(final Rect[] rects) {
        double left = Double.POSITIVE_INFINITY;
        double top = Double.POSITIVE_INFINITY;
        double right = Double.NEGATIVE_INFINITY;
        double bottom = Double.NEGATIVE_INFINITY;
        for (final Rect rect : rects) {
            left = Math.min(left, rect.left);
            top = Math.min(top, rect.top);
            right = Math.max(right, rect.right);
            bottom = Math.max(bottom, rect.bottom);
        }
        return SPRAWL * 2 * (right - left + (bottom - top));
    }

    private static List<ArrangeCard> airCards(final List<ArrangeCard> cards) {
        final List<ArrangeCard> out = new ArrayList<>(cards.size());
        for (final ArrangeCard card : cards) out
            .add(new ArrangeCard(card.id(), 0, 0, card.width(), card.height(), card.storage()));
        return out;
    }

    /**
     * Every term of the free placer's score for a layout, for tuning by hand ({@code explainFreeScore}). The website
     * reads the gap and beside cells its last arrangeFree call set; here they are given (null: the defaults, 1 and 2).
     */
    public static FreeScore explainFreeScore(final List<ArrangeCard> cards, final List<ArrangeWire> wires,
        final Map<String, GridPoint> positions, final RouterTuning prices, @Nullable final Double gapCells,
        @Nullable final Double besideCells) {
        return new FreeArrange(gapCells != null ? gapCells : 1, besideCells != null ? besideCells : 2)
            .explain(cards, wires, positions, prices);
    }

    private FreeScore explain(final List<ArrangeCard> cards, final List<ArrangeWire> wires,
        final Map<String, GridPoint> positions, final RouterTuning prices) {
        final Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < cards.size(); i++) index.put(
            cards.get(i)
                .id(),
            i);
        final List<LocalWire> local = new ArrayList<>();
        for (final ArrangeWire wire : wires) {
            final Integer a = index.get(wire.source()), b = index.get(wire.target());
            if (a == null || b == null || a.intValue() == b.intValue()) continue;
            local.add(new LocalWire(a, b, wire));
        }
        final Rect[] rects = new Rect[cards.size()];
        for (int i = 0; i < cards.size(); i++) {
            final GridPoint at = positions.get(
                cards.get(i)
                    .id());
            rects[i] = rectAt(at != null ? at : new GridPoint(0, 0), cards.get(i));
        }
        final double gap = cells(gapCells);
        final Lattice lattice = columnLattice(cards);
        final double[] flats = flatPrices(cards.size(), local, lattice);
        final double[] weights = new double[local.size()];
        final double[][] paths = new double[local.size()][];
        for (int w = 0; w < local.size(); w++) {
            weights[w] = weightOf(
                local.get(w)
                    .wire());
            paths[w] = proxyPath(rects[local.get(w)
                .a()],
                rects[local.get(w)
                    .b()]);
        }
        double own = 0;
        double flow = 0;
        for (int w = 0; w < local.size(); w++) {
            final int a = local.get(w)
                .a(), b = local.get(w)
                    .b();
            own += (pathLength(paths[w]) + pathBends(paths[w], prices) + pathBlocked(paths[w], rects, a, b, prices))
                * weights[w];
            flow += flowAt(rects[a], rects[b], gap, flats[w]) * weights[w];
        }
        double crossings = 0;
        for (int i = 0; i < paths.length; i += 1) {
            for (int k = i + 1; k < paths.length; k += 1) {
                crossings += pathCrossings(paths[i], paths[k]) * prices.crossing() * Math.max(weights[i], weights[k]);
            }
        }
        final double air = Air.makeAirTerm(airCards(cards), wires, prices.islandAir())
            .applyAsDouble(positions);
        final List<Map<Integer, double[]>> rows = portRowsOf(cards.size(), local);
        double tidy = 0;
        for (int k = 0; k < cards.size(); k += 1) tidy += tidyAt(k, rects, rows);
        final double sprawl = sprawlOf(rects);
        return new FreeScore(own, flow, crossings, air, sprawl, tidy, own + flow + crossings + air + sprawl + tidy);
    }

    // endregion

    // region Stage 3: the search.

    private static Rect inflate(final Rect rect, final double by) {
        return new Rect(rect.left - by, rect.top - by, rect.right + by, rect.bottom + by);
    }

    private static Rect bbox(final double[] path) {
        double left = Double.POSITIVE_INFINITY;
        double top = Double.POSITIVE_INFINITY;
        double right = Double.NEGATIVE_INFINITY;
        double bottom = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < path.length; i += 2) {
            left = Math.min(left, path[i]);
            top = Math.min(top, path[i + 1]);
            right = Math.max(right, path[i]);
            bottom = Math.max(bottom, path[i + 1]);
        }
        return new Rect(left, top, right, bottom);
    }

    /** A proposed move: these cards to these spots. */
    private record Move(List<Integer> moved, List<GridPoint> to) {}

    /** The change a trial booked, for commit or undo. */
    private record Pending(List<Integer> touched, List<Integer> blockedOnly, List<Integer> moved, double oldPart,
        double newPart, double newExtras, double tidyDelta) {}

    /** One annealing run ({@code anneal}): the website's closures share these. */
    private final class Annealer {

        private final List<ArrangeCard> cards;
        private final List<LocalWire> wires;
        private final GridPoint[] start;
        private final RouterTuning prices;
        private final Rng random;
        private final Lattice lattice;
        private final Patterns patterns;
        private final int n, m;
        private final List<Integer> freeCards = new ArrayList<>();
        private final double gap;
        private GridPoint[] pos;
        private Rect[] rects;
        private List<Rect> rectList;
        private List<List<Integer>> wiresOf;
        private double[] weights;
        private ToDoubleFunction<Map<String, GridPoint>> air;
        private final List<List<Integer>> neighbours = new ArrayList<>();
        private final List<List<Integer>> bridgeSides = new ArrayList<>();
        private double[][] paths;
        private double[] own;
        private double[] flats;
        private List<Map<Integer, double[]>> portRows;
        private double[] tidy;
        private double tidyTotal;
        private double crossings;
        private double ownTotal;
        private double extras;
        private double score;
        private final List<GridPoint> savedPos = new ArrayList<>();
        private final Map<Integer, double[]> savedPaths = new HashMap<>();
        private final Map<Integer, Double> savedOwn = new HashMap<>();
        private final Map<Integer, Double> savedTidy = new LinkedHashMap<>();
        private Pending pendingDelta;
        // bridges
        private int[] disc, low;
        private int time = 0;
        private final List<Integer> bridges = new ArrayList<>();

        Annealer(final List<ArrangeCard> cards, final List<LocalWire> wires, final GridPoint[] start,
            final RouterTuning prices, final Rng random, final Lattice lattice, final Patterns patterns) {
            this.cards = cards;
            this.wires = wires;
            this.start = start;
            this.prices = prices;
            this.random = random;
            this.lattice = lattice;
            this.patterns = patterns;
            this.n = cards.size();
            this.m = wires.size();
            this.gap = cells(gapCells);
        }

        private double stepXOf(final int i) {
            return lattice.isColumnCard(i) ? lattice.pitch() : BOARD_GRID;
        }

        private void setRect(final int i, final GridPoint p) {
            pos[i] = p;
            final Rect r = rectAt(p, cards.get(i));
            rects[i] = r;
            rectList.set(i, r);
        }

        private double crossPrice(final int i, final int k) {
            return prices.crossing() * Math.max(weights[i], weights[k]);
        }

        private Map<String, GridPoint> positionsMap() {
            final Map<String, GridPoint> out = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) out.put(
                cards.get(i)
                    .id(),
                pos[i]);
            return out;
        }

        private int otherEnd(final int w, final int i) {
            final LocalWire wire = wires.get(w);
            return wire.a() == i ? wire.b() : wire.a();
        }

        private void dfs(final int u, final int viaWire) {
            disc[u] = low[u] = time++;
            for (final int w : wiresOf.get(u)) {
                if (w == viaWire) continue;
                final int v = otherEnd(w, u);
                if (disc[v] < 0) {
                    dfs(v, w);
                    low[u] = Math.min(low[u], low[v]);
                    if (low[v] > disc[u]) bridges.add(w);
                } else {
                    low[u] = Math.min(low[u], disc[v]);
                }
            }
        }

        /** The side reachable from one end without the bridge {@code w}. */
        private List<Integer> side(final int from, final int w) {
            final Set<Integer> seen = new HashSet<>();
            seen.add(from);
            final List<Integer> list = new ArrayList<>();
            list.add(from);
            for (int head = 0; head < list.size(); head += 1) {
                for (final int x : wiresOf.get(list.get(head))) {
                    if (x == w) continue;
                    final int v = otherEnd(x, list.get(head));
                    if (!seen.contains(v)) {
                        seen.add(v);
                        list.add(v);
                    }
                }
            }
            return list;
        }

        private double flowCost(final int w) {
            return flowAt(rects[wires.get(w)
                .a()],
                rects[wires.get(w)
                    .b()],
                gap,
                flats[w]);
        }

        private double priceOwn(final int w) {
            final LocalWire wire = wires.get(w);
            return (pathLength(paths[w]) + pathBends(paths[w], prices)
                + pathBlocked(paths[w], rects, wire.a(), wire.b(), prices)
                + flowCost(w)) * weights[w];
        }

        private double tidyOf(final int k) {
            return tidyAt(k, rects, portRows);
        }

        private double sprawl() {
            return sprawlOf(rects);
        }

        /** Drawers in a pattern line touch; everything else keeps the gap. */
        private double gapBetween(final int i, final int o) {
            return patterns.placement[i] != null && patterns.placement[o] != null ? 0 : gap;
        }

        /**
         * Tries moving the cards in {@code moved} to {@code to}; returns the score delta and keeps the move, or
         * restores everything and returns null when the cards would overlap something.
         */
        @Nullable
        private Double attempt(final List<Integer> moved, final List<GridPoint> to) {
            final Set<Integer> movedSet = new HashSet<>(moved);
            for (int k = 0; k < moved.size(); k += 1) {
                final int i = moved.get(k);
                final Rect rect = rectAt(to.get(k), cards.get(i));
                for (int o = 0; o < n; o += 1) {
                    if (movedSet.contains(o)) continue;
                    if (overlaps(rect, rects[o], gapBetween(i, o))) return null;
                }
                for (int j = 0; j < k; j += 1) {
                    final int other = moved.get(j);
                    final Rect otherRect = rectAt(to.get(j), cards.get(other));
                    if (overlaps(rect, otherRect, gapBetween(i, other))) return null;
                }
            }
            // The wires re-priced: those on the moved cards, and those whose path the cards stood across before or
            // stand across now.
            final Set<Integer> touched = new LinkedHashSet<>();
            for (final int i : moved) touched.addAll(wiresOf.get(i));
            final List<Rect> swept = new ArrayList<>();
            for (int k = 0; k < moved.size(); k += 1) {
                final int i = moved.get(k);
                swept.add(inflate(rects[i], BOARD_GRID));
                swept.add(inflate(rectAt(to.get(k), cards.get(i)), BOARD_GRID));
            }
            final List<Integer> blockedOnly = new ArrayList<>();
            for (int w = 0; w < m; w += 1) {
                if (touched.contains(w)) continue;
                final Rect box = bbox(paths[w]);
                for (final Rect sweep : swept) {
                    if (overlaps(box, sweep, 0)) {
                        blockedOnly.add(w);
                        break;
                    }
                }
            }
            // Cards whose tidiness may change: the moved ones and every card within reach of where they stood or
            // where they land.
            final List<Integer> tidyCards = new ArrayList<>();
            {
                final List<Rect> reach = new ArrayList<>(swept.size());
                for (final Rect s : swept) reach.add(inflate(s, TIDY_REACH));
                for (int c = 0; c < n; c += 1) {
                    if (movedSet.contains(c)) {
                        tidyCards.add(c);
                        continue;
                    }
                    for (final Rect r : reach) {
                        if (overlaps(rects[c], r, 0)) {
                            tidyCards.add(c);
                            break;
                        }
                    }
                }
            }
            double oldTidy = 0;
            for (final int c : tidyCards) oldTidy += tidy[c];
            final List<Integer> touchedList = new ArrayList<>(touched);
            double oldPart = 0;
            for (final int w : touchedList) oldPart += own[w];
            for (final int w : blockedOnly) oldPart += own[w];
            for (int a = 0; a < touchedList.size(); a += 1) {
                final int w = touchedList.get(a);
                for (int o = 0; o < m; o += 1) {
                    if (o == w) continue;
                    if (touched.contains(o) && o < w) continue;
                    oldPart += pathCrossings(paths[w], paths[o]) * crossPrice(w, o);
                }
            }
            // Apply.
            savedPos.clear();
            savedPaths.clear();
            savedOwn.clear();
            for (int k = 0; k < moved.size(); k += 1) {
                savedPos.add(pos[moved.get(k)]);
                setRect(moved.get(k), to.get(k));
            }
            for (final int w : touchedList) {
                savedPaths.put(w, paths[w]);
                savedOwn.put(w, own[w]);
                paths[w] = proxyPath(rects[wires.get(w)
                    .a()],
                    rects[wires.get(w)
                        .b()]);
                own[w] = priceOwn(w);
            }
            for (final int w : blockedOnly) {
                savedOwn.put(w, own[w]);
                own[w] = priceOwn(w);
            }
            double newPart = 0;
            for (final int w : touchedList) newPart += own[w];
            for (final int w : blockedOnly) newPart += own[w];
            for (int a = 0; a < touchedList.size(); a += 1) {
                final int w = touchedList.get(a);
                for (int o = 0; o < m; o += 1) {
                    if (o == w) continue;
                    if (touched.contains(o) && o < w) continue;
                    newPart += pathCrossings(paths[w], paths[o]) * crossPrice(w, o);
                }
            }
            savedTidy.clear();
            double newTidy = 0;
            for (final int c : tidyCards) {
                savedTidy.put(c, tidy[c]);
                tidy[c] = tidyOf(c);
                newTidy += tidy[c];
            }
            final double newExtras = air.applyAsDouble(positionsMap()) + sprawl();
            final double delta = newPart - oldPart + (newExtras - extras) + (newTidy - oldTidy);
            // Book the change; the caller reverts by undo if it refuses.
            pendingDelta = new Pending(touchedList, blockedOnly, moved, oldPart, newPart, newExtras, newTidy - oldTidy);
            return delta;
        }

        private void commit() {
            final Pending p = pendingDelta;
            double oldOwn = 0;
            for (final int w : p.touched()) oldOwn += savedOwn.get(w);
            for (final int w : p.blockedOnly()) oldOwn += savedOwn.get(w);
            double newOwn = 0;
            for (final int w : p.touched()) newOwn += own[w];
            for (final int w : p.blockedOnly()) newOwn += own[w];
            ownTotal += newOwn - oldOwn;
            crossings += p.newPart() - newOwn - (p.oldPart() - oldOwn);
            extras = p.newExtras();
            tidyTotal += p.tidyDelta();
            score = ownTotal + crossings + extras + tidyTotal;
            pendingDelta = null;
        }

        private void undo() {
            final Pending p = pendingDelta;
            for (int k = 0; k < p.moved()
                .size(); k += 1) setRect(
                    p.moved()
                        .get(k),
                    savedPos.get(k));
            for (final int w : p.touched()) {
                paths[w] = savedPaths.get(w);
                own[w] = savedOwn.get(w);
            }
            for (final int w : p.blockedOnly()) own[w] = savedOwn.get(w);
            for (final Map.Entry<Integer, Double> entry : savedTidy.entrySet()) tidy[entry.getKey()] = entry.getValue();
            pendingDelta = null;
        }

        private int pick(final int max) {
            return (int) Math.floor(random.next() * max);
        }

        @Nullable
        private GridPoint fitNear(final int i, final GridPoint want, final Set<Integer> exclude) {
            return nearestFree(quantise(lattice, i, want), cards.get(i), rectList, gap, 3, exclude::contains, stepXOf(i));
        }

        /** Spots beside partner {@code p} for card {@code i}: four sides, rows aligned. */
        private List<GridPoint> besideSpots(final int i, final int p, final int w) {
            final ArrangeCard a = cards.get(i);
            final ArrangeCard b = cards.get(p);
            final GridPoint at = pos[p];
            final List<GridPoint> spots = new ArrayList<>();
            final List<Double> rowsY = new ArrayList<>();
            rowsY.add(at.y());
            rowsY.add(at.y() + b.height() - a.height());
            rowsY.add(at.y() + (b.height() - a.height()) / 2);
            // Port rows aligned: the straight shot the router prices lowest.
            final LocalWire local = wires.get(w);
            final ArrangeWire wire = local.wire();
            final Double ownPort = local.a() == i ? wire.sourcePortY() : wire.targetPortY();
            final Double theirPort = local.a() == p ? wire.sourcePortY() : wire.targetPortY();
            if (ownPort != null && theirPort != null) rowsY.add(at.y() + theirPort - ownPort);
            for (final double y : rowsY) {
                spots.add(new GridPoint(at.x() - a.width() - cells(besideCells), y));
                spots.add(new GridPoint(at.x() + b.width() + cells(besideCells), y));
            }
            final double[] colsX = { at.x(), at.x() + b.width() - a.width(), at.x() + (b.width() - a.width()) / 2 };
            for (final double x : colsX) {
                spots.add(new GridPoint(x, at.y() - a.height() - cells(gapCells)));
                spots.add(new GridPoint(x, at.y() + b.height() + cells(gapCells)));
            }
            final List<GridPoint> out = new ArrayList<>(spots.size());
            for (final GridPoint s : spots) out.add(quantise(lattice, i, s));
            return out;
        }

        /**
         * A move of free cards, completed: every derived drawer attached to a moved machine (or to a machine sharing a
         * drawer with one) is re-placed from the machines' new positions and rides along in the same trial.
         */
        private Move expand(final List<Integer> moved, final List<GridPoint> to) {
            final Map<Integer, GridPoint> override = new HashMap<>();
            for (int k = 0; k < moved.size(); k++) override.put(moved.get(k), to.get(k));
            final List<Integer> machines = new ArrayList<>();
            for (final int i : moved) if (!patterns.attached.get(i)
                .isEmpty()
                || !patterns.pairPartners.get(i)
                    .isEmpty())
                machines.add(i);
            if (machines.isEmpty()) return new Move(moved, to);
            final Map<Integer, GridPoint> derived = derivePatterns(cards, patterns, machines, i -> {
                final GridPoint o = override.get(i);
                return o != null ? o : pos[i];
            });
            final List<Integer> allMoved = new ArrayList<>(moved);
            final List<GridPoint> allTo = new ArrayList<>(to);
            for (final Map.Entry<Integer, GridPoint> entry : derived.entrySet()) {
                if (override.containsKey(entry.getKey())) continue;
                allMoved.add(entry.getKey());
                allTo.add(entry.getValue());
            }
            return new Move(allMoved, allTo);
        }

        private boolean isOwnDrawer(final int i, final int p) {
            return patterns.attached.get(i)
                .contains(p);
        }

        @Nullable
        private Move propose() {
            final double roll = random.next();
            final int i = freeCards.get(pick(freeCards.size()));
            if (roll < 0.45) {
                // Beside a partner (never beside a drawer of its own: that follows it).
                final List<Integer> partnerWires = new ArrayList<>();
                for (final int w : wiresOf.get(i)) if (!isOwnDrawer(i, otherEnd(w, i))) partnerWires.add(w);
                if (partnerWires.isEmpty()) return null;
                final int w = partnerWires.get(pick(partnerWires.size()));
                final int p = otherEnd(w, i);
                final List<GridPoint> spots = besideSpots(i, p, w);
                final GridPoint want = spots.get(pick(spots.size()));
                final GridPoint spot = fitNear(i, want, Set.of(i));
                return spot != null ? new Move(List.of(i), List.of(spot)) : null;
            }
            if (roll < 0.7) {
                // A nudge: cells vertically; a column, or cells for a drawer, sideways.
                final double sign = random.next() < 0.5 ? -1 : 1;
                final GridPoint to;
                if (random.next() < 0.5) {
                    to = new GridPoint(
                        pos[i].x() + sign * (lattice.isColumnCard(i) ? lattice.pitch() : (1 + pick(6)) * BOARD_GRID),
                        pos[i].y());
                } else {
                    to = new GridPoint(pos[i].x(), pos[i].y() + sign * (1 + pick(6)) * BOARD_GRID);
                }
                return new Move(List.of(i), List.of(to));
            }
            if (roll < 0.9) {
                // A swap.
                final int k = freeCards.get(pick(freeCards.size()));
                if (k == i) return null;
                return new Move(List.of(i, k), List.of(quantise(lattice, i, pos[k]), quantise(lattice, k, pos[i])));
            }
            if (!bridgeSides.isEmpty() && random.next() < 0.5) {
                // A whole side of a bridge wire, shifted together.
                final List<Integer> group = new ArrayList<>();
                for (final int g : bridgeSides.get(pick(bridgeSides.size()))) if (patterns.placement[g] == null)
                    group.add(g);
                if (group.isEmpty()) return null;
                return shift(group, 6);
            }
            // A neighbourhood shifted together, by a column or by cells.
            final List<Integer> group = new ArrayList<>();
            if (patterns.placement[i] == null) group.add(i);
            for (final int g : neighbours.get(i)) if (patterns.placement[g] == null) group.add(g);
            return shift(group, 4);
        }

        /** A group shifted together, by a column or by up to {@code cellsUpTo} cells. */
        private Move shift(final List<Integer> group, final int cellsUpTo) {
            final double sign = random.next() < 0.5 ? -1 : 1;
            boolean columnMove = false;
            for (final int g : group) if (lattice.isColumnCard(g)) columnMove = true;
            final double dx = random.next() < 0.5
                ? sign * (columnMove ? lattice.pitch() : (1 + pick(cellsUpTo)) * BOARD_GRID)
                : 0;
            final double dy = dx == 0 ? sign * (1 + pick(cellsUpTo)) * BOARD_GRID : 0;
            final List<GridPoint> to = new ArrayList<>(group.size());
            for (final int g : group) to.add(new GridPoint(pos[g].x() + dx, pos[g].y() + dy));
            return new Move(group, to);
        }

        GridPoint[] run(final double trials, final DoubleConsumer onProgress, final double heat) {
            // The cards the search moves; derived drawers follow their machines.
            for (int i = 0; i < n; i++) if (patterns.placement[i] == null) freeCards.add(i);
            if (freeCards.isEmpty()) {
                return start.clone();
            }
            pos = start.clone();
            rects = new Rect[n];
            rectList = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                rects[i] = rectAt(pos[i], cards.get(i));
                rectList.add(rects[i]);
            }
            wiresOf = wiresOf(n, wires);
            weights = new double[m];
            for (int w = 0; w < m; w++) weights[w] = weightOf(
                wires.get(w)
                    .wire());
            final List<ArrangeWire> airWires = new ArrayList<>(m);
            for (final LocalWire w : wires) airWires.add(
                new ArrangeWire(
                    null,
                    w.wire()
                        .source(),
                    w.wire()
                        .target(),
                    null,
                    null,
                    null,
                    null));
            air = Air.makeAirTerm(airCards(cards), airWires, prices.islandAir());

            for (int i = 0; i < n; i++) {
                final Set<Integer> unique = new LinkedHashSet<>();
                for (final int w : wiresOf.get(i)) unique.add(otherEnd(w, i));
                neighbours.add(new ArrayList<>(unique));
            }
            // BRIDGES: wires whose loss would split the component. The cards on the smaller side of one form a natural
            // island, and shifting them as one is the move that lets an island drift out (the air term asks for it; no
            // one-card move can do it, because every card in the cluster holds the others in place).
            {
                disc = new int[n];
                Arrays.fill(disc, -1);
                low = new int[n];
                for (int u = 0; u < n; u += 1) if (disc[u] < 0) dfs(u, -1);
                for (final int w : bridges) {
                    // The side reachable from one end without the bridge; keep the smaller.
                    final List<Integer> left = side(
                        wires.get(w)
                            .a(),
                        w);
                    final List<Integer> right = side(
                        wires.get(w)
                            .b(),
                        w);
                    final List<Integer> smaller = left.size() <= right.size() ? left : right;
                    if (smaller.size() >= 2 && smaller.size() < n) bridgeSides.add(smaller);
                }
            }

            // The books: each wire's path and own price, every crossing pair's price.
            paths = new double[m][];
            own = new double[m];
            flats = flatPrices(n, wires, lattice);
            // Tidiness per card: recomputed only for cards near a move.
            portRows = portRowsOf(n, wires);
            tidy = new double[n];
            for (int k = 0; k < n; k += 1) tidy[k] = tidyOf(k);
            tidyTotal = 0;
            for (final double v : tidy) tidyTotal = tidyTotal + v;
            for (int w = 0; w < m; w += 1) {
                paths[w] = proxyPath(rects[wires.get(w)
                    .a()],
                    rects[wires.get(w)
                        .b()]);
                own[w] = priceOwn(w);
            }
            crossings = 0;
            for (int i = 0; i < m; i += 1) {
                for (int k = i + 1; k < m; k += 1) {
                    crossings += pathCrossings(paths[i], paths[k]) * crossPrice(i, k);
                }
            }
            ownTotal = 0;
            for (final double v : own) ownTotal = ownTotal + v;
            extras = air.applyAsDouble(positionsMap()) + sprawl();
            score = ownTotal + crossings + extras + tidyTotal;
            double best = score;
            GridPoint[] bestPos = pos.clone();

            // Temperature from the moves themselves: the median uphill step, so the schedule means the same on a tiny
            // board as on a huge one.
            final List<Double> samples = new ArrayList<>();
            for (int s = 0; s < 200 && samples.size() < 60; s += 1) {
                final Move proposed = propose();
                if (proposed == null) continue;
                final Move move = expand(proposed.moved(), proposed.to());
                final Double delta = attempt(move.moved(), move.to());
                if (delta == null) continue;
                undo();
                if (delta > 0) samples.add(delta);
            }
            JsSort.sort(samples, (a, b) -> JsMath.sign(a - b));
            final double t0 = Math
                .max(1, (!samples.isEmpty() ? samples.get((int) Math.floor(samples.size() / 2.0)) * 2 : 200) * heat);
            final double tEnd = t0 / 2000;
            final double cool = StrictMath.log(tEnd / t0) / Math.max(1, trials);
            for (int trial = 0; trial < trials; trial += 1) {
                final double temperature = t0 * StrictMath.exp(cool * trial);
                final Move proposed = propose();
                if (proposed == null) continue;
                final Move move = expand(proposed.moved(), proposed.to());
                final Double delta = attempt(move.moved(), move.to());
                if (delta == null) continue;
                if (delta <= 0 || random.next() < StrictMath.exp(-delta / temperature)) {
                    commit();
                    if (score < best - 1e-6) {
                        best = score;
                        bestPos = pos.clone();
                    }
                } else {
                    undo();
                }
                if (trial % 500 == 0) onProgress.accept(trial);
            }
            onProgress.accept(trials);
            return bestPos;
        }
    }

    // endregion
}
