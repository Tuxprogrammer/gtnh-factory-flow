package com.gtnhplanner.core.layout.arrange;

import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import javax.annotation.Nullable;

import com.gtnhplanner.core.layout.GridPoint;
import com.gtnhplanner.core.layout.RouterTuning;

/** The website's board-arrange-optimize.ts. STUB: the translation replaces the bodies; the signatures are fixed. */
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

    /** {@code optimizeIslandLayout(cards, wires, options)}. */
    public static OptimizeResult optimizeIslandLayout(final List<OptimizeCard> cards, final List<OptimizeWire> wires,
        final OptimizeOptions options) {
        throw new UnsupportedOperationException("not translated yet");
    }

    /** {@code scoreLayoutProxy(cards, wires, positions, prices, air)}; {@code air} null when absent. */
    public static double scoreLayoutProxy(final List<ArrangeCard> cards, final List<OptimizeWire> wires,
        final Map<String, GridPoint> positions, final RouterTuning prices,
        @Nullable final ToDoubleFunction<Map<String, GridPoint>> air) {
        throw new UnsupportedOperationException("not translated yet");
    }
}
