package com.gtnhplanner.core.view.special;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import com.gtnhplanner.core.view.card.CardView;
import com.gtnhplanner.core.view.card.CardViews;
import com.gtnhplanner.core.view.card.Cards;
import com.gtnhplanner.core.view.corpus.CorpusTest;
import com.gtnhplanner.core.view.corpus.ViewCorpus;
import com.gtnhplanner.harness.LangText;

/**
 * The special cards' shared cases (conformance/view/special/cases.json): the core's special rules give, byte for byte,
 * the parts the website's own rules gave of each card's facts and its kind's, over the card's own view, measured six px
 * a character, in the English words.
 */
class SpecialCasesTest extends CorpusTest<SpecialInput.Case> {

    private static LangText lang;

    SpecialCasesTest() {
        super("special", "cases", SpecialInput.Case.class, 25);
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
    protected String render(final SpecialInput.Case in) {
        final CardView base = CardViews.card(in.card(), in.dials(), Cards.SIX, CardViews.Host.WEB);
        return SpecialViews.special(in.special(), in.card(), base, in.dials(), Cards.SIX, CardViews.Host.WEB)
            .toJson();
    }

    @Override
    protected String measure() {
        return ViewCorpus.SIX_PX;
    }

    @Override
    protected Class<?> viewRecord() {
        return SpecialView.class;
    }
}
