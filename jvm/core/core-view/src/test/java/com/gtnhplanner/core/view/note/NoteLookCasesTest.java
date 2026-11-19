package com.gtnhplanner.core.view.note;

import com.gtnhplanner.core.view.corpus.CorpusTest;

/** A sticky note's colours against the website's: {@code conformance/view/note/look.json}. */
class NoteLookCasesTest extends CorpusTest<NoteLookInput> {

    NoteLookCasesTest() {
        super("note", "look", NoteLookInput.class, 20);
    }

    @Override
    protected Class<?> viewRecord() {
        return NoteLookView.class;
    }

    @Override
    protected String render(final NoteLookInput in) {
        return NoteLookBuilder.toJson(NoteLookBuilder.build(in));
    }
}
