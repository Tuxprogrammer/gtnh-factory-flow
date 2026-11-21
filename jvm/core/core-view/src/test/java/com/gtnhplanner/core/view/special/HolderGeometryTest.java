package com.gtnhplanner.core.view.special;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.view.card.CardGeometry;
import com.gtnhplanner.core.view.card.CardGeometry.Anchor;
import com.gtnhplanner.core.view.card.CardView;
import com.gtnhplanner.core.view.card.CardViews;
import com.gtnhplanner.core.view.card.Cards;
import com.gtnhplanner.core.view.corpus.RecordReader;
import com.gtnhplanner.core.view.corpus.ViewCorpus;
import com.gtnhplanner.harness.LangText;

/**
 * A holder card on the website as its stylesheet lays it out (SpecialViews.webHolder): the numbers the geometry probe
 * measured in the DOM of a board of holders (docs/view-card.md, "Special cards"): an 80 px well, the strip under it, an
 * empty holder's sockets anchored in their middle, 62 px down.
 */
class HolderGeometryTest {

    private static LangText lang;

    @BeforeAll
    static void english() {
        lang = LangText.english();
    }

    @AfterAll
    static void restore() {
        lang.close();
    }

    private static CardGeometry of(final String name) throws IOException {
        for (final ViewCorpus.Case c : ViewCorpus.open("special", "cases")
            .cases()) {
            if (!c.name()
                .equals(name)) continue;
            final SpecialInput.Case in = RecordReader.read(SpecialInput.Case.class, c.input());
            final CardView base = CardViews.card(in.card(), in.dials(), Cards.SIX, CardViews.Host.WEB);
            return SpecialViews.webHolder(
                SpecialViews.special(in.special(), in.card(), base, in.dials(), Cards.SIX, CardViews.Host.WEB),
                base);
        }
        throw new IllegalArgumentException("no case " + name);
    }

    @Test
    void aCustomRateHoldingAnItemIsItsRowBesideTheWellAndItsDialUnder() throws IOException {
        final CardGeometry g = of("custom rate: holding an item");
        assertEquals(40, g.railsTop());
        assertEquals(80, g.railsHeight());
        assertEquals(120, g.stripTop());
        assertEquals(40, g.stripHeight());
        assertEquals(160, g.height());
        assertEquals(-1, g.footTop());
        assertEquals(List.of(new Anchor("output:item:iron_ingot", true, 60)), g.anchors());
    }

    @Test
    void anEmptyHolderIsItsTwoSockets() throws IOException {
        final CardGeometry custom = of("custom rate: empty, its two sockets and its dial");
        assertEquals(160, custom.height());
        assertEquals(List.of(new Anchor(SpecialViews.TAKE, false, 62), new Anchor(SpecialViews.GIVE, true, 62)),
            custom.anchors());
        final CardGeometry tank = of("fluid tank: empty");
        assertEquals(120, tank.height());
        assertEquals(-1, tank.footTop());
        assertEquals(0, tank.stripHeight());
    }

    @Test
    void aTankEmptyingCellsIsItsRowsBesideTheWell() throws IOException {
        final CardGeometry g = of("fluid tank: emptying cells");
        assertEquals(80, g.railsHeight());
        assertEquals(120, g.height());
        assertEquals(List.of(new Anchor("input:item:oxygen_cell", false, 60), new Anchor("output:fluid:oxygen", true, 60)),
            g.anchors());
    }

    @Test
    void anExactlyCustomRateFedByAForcedInputHasItsBarUnderTheStrip() throws IOException {
        final CardGeometry g = of("custom rate: fed by a forced input");
        // The forced row hangs under its port: one element two rows tall, the wire docks between the two.
        assertEquals(List.of(new Anchor("input:item:iron_ingot", false, 80)), g.anchors());
        assertEquals(40 + 80 + g.stripHeight() + 1, g.footTop());
        assertEquals(40 + 80 + g.stripHeight() + CardGeometry.WEB_FOOT, g.height());
    }
}
