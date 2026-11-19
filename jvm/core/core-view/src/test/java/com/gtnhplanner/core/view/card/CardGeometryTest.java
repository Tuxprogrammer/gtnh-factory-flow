package com.gtnhplanner.core.view.card;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.view.card.CardGeometry.Anchor;
import com.gtnhplanner.core.view.card.CardGeometry.Row;

/** The website's card as its stylesheet lays it out: the numbers the probe measured in the DOM (docs/view-card.md). */
class CardGeometryTest {

    @Test
    void railsStripAndFootStackUnderTheHead() {
        final CardGeometry g = CardGeometry.web(
            List.of(new Row("a", false), new Row("b", true)),
            true,
            List.of(new Row("c", false)),
            40,
            true);
        // Inputs: 40 + an 80 px row with its forced row + the power row's 40 = 160, over the picture's 120.
        assertEquals(160, g.railsHeight());
        assertEquals(40, g.railsTop());
        assertEquals(200, g.stripTop());
        assertEquals(240, g.footTop());
        assertEquals(260, g.height());
        assertEquals(260, CardGeometry.webNode(g));
        assertEquals(List.of(new Anchor("a", false, 60), new Anchor("b", false, 100), new Anchor("c", true, 60)),
            g.anchors());
    }

    @Test
    void aShortCardIsThePictureTallAndItsNodeWholeCells() {
        final CardGeometry g = CardGeometry.web(List.of(new Row("a", false)), false, List.of(), 0, false);
        assertEquals(120, g.railsHeight());
        assertEquals(160, g.height());
        assertEquals(-1, g.footTop());
        final CardGeometry odd = CardGeometry.web(List.of(), false, List.of(), 0, true);
        assertEquals(180, odd.height());
        assertEquals(180, CardGeometry.webNode(odd));
    }
}
