package com.gtnhplanner.core.layout.arrange;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

import javax.annotation.Nullable;

import com.gtnhplanner.core.layout.GridEdgeRouter;
import com.gtnhplanner.core.layout.GridPoint;
import com.gtnhplanner.core.layout.JsMath;
import com.gtnhplanner.core.layout.RouterTuning;
import com.gtnhplanner.core.layout.WireMetrics;

/**
 * Auto-arrange for the visible board: the website's board-arrange.ts entry points (and arrange-solve.ts's staged run),
 * line for line.
 *
 * <p>
 * The COLUMN PASS ({@link ColumnArrange}) treats a plan as almost a tree: flow runs left to right, the trunk through
 * the middle, every feeder chain a section kept as one band. {@link #arrangeBoard} runs that pass plain and with the
 * optimiser ({@link Optimize}), plus a column-free placement ({@link FreeArrange}), and picks by the router's points.
 * Deterministic: same cards and wires in, same layout out. Everything lands on the 20px grid.
 */
public final class Arrange {

    private Arrange() {}

    /** The router's verdict on a layout ({@code judge}'s answer); {@code events} empty when the judge does not say. */
    public record Verdict(int crossings, double length, double points, List<WireMetrics.Crossing> events) {}

    /**
     * {@code ArrangeInput.judge}: routes the board's real wires with every card at the given top-left. A quick verdict
     * ({@code quick}) against {@code base} may keep the base's routes for wires the change does not touch.
     */
    @FunctionalInterface
    public interface Judge {

        Verdict judge(Map<String, GridPoint> positions, boolean quick, @Nullable Map<String, GridPoint> base);
    }

    /** {@code onProgress}'s argument. */
    public record Progress(int step, String stage, double done, double total) {}

    @FunctionalInterface
    public interface ProgressSink {

        void accept(Progress progress);
    }

    /**
     * The host's second thread for the staged run (arrange-solve.ts runs the column candidates beside the free
     * placement, then the two polishes, on two workers). Null: one thread. Becomes core-base's Worker when the kernel's
     * lands.
     */
    public interface Worker {

        <T> Job<T> start(Supplier<T> work);

        default void stop() {}
    }

    public interface Job<T> {

        T await();
    }

    /**
     * {@code ArrangeInput}. Null fields are absent. {@code spacing} is the taste ("compact", "normal", "roomy"; null:
     * normal). {@code clock}: the polish's wall clock (null: the host's real one); a clock that stands still makes the
     * polish stop on its count alone. {@code tuning} null: {@link RouterTuning#DEFAULT}.
     */
    public record Input(List<ArrangeCard> cards, List<ArrangeWire> wires, @Nullable List<ArrangeInk> ink,
        @Nullable GridPoint origin, @Nullable String spacing, @Nullable Judge judge, @Nullable Double polishBudget,
        @Nullable RouterTuning tuning, @Nullable ProgressSink onProgress, @Nullable GridEdgeRouter.Clock clock,
        @Nullable Worker worker) {

        Input withJudge(@Nullable final Judge newJudge) {
            return new Input(
                cards,
                wires,
                ink,
                origin,
                spacing,
                newJudge,
                polishBudget,
                tuning,
                onProgress,
                clock,
                worker);
        }

        Input withProgress(@Nullable final ProgressSink sink) {
            return new Input(cards, wires, ink, origin, spacing, judge, polishBudget, tuning, sink, clock, worker);
        }
    }

    /**
     * The arrange was called off: a host's {@link ProgressSink} (or {@link Steps}) throws it to stop an arrange under
     * way, and it leaves the arrange as it is thrown.
     */
    public static final class Cancelled extends RuntimeException {

        public Cancelled() {
            super("arrange cancelled", null, false, false);
        }
    }

    /** {@code ArrangeCandidates}. */
    public record Candidates(ArrangeResult plain, ArrangeResult challenger, ArrangeResult free) {}

    /** {@code arrangeColumnStage}'s answer. */
    public record Columns(ArrangeResult plain, ArrangeResult challenger) {}

    /** {@code arrangePolishStage}'s answer. */
    public record Polished(ArrangeResult result, double points, int crossings) {}

    private static void progress(final Input input, final int step, final String stage, final double done,
        final double total) {
        if (input.onProgress() != null) input.onProgress()
            .accept(new Progress(step, stage, done, total));
    }

    private static Map<String, GridPoint> positionsOf(final ArrangeResult result) {
        final Map<String, GridPoint> positions = new LinkedHashMap<>();
        for (final ArrangeResult.Move move : result.moves()) positions.put(move.id(), move.position());
        return positions;
    }

