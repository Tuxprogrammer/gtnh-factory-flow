package com.gtnhplanner.core.view.group;

import com.gtnhplanner.core.view.corpus.CorpusTest;

/** The group summary against the website's: {@code conformance/view/group/cases.json}. */
class GroupCasesTest extends CorpusTest<GroupInput> {

    GroupCasesTest() {
        super("group", "cases", GroupInput.class, 10);
    }

    @Override
    protected Class<?> viewRecord() {
        return GroupView.class;
    }

    @Override
    protected String render(final GroupInput in) {
        return GroupBuilder.toJson(GroupBuilder.build(in));
    }
}
