package com.gtnhplanner.core.view.drawer;

import com.gtnhplanner.core.view.card.Cards;
import com.gtnhplanner.core.view.corpus.CorpusTest;
import com.gtnhplanner.core.view.corpus.ViewCorpus;

/**
 * The drawer against the website's: every input record in {@code conformance/view/drawer/cases.json} must give the view
 * the website's own {@code buildDrawerView} gave, with the corpus's six-pixel measure.
 */
class DrawerCasesTest extends CorpusTest<DrawerInput> {

    DrawerCasesTest() {
        super("drawer", "cases", DrawerInput.class, 40);
    }

    @Override
    protected String measure() {
        return ViewCorpus.SIX_PX;
    }

    @Override
    protected Class<?> viewRecord() {
        return DrawerView.class;
    }

    @Override
    protected String render(final DrawerInput in) {
        return DrawerBuilder.toJson(DrawerBuilder.build(in, Cards.SIX));
    }
}