    /** An input after prepareArrange: the judge's points carry the stranger air. */
    private record Prepared(Input input, ToDoubleFunction<Map<String, GridPoint>> airOf) {}

    /**
     * Three candidates: the plain column pass, the column pass with the optimiser (the challenger), and the free
     * placement. The stranger air (board-arrange-air.ts) is added to the judge's points here so every stage (search
     * proxy, finalists, polish, final choice) optimises the same objective and the judge never undoes the search.
     */
    private static Prepared prepareArrange(final ColumnArrange st, final Input rawInput) {
        st.arrangePrices = ColumnArrange
            .arrangePrices(rawInput.tuning() != null ? rawInput.tuning() : RouterTuning.DEFAULT);
        st.arrangeAir = Air.makeAirTerm(rawInput.cards(), rawInput.wires(), st.arrangePrices.islandAir());
        final ToDoubleFunction<Map<String, GridPoint>> airOf = positions -> st.arrangeAir.applyAsDouble(positions);
        final Judge raw = rawInput.judge();
        final Input input = raw != null ? rawInput.withJudge((positions, quick, base) -> {
            final Verdict verdict = raw.judge(positions, quick, base);
            return new Verdict(
                verdict.crossings(),
                verdict.length(),
                verdict.points() + airOf.applyAsDouble(positions),
                verdict.events());
        }) : rawInput;
        return new Prepared(input, airOf);
    }

    private record Ranked(String name, ArrangeResult result, Verdict verdict) {}

    private static Verdict verdict(final Input input, final ArrangeResult result) {
        return input.judge()
            .judge(positionsOf(result), false, null);
    }

    /**
     * The three candidates routed by the judge, best first. The best two are polished; every stage that needs the
     * ranking recomputes it from the same candidates, so the parallel stages agree with the serial one.
     */
    private static List<Ranked> rankCandidates(final Input input, final Candidates candidates) {
        final List<Ranked> ranked = new ArrayList<>();
        ranked.add(new Ranked("plain", candidates.plain(), verdict(input, candidates.plain())));
        ranked.add(new Ranked("challenger", candidates.challenger(), verdict(input, candidates.challenger())));
        ranked.add(new Ranked("free", candidates.free(), verdict(input, candidates.free())));
        ranked.sort((a, b) -> JsMath.sign(fewerCrossings(a.verdict(), b.verdict())));
        return ranked;
    }

    /**
     * The arrange in three parallel-friendly stages: the column candidates (plain pass and challenger), the free
     * placement, and one of the two polishes. Run serially they are exactly {@link #arrangeBoard}.
     */
    public static Columns arrangeColumnStage(final Input rawInput) {
        final ColumnArrange st = new ColumnArrange();
        final Input input = prepareArrange(st, rawInput).input();
        progress(input, 0, "Laying the board out", 0, 1);
        final ArrangeResult plain = st.arrangeBoardOnce(input, false);
        if (input.cards()
            .size() < 2) {
            return new Columns(plain, plain);
        }
        progress(input, 1, "Searching for a better layout", 0, 1);
        st.arrangeSearchProgress = (done, total) -> progress(input, 1, "Searching for a better layout", done, total);
        final ArrangeResult challenger = st.arrangeBoardOnce(input, true);
        st.arrangeSearchProgress = null;
        return new Columns(plain, challenger);
    }

    public static ArrangeResult arrangeFreeStage(final Input rawInput) {
        final ColumnArrange st = new ColumnArrange();
        final Input input = prepareArrange(st, rawInput).input();
        // The column pass sets the spacing the free placement reads.
        st.applyTaste(input.spacing());
        st.applyArrangeDials(st.arrangePrices);
        return arrangeFreeCandidate(st, input);
    }

    /** Polishes the {@code which}-th best candidate (0 or 1); its finished score rides along. */
    public static Polished arrangePolishStage(final Input rawInput, final Candidates candidates, final int which) {
        final ColumnArrange st = new ColumnArrange();
        final Input input = prepareArrange(st, rawInput).input();
        final List<Ranked> ranked = rankCandidates(input, candidates);
        final ArrangeResult polished = polishWithJudge(
            st,
            input,
            ranked.get(which)
                .result(),
            ranked.get(which)
                .verdict(),
            which == 0 ? "first" : "second");
        final Verdict last = verdict(input, polished);
        return new Polished(polished, last.points(), last.crossings());
    }

