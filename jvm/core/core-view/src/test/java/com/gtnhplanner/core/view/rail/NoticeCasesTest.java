package com.gtnhplanner.core.view.rail;

import com.gtnhplanner.core.view.corpus.CorpusTest;

/** The notices bar against the website's: {@code conformance/view/notices/cases.json}. */
class NoticeCasesTest extends CorpusTest<Notices.Input> {

    NoticeCasesTest() {
        super("notices", "cases", Notices.Input.class, 25);
    }

    @Override
    protected Class<?> viewRecord() {
        return Notices.Notice.class;
    }

    @Override
    protected boolean viewIsList() {
        return true;
    }

    @Override
    protected String render(final Notices.Input in) {
        return Notices.toJson(Notices.build(in));
    }
}
