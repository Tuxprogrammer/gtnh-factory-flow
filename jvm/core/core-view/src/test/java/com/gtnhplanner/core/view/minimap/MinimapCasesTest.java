package com.gtnhplanner.core.view.minimap;

import com.gtnhplanner.core.view.corpus.CorpusTest;

/** The minimap scene against the website's: {@code conformance/view/minimap/cases.json}. */
class MinimapCasesTest extends CorpusTest<MinimapInput> {

    MinimapCasesTest() {
        super("minimap", "cases", MinimapInput.class, 8);
    }

    @Override
    protected Class<?> viewRecord() {
        return MinimapView.class;
    }

    @Override
    protected String render(final MinimapInput in) {
        return MinimapBuilder.toJson(MinimapBuilder.build(in));
    }

    @org.junit.jupiter.api.Test
    void aHostsOwnColourWinsOverATagAndAGroupWithNoneIsNeutral() {
        final MinimapInput in = new MinimapInput("P", java.util.List.of(
            new MinimapInput.Node("a", "card", 0, 0, 10, 10, false, false, "orange", "#112233", 1, false, null),
            new MinimapInput.Node("b", "card", 0, 0, 10, 10, false, false, "orange", null, 1, false, null),
            new MinimapInput.Node("c", "group", 0, 0, 10, 10, false, false, null, null, 0, false, null),
            new MinimapInput.Node("d", "mystery", 0, 0, 10, 10, false, false, null, null, 0, false, null)),
            java.util.List.of());
        final MinimapView v = MinimapBuilder.build(in);
        org.junit.jupiter.api.Assertions.assertEquals(3, v.tiles().size());
        org.junit.jupiter.api.Assertions.assertEquals("#112233", v.tiles().get(0).tint());
        org.junit.jupiter.api.Assertions.assertEquals("#f9801d", v.tiles().get(1).tint());
        org.junit.jupiter.api.Assertions.assertEquals("#8A93A6", v.tiles().get(2).tint());
    }
}