    public static ArrangeResult arrangeBoard(final Input rawInput) {
        final ColumnArrange st = new ColumnArrange();
        final Prepared prepared = prepareArrange(st, rawInput);
        final Input input = prepared.input();
        progress(input, 0, "Laying the board out", 0, 1);
        final ArrangeResult plain = st.arrangeBoardOnce(input, false);
        if (input.cards()
            .size() < 2) {
            return plain;
        }
        if (input.judge() == null) {
            // No router at hand: the proxy chooses among the plain pass, the challenger and the free placement, the
            // way it chose among the challenger's own trials.
            final ArrangeResult challenger = st.arrangeBoardOnce(input, true);
            final ArrangeResult free = arrangeFreeCandidate(st, input);
            // The proxy is a pure function of the layout, so each candidate's is computed once.
            final ArrangeResult[] candidates = { plain, challenger, free };
            final List<double[]> scored = new ArrayList<>();
            for (int i = 0; i < candidates.length; i++) {
                scored.add(new double[] { proxy(st, input, prepared.airOf(), candidates[i]), i });
            }
            scored.sort((a, b) -> JsMath.sign(a[0] - b[0]));
            return candidates[(int) scored.get(0)[1]];
        }
        progress(input, 1, "Searching for a better layout", 0, 1);
        st.arrangeSearchProgress = (done, total) -> progress(input, 1, "Searching for a better layout", done, total);
        final ArrangeResult challenger = st.arrangeBoardOnce(input, true);
        st.arrangeSearchProgress = null;
        // The third candidate: no columns at all (board-arrange-free.ts).
        final ArrangeResult free = arrangeFreeCandidate(st, input);
        progress(input, 2, "Routing the candidates", 0, 1);
        return polishBoth(st, input, new Candidates(plain, challenger, free), null);
    }

    /**
     * The two best by the router's verdict are polished - they start from different structures and the polish is
     * greedy, so each can reach a place the other cannot - and the better finished board wins. {@code fold} null:
     * the polishes report on the input's own sink.
     */
    private static ArrangeResult polishBoth(final ColumnArrange st, final Input input, final Candidates candidates,
        @Nullable final PolishFold fold) {
        final List<Ranked> ranked = rankCandidates(input, candidates);
        final ArrangeResult polishedFirst = polishWithJudge(
            st,
            fold != null ? input.withProgress(fold::first) : input,
            ranked.get(0)
                .result(),
            ranked.get(0)
                .verdict(),
            "first");
        if (fold != null) fold.firstFinished();
        final ArrangeResult polishedSecond = polishWithJudge(
            st,
            fold != null ? input.withProgress(fold::second) : input,
            ranked.get(1)
                .result(),
            ranked.get(1)
                .verdict(),
            "second");
        progress(input, 5, "Choosing the better board", 1, 1);
        final Verdict finalFirst = verdict(input, polishedFirst);
        final Verdict finalSecond = verdict(input, polishedSecond);
        return fewerCrossings(finalSecond, finalFirst) < 0 ? polishedSecond : polishedFirst;
    }

    /**
     * Which of two judged boards the arrange keeps (negative: {@code a}). Players read crossings first, so here a
     * crossing counts PICK_CROSSING_EXTRA px of wire on top of its price in the points; the search and the polish
     * still trade at the router's price, which keeps their moves small.
     */
    public static double fewerCrossings(final Verdict a, final Verdict b) {
        return a.points() + PICK_CROSSING_EXTRA * a.crossings() - (b.points() + PICK_CROSSING_EXTRA * b.crossings());
    }

    public static final double PICK_CROSSING_EXTRA = 1600;

    private static double proxy(final ColumnArrange st, final Input input,
        final ToDoubleFunction<Map<String, GridPoint>> airOf, final ArrangeResult result) {
        final List<Optimize.OptimizeWire> wires = new ArrayList<>();
        for (final ArrangeWire wire : input.wires())
            wires.add(new Optimize.OptimizeWire(wire.source(), wire.target(), wire.weight(), wire.width()));
        return Optimize.scoreLayoutProxy(input.cards(), wires, positionsOf(result), st.arrangePrices, airOf);
    }

