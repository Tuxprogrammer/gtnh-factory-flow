package com.gtnhplanner.core.layout.arrange;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.gtnhplanner.core.layout.GridPoint;
import com.gtnhplanner.core.layout.RouterTuning;

/** The website's board-arrange-free.ts. STUB: the translation replaces the body; the signatures are fixed. */
public final class FreeArrange {

    private FreeArrange() {}

    /** {@code FreeOptions}: null fields are absent. */
    public record FreeOptions(RouterTuning prices, @Nullable Double trials, @Nullable Double gapCells,
        @Nullable Double besideCells, @Nullable Steps onProgress) {}

    /** {@code FreeResult}: positions by card id, in the order the website's Map holds them. */
    public record FreeResult(Map<String, GridPoint> positions, List<ArrangeResult.Island> islands) {}

    /** {@code arrangeFree(cards, wires, options)}. */
    public static FreeResult arrangeFree(final List<ArrangeCard> cards, final List<ArrangeWire> wires,
        final FreeOptions options) {
        throw new UnsupportedOperationException("not translated yet");
    }
}
