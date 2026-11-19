package com.gtnhplanner.core.view.card;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import com.gtnhplanner.core.view.corpus.CorpusTest;
import com.gtnhplanner.core.view.corpus.ViewCorpus;
import com.gtnhplanner.harness.LangText;

/**
 * The recipe card's shared cases (conformance/view/card/cases.json): the core's card rules give, byte for byte, the view
 * the website's own builders gave of each card's facts, measured six px a character, in the English words.
 */
class CardCasesTest extends CorpusTest<CardInput.Case> {

    private static LangText lang;

    CardCasesTest() {
        super("card", "cases", CardInput.Case.class, 99);
    }

    @BeforeAll
    static void english() {
        lang = LangText.english();
    }

    @AfterAll
    static void restore() {
        lang.close();
    }

    @Override
    protected String render(final CardInput.Case in) {
        return CardViews.card(in.card(), in.dials(), Cards.SIX, CardViews.Host.WEB)
            .toJson();
    }

    @Override
    protected String measure() {
        return ViewCorpus.SIX_PX;
    }

    @Override
    protected Class<?> viewRecord() {
        return CardView.class;
    }
}
