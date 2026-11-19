package com.gtnhplanner.core.layout.arrange;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.gtnhplanner.core.layout.GridEdgeRouter;
import com.gtnhplanner.core.layout.GridPoint;
import com.gtnhplanner.core.layout.RouterTuning;
import com.gtnhplanner.core.layout.WireMetrics;

/**
 * The website's board-arrange.ts entry points (and arrange-solve.ts's staged run). STUB: the translation replaces the
 * bodies; the signatures are fixed.
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
        @Nullable Worker worker) {}

    /** {@code ArrangeCandidates}. */
    public record Candidates(ArrangeResult plain, ArrangeResult challenger, ArrangeResult free) {}

    /** {@code arrangeColumnStage}'s answer. */
    public record Columns(ArrangeResult plain, ArrangeResult challenger) {}

    /** {@code arrangePolishStage}'s answer. */
    public record Polished(ArrangeResult result, double points, int crossings) {}

    public static ArrangeResult arrangeBoard(final Input input) {
        throw new UnsupportedOperationException("not translated yet");
    }

    public static ArrangeResult arrangeBoardColumns(final Input input) {
        throw new UnsupportedOperationException("not translated yet");
    }

    public static Columns arrangeColumnStage(final Input input) {
        throw new UnsupportedOperationException("not translated yet");
    }

    public static ArrangeResult arrangeFreeStage(final Input input) {
        throw new UnsupportedOperationException("not translated yet");
    }

    public static Polished arrangePolishStage(final Input input, final Candidates candidates, final int which) {
        throw new UnsupportedOperationException("not translated yet");
    }

    /**
     * The staged arrange on the host's worker (arrange-solve.ts {@code arrangeStaged}): the same board as
     * {@link #arrangeBoard}, sooner. Without a worker or a judge it is {@link #arrangeBoard}.
     */
    public static ArrangeResult arrangeStaged(final Input input) {
        throw new UnsupportedOperationException("not translated yet");
    }
}
