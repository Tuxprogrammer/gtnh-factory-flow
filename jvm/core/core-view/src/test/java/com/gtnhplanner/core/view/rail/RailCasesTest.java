package com.gtnhplanner.core.view.rail;

import com.gtnhplanner.core.view.corpus.CorpusTest;

/**
 * The overview rail against the website's: every input record in {@code conformance/view/rail/cases.json} must give
 * the view the website's own code gave, number for number.
 */
class RailCasesTest extends CorpusTest<RailInput> {

    RailCasesTest() {
        super("rail", "cases", RailInput.class, 40);
    }

    @Override
    protected Class<?> viewRecord() {
        return RailView.class;
    }

    @Override
    protected String render(final RailInput in) {
        return RailBuilder.build(in).toJson();
    }
}