    /** The free placement as an ArrangeResult: component boxes, no steering. */
    private static ArrangeResult arrangeFreeCandidate(final ColumnArrange st, final Input input) {
        final RouterTuning prices = st.arrangePrices != null ? st.arrangePrices : RouterTuning.DEFAULT;
        final String spacing = input.spacing() != null ? input.spacing() : "normal";
        final FreeArrange.FreeResult placed = FreeArrange.arrangeFree(
            input.cards(),
            input.wires(),
            new FreeArrange.FreeOptions(
                prices,
                // Per card, not per board: a trial is one card's move re-priced incrementally, so a bigger board
                // needs proportionally more of them.
                Math.max(
                    prices.searchTrials(),
                    1500 * (double) input.cards()
                        .size()),
                st.rowGap / ColumnArrange.BOARD_GRID,
                spacing.equals("compact") ? 2.0 : spacing.equals("roomy") ? 5.0 : 3.0,
                (done, total) -> progress(input, 1, "Placing freely", done, total)));
        final GridPoint origin = input.origin() != null ? input.origin()
            : ColumnArrange.boundingTopLeft(input.cards());
        final Map<String, GridPoint> newById = new LinkedHashMap<>();
        final List<ArrangeResult.Move> moves = new ArrayList<>();
        for (final ArrangeCard card : input.cards()) {
            final GridPoint found = placed.positions()
                .get(card.id());
            final GridPoint p = found != null ? found : new GridPoint(0, 0);
            final GridPoint position = new GridPoint(origin.x() + p.x(), origin.y() + p.y());
            newById.put(card.id(), position);
            moves.add(new ArrangeResult.Move(card.id(), position));
        }
        ColumnArrange.followInk(input, newById, moves);
        final List<ArrangeResult.Island> islands = new ArrayList<>();
        for (final ArrangeResult.Island island : placed.islands()) {
            islands.add(
                new ArrangeResult.Island(
                    origin.x() + island.x(),
                    origin.y() + island.y(),
                    island.width(),
                    island.height(),
                    island.backdrop()));
        }
        return new ArrangeResult(moves, islands, new ArrayList<>());
    }

    /**
     * The column candidates alone, plain versus challenger, chosen the way the whole arrange chooses. For tests of the
     * column pass's own mechanisms; the arrange itself is {@link #arrangeBoard}.
     */
    public static ArrangeResult arrangeBoardColumns(final Input rawInput) {
        final ColumnArrange st = new ColumnArrange();
        st.arrangePrices = ColumnArrange
            .arrangePrices(rawInput.tuning() != null ? rawInput.tuning() : RouterTuning.DEFAULT);
        st.arrangeAir = Air.makeAirTerm(rawInput.cards(), rawInput.wires(), st.arrangePrices.islandAir());
        final ToDoubleFunction<Map<String, GridPoint>> airOf = positions -> st.arrangeAir.applyAsDouble(positions);
        final ArrangeResult plain = st.arrangeBoardOnce(rawInput, false);
        if (rawInput.cards()
            .size() < 2) {
            return plain;
        }
        final ArrangeResult challenger = st.arrangeBoardOnce(rawInput, true);
        return proxy(st, rawInput, airOf, challenger) < proxy(st, rawInput, airOf, plain) ? challenger : plain;
    }

    /**
     * Wall-clock allowance for one polish, on top of its count of judge calls: on a big board one verdict can take
     * seconds. Two polishes run per arrange.
     */
    private static final double POLISH_TIME_MS = 8_000;

    private static double now(final Input input) {
        return input.clock() != null ? input.clock()
            .nowMs() : System.nanoTime() / 1e6;
    }

    private record Size(double width, double height) {}

    /**
     * The exact polish: the real router says where wires still cross (or, with none, which wires are longest), and
     * the cards on them are tried elsewhere: beside a partner on any side, level with it in their own column, or
     * swapped with a card in their column, their buds riding along. The first try that lowers the router's points is
     * taken and the search repeats until nothing helps or the budget runs out. Every try keeps the grid and the
     * no-overlap rule.
     */
    private static ArrangeResult polishWithJudge(final ColumnArrange st, final Input input, final ArrangeResult result,
        final Verdict verdict, final String which) {
        final Judge judge = input.judge();
        if (judge == null) {
            return result;
        }
        final double fullBudget = input.polishBudget() != null ? input.polishBudget()
            : st.arrangePrices != null ? st.arrangePrices.polishBudget() : 100;
        final double[] budget = { fullBudget };
        // Past the deadline the verdict under way finishes and no further one is asked for. The progress bar reads
        // whichever of budget and time is further along, so it keeps moving on a slow board.
        final double startedAt = now(input);
        final double deadline = startedAt + POLISH_TIME_MS;
        final Runnable spend = () -> budget[0] = now(input) > deadline ? 0 : budget[0] - 1;
        final Runnable report = () -> {
            if (input.onProgress() == null) return;
            input.onProgress()
                .accept(
                    new Progress(
                        which.equals("first") ? 3 : 4,
                        // The step label already says which layout; this is the detail line beside it.
                        verdict.crossings() + " crossings to start",
                        Math.min(
                            fullBudget,
                            Math.max(
                                fullBudget - budget[0],
                                (fullBudget * (now(input) - startedAt)) / POLISH_TIME_MS)),
                        fullBudget));
        };
        report.run();
        final Map<String, Size> sizeById = new LinkedHashMap<>();
        for (final ArrangeCard card : input.cards()) sizeById.put(card.id(), new Size(card.width(), card.height()));
        final Map<String, List<ArrangeWire>> wiresOf = new HashMap<>();
        for (final ArrangeWire wire : input.wires()) {
            ColumnArrange.push(wiresOf, wire.source(), wire);
            ColumnArrange.push(wiresOf, wire.target(), wire);
        }
        final Map<String, ArrangeWire> wireById = new HashMap<>();
        for (final ArrangeWire wire : input.wires()) {
            if (wire.id() != null && !wire.id()
                .isEmpty()) wireById.put(wire.id(), wire);
        }
        final Map<String, GridPoint> positions = positionsOf(result);
        // The search runs on quick verdicts against a fully judged BASE: a move is tried with only its own wires
        // re-solved, and when it wins, the new layout gets the full verdict and becomes the base.
        Map<String, GridPoint> base = new LinkedHashMap<>(positions);
        Verdict best = verdict;
        final Map<String, GridPoint> startPositions = new LinkedHashMap<>(positions);
        final Verdict startVerdict = verdict;
        final double gap = ColumnArrange.BOARD_GRID;
        final Set<String> tried = new HashSet<>();
        // A machine moves with the drawers that serve only it, the way a hand drags a machine and its buds together.
        final Map<String, List<String>> budsOf = new HashMap<>();
        for (final Map.Entry<String, Size> e : sizeById.entrySet()) {
            final String id = e.getKey();
            if (e.getValue()
                .width() > ColumnArrange.cells(6)) continue;
            final Set<String> partners = new LinkedHashSet<>();
            for (final ArrangeWire wire : wiresOf.getOrDefault(id, List.of()))
                partners.add(wire.source().equals(id) ? wire.target() : wire.source());
            if (partners.size() == 1) {
                final String anchor = partners.iterator()
                    .next();
                if (sizeById.get(anchor)
                    .width() > ColumnArrange.cells(6)) ColumnArrange.push(budsOf, anchor, id);
            }
        }
        for (int round = 0; round < 40 && budget[0] > 0; round += 1) {
            // Cards on crossing wires, the most-crossed first; drawers before machines because a drawer is cheap to
            // move.
            final Map<String, Double> blame = new LinkedHashMap<>();
            // The far end of each crossing wire, per card: the partner a move should bring the card toward.
            final Map<String, Set<String>> crossingPartners = new HashMap<>();
            if (best.events() != null) {
                for (final WireMetrics.Crossing event : best.events()) {
                    for (final String edgeId : new String[] { event.a(), event.b() }) {
                        final ArrangeWire wire = wireById.get(edgeId);
                        if (wire == null) continue;
                        blame.put(wire.source(), blame.getOrDefault(wire.source(), 0.0) + 1);
                        blame.put(wire.target(), blame.getOrDefault(wire.target(), 0.0) + 1);
                        crossingPartners.computeIfAbsent(wire.source(), k -> new HashSet<>())
                            .add(wire.target());
                        crossingPartners.computeIfAbsent(wire.target(), k -> new HashSet<>())
                            .add(wire.source());
                    }
                }
            }
            // Nothing crosses: the cards on the longest wires are the suspects, since every remaining point is wire
            // and bends.
            if (blame.isEmpty()) {
                for (final ArrangeWire wire : input.wires()) {
                    final GridPoint a = positions.get(wire.source());
                    final GridPoint b = positions.get(wire.target());
                    if (a == null || b == null) continue;
                    final double span = Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y());
                    blame.put(wire.source(), Math.max(blame.getOrDefault(wire.source(), 0.0), span));
                    blame.put(wire.target(), Math.max(blame.getOrDefault(wire.target(), 0.0), span));
                }
                if (blame.isEmpty()) break;
            }
            // The most-crossed card first: it is the one standing in the wrong place. Drawers break ties, being cheap
            // to move.
            final List<String> suspects = new ArrayList<>(blame.keySet());
            suspects.sort((a, b) -> {
                final int storageA = sizeById.get(a)
                    .width() <= ColumnArrange.cells(6) ? 1 : 0;
                final int storageB = sizeById.get(b)
                    .width() <= ColumnArrange.cells(6) ? 1 : 0;
                return JsMath.sign(
                    ColumnArrange.or(
                        blame.get(b) - blame.get(a),
                        ColumnArrange.or(storageB - storageA, a.compareTo(b) < 0 ? -1 : 1)));
            });
            boolean improved = false;
            for (final String id : suspects) {
                if (improved || budget[0] <= 0) break;
                final Size size = sizeById.get(id);
                final List<String> partners = new ArrayList<>();
                {
                    final Set<String> distinct = new LinkedHashSet<>();
                    for (final ArrangeWire wire : wiresOf.getOrDefault(id, List.of()))
                        distinct.add(wire.source().equals(id) ? wire.target() : wire.source());
                    for (final String partner : distinct) if (positions.containsKey(partner)) partners.add(partner);
                }
                final List<GridPoint> candidates = new ArrayList<>();
                final GridPoint own = positions.get(id);
                final List<String> group = groupOf(budsOf, id);
                for (final String partner : partners) {
                    final GridPoint at = positions.get(partner);
                    final Size partnerSize = sizeById.get(partner);
                    final double[] rows = { at.y(), at.y() + partnerSize.height() - size.height(),
                        at.y() + (partnerSize.height() - size.height()) / 2 };
                    final double[] columns = { at.x(), at.x() + partnerSize.width() - size.width(),
                        at.x() + (partnerSize.width() - size.width()) / 2 };
                    for (final double y : rows) {
                        add(id, at.x() - size.width() - 2 * gap, y, tried, positions, budsOf, sizeById, candidates);
                        add(id, at.x() + partnerSize.width() + 2 * gap, y, tried, positions, budsOf, sizeById,
                            candidates);
                        // A slide in its own column, level with the partner.
                        add(id, own.x(), y, tried, positions, budsOf, sizeById, candidates);
                    }
                    for (final double x : columns) {
                        add(id, x, at.y() - size.height() - 2 * gap, tried, positions, budsOf, sizeById, candidates);
                        add(id, x, at.y() + partnerSize.height() + 2 * gap, tried, positions, budsOf, sizeById,
                            candidates);
                    }
                }
                // Nearest a partner first: the smallest move that helps is the one a hand would make. The partners on
                // the crossing wires count four times over: it is the wire that crosses that the move has to shorten.
                final Set<String> wanted = crossingPartners.get(id);
                final Map<GridPoint, Double> distanceOf = new HashMap<>();
                for (final GridPoint p : candidates) {
                    double sum = 0;
                    for (final String partner : partners) {
                        final GridPoint at = positions.get(partner);
                        final Size partnerSize = sizeById.get(partner);
                        final double weight = wanted != null && wanted.contains(partner) ? 4 : 1;
                        sum = sum + weight * (Math.max(
                            Math.max(0, at.x() - p.x() - size.width()),
                            p.x() - at.x() - partnerSize.width())
                            + Math.max(
                                Math.max(0, at.y() - p.y() - size.height()),
                                p.y() - at.y() - partnerSize.height()));
                    }
                    distanceOf.put(p, sum + Math.abs(p.x() - own.x()) * 0.1 + Math.abs(p.y() - own.y()) * 0.1);
                }
                candidates.sort((a, b) -> JsMath.sign(distanceOf.get(a) - distanceOf.get(b)));
                for (final GridPoint candidate : candidates.subList(0, Math.min(18, candidates.size()))) {
                    if (budget[0] <= 0) break;
                    final Map<String, GridPoint> saved = new LinkedHashMap<>();
                    for (final String member : group) saved.put(member, positions.get(member));
                    final double dx = candidate.x() - own.x();
                    final double dy = candidate.y() - own.y();
                    for (final String member : group) {
                        final GridPoint at = saved.get(member);
                        positions.put(member, new GridPoint(at.x() + dx, at.y() + dy));
                    }
                    final Verdict quick = judge.judge(positions, true, base);
                    spend.run();
                    report.run();
                    // Only a quick verdict at least two percent better earns a full one.
                    if (quick.points() < best.points() * 0.98) {
                        final Verdict next = judge.judge(positions, false, null);
                        if (next.points() < best.points() - 1) {
                            best = next;
                            base = new LinkedHashMap<>(positions);
                            improved = true;
                            break;
                        }
                    }
                    for (final Map.Entry<String, GridPoint> e : saved.entrySet()) positions.put(e.getKey(), e.getValue());
                }
                if (improved || budget[0] <= 0) break;
                // SWAPS: trade places with a card in the same column - the move that fixes a wire climbing past its
                // neighbours' wires. Each card takes the other's top-left, buds riding along; both must fit.
                final Set<String> swapKeys = new HashSet<>();
                for (final Map.Entry<String, GridPoint> entry : positions.entrySet()) {
                    if (budget[0] <= 0 || improved) break;
                    final String other = entry.getKey();
                    final GridPoint otherAt = entry.getValue();
                    if (other.equals(id) || group.contains(other) || groupOf(budsOf, other).contains(id)) continue;
                    final Size otherSize = sizeById.get(other);
                    final boolean sameColumn = own.x() < otherAt.x() + otherSize.width()
                        && otherAt.x() < own.x() + size.width();
                    if (!sameColumn) continue;
                    final String first = id.compareTo(other) <= 0 ? id : other;
                    final String second = id.compareTo(other) <= 0 ? other : id;
                    final String key = first + "<>"
                        + second
                        + "@"
                        + JsMath.text(own.x())
                        + ","
                        + JsMath.text(own.y())
                        + "|"
                        + JsMath.text(otherAt.x())
                        + ","
                        + JsMath.text(otherAt.y());
                    if (tried.contains(key) || swapKeys.contains(key)) continue;
                    swapKeys.add(key);
                    tried.add(key);
                    final List<String> otherGroup = groupOf(budsOf, other);
                    final List<String> both = new ArrayList<>(group);
                    both.addAll(otherGroup);
                    final Map<String, GridPoint> saved = new LinkedHashMap<>();
                    for (final String member : both) saved.put(member, positions.get(member));
                    final double dxA = otherAt.x() - own.x();
                    final double dyA = otherAt.y() - own.y();
                    for (final String member : group) {
                        final GridPoint at = saved.get(member);
                        positions.put(member, new GridPoint(at.x() + dxA, at.y() + dyA));
                    }
                    for (final String member : otherGroup) {
                        final GridPoint at = saved.get(member);
                        positions.put(member, new GridPoint(at.x() - dxA, at.y() - dyA));
                    }
                    // Neither group may land on anything (the other group included).
                    boolean legal = true;
                    for (final String member : both) {
                        final GridPoint at = positions.get(member);
                        final Size memberSize = sizeById.get(member);
                        for (final Map.Entry<String, GridPoint> third : positions.entrySet()) {
                            if (third.getKey()
                                .equals(member)) continue;
                            final GridPoint thirdAt = third.getValue();
                            final Size thirdSize = sizeById.get(third.getKey());
                            if (at.x() < thirdAt.x() + thirdSize.width() + gap
                                && at.x() + memberSize.width() + gap > thirdAt.x()
                                && at.y() < thirdAt.y() + thirdSize.height() + gap
                                && at.y() + memberSize.height() + gap > thirdAt.y()) {
                                legal = false;
                                break;
                            }
                        }
                        if (!legal) break;
                    }
                    if (legal) {
                        final Verdict quick = judge.judge(positions, true, base);
                        spend.run();
                        report.run();
                        if (quick.points() < best.points() * 0.98) {
                            final Verdict next = judge.judge(positions, false, null);
                            if (next.points() < best.points() - 1) {
                                best = next;
                                base = new LinkedHashMap<>(positions);
                                improved = true;
                                break;
                            }
                        }
                    }
                    for (final Map.Entry<String, GridPoint> e : saved.entrySet()) positions.put(e.getKey(), e.getValue());
                }
            }
            if (!improved) break;
        }
        // The full verdict decides: the polished layout only replaces the starting one if it is truly better by the
        // real router.
        final Verdict last = judge.judge(positions, false, null);
        final boolean keep = last.points() < startVerdict.points();
        final Map<String, GridPoint> chosen = keep ? positions : startPositions;
        final List<ArrangeResult.Move> moves = new ArrayList<>();
        for (final ArrangeResult.Move move : result.moves()) {
            final GridPoint at = chosen.get(move.id());
            moves.add(new ArrangeResult.Move(move.id(), at != null ? at : move.position()));
        }
        return new ArrangeResult(moves, result.islands(), result.wireRoutes());
    }

    private static List<String> groupOf(final Map<String, List<String>> budsOf, final String id) {
        final List<String> group = new ArrayList<>();
        group.add(id);
        group.addAll(budsOf.getOrDefault(id, List.of()));
        return group;
    }

    private static boolean groupOverlaps(final Map<String, GridPoint> positions, final Map<String, Size> sizeById,
        final List<String> group, final double dx, final double dy) {
        final double gap = ColumnArrange.BOARD_GRID;
        final Set<String> set = new HashSet<>(group);
        for (final String member : group) {
            final GridPoint at = positions.get(member);
            final Size size = sizeById.get(member);
            final double x = at.x() + dx;
            final double y = at.y() + dy;
            for (final Map.Entry<String, GridPoint> e : positions.entrySet()) {
                if (set.contains(e.getKey())) continue;
                final GridPoint otherAt = e.getValue();
                final Size otherSize = sizeById.get(e.getKey());
                if (x < otherAt.x() + otherSize.width() + gap && x + size.width() + gap > otherAt.x()
                    && y < otherAt.y() + otherSize.height() + gap
                    && y + size.height() + gap > otherAt.y()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The polish's {@code add}: a snapped, untried, free spot for the card becomes a candidate. */
    private static void add(final String id, final double rawX, final double rawY, final Set<String> tried,
        final Map<String, GridPoint> positions, final Map<String, List<String>> budsOf,
        final Map<String, Size> sizeById, final List<GridPoint> candidates) {
        final double x = ColumnArrange.snapToGrid(rawX);
        final double y = ColumnArrange.snapToGrid(rawY);
        final String key = id + "@" + JsMath.text(x) + "," + JsMath.text(y);
        if (tried.contains(key)) return;
        final GridPoint home = positions.get(id);
        if (groupOverlaps(positions, sizeById, groupOf(budsOf, id), x - home.x(), y - home.y())) return;
        tried.add(key);
        candidates.add(new GridPoint(x, y));
    }

    // region The staged arrange (arrange-solve.ts)

    /** The loader's folding of the two polishes' progress into steps 3 and 4. */
    private static final class PolishFold {

        private final Input input;
        private double firstDone;
        private double secondDone;
        private boolean firstFinished;

        PolishFold(final Input input) {
            this.input = input;
        }

        void first(final Progress p) {
            if (p.step() == 3) {
                firstDone = fraction(p);
                report();
            }
        }

        void firstFinished() {
            firstFinished = true;
            report();
        }

        void second(final Progress p) {
            if (p.step() == 4) {
                secondDone = fraction(p);
                if (firstFinished) report();
            }
        }

        private void report() {
            if (firstFinished) progress(input, 4, "Polishing the second layout", JsMath.round(secondDone * 1000), 1000);
            else progress(input, 3, "Polishing the first layout", JsMath.round(firstDone * 1000), 1000);
        }
    }

    private static double fraction(final Progress progress) {
        return Math.min(progress.done(), progress.total()) / Math.max(progress.total(), 1);
    }

    /** The search bar of the staged run: the mean of both searches. Hears from both threads. */
    private static final class SearchFold {

        private final Input input;
        private volatile double columnsDone;
        private volatile double freeDone;

        SearchFold(final Input input) {
            this.input = input;
        }

        void columns(final Progress p) {
            if (p.step() == 1) {
                columnsDone = fraction(p);
                report();
            }
        }

        void free(final Progress p) {
            if (p.step() == 1) {
                freeDone = fraction(p);
                report();
            }
        }

        private void report() {
            progress(input, 1, "Searching for a better layout", JsMath.round((columnsDone + freeDone) * 500), 1000);
        }
    }

    /**
     * The staged arrange on the host's worker (arrange-solve.ts {@code arrangeStaged}): the same board as
     * {@link #arrangeBoard}, sooner. The column candidates run on the worker while the free placement runs here (the
     * free placement never asks the judge, so the judge sees exactly arrangeBoard's calls in arrangeBoard's order);
     * then the candidates are ranked and polished here, in arrangeBoard's order: the input carries one judge, which is
     * not for two threads at once and whose remembered full verdicts feed its quick ones, so the two polishes cannot
     * share it side by side and still give arrangeBoard's board. The host's progress sink hears from the worker's
     * thread too while the column candidates run. Without a worker or a judge, or with fewer than two cards, it is
     * {@link #arrangeBoard}.
     */
    public static ArrangeResult arrangeStaged(final Input input) {
        final Worker worker = input.worker();
        if (worker == null || input.judge() == null
            || input.cards()
                .size() < 2) {
            return arrangeBoard(input);
        }
        progress(input, 0, "Laying the board out", 0, 1);
        final SearchFold search = new SearchFold(input);
        final Job<Columns> columnsJob = worker.start(() -> arrangeColumnStage(input.withProgress(search::columns)));
        final ArrangeResult free;
        final Columns columns;
        try {
            free = arrangeFreeStage(input.withProgress(search::free));
            columns = columnsJob.await();
        } catch (final RuntimeException | Error e) {
            worker.stop();
            throw e;
        }
        progress(input, 2, "Routing the candidates", 0, 1);
        final ColumnArrange st = new ColumnArrange();
        final Input prepared = prepareArrange(st, input).input();
        return polishBoth(
            st,
            prepared,
            new Candidates(columns.plain(), columns.challenger(), free),
            new PolishFold(input));
    }

    // endregion
}
