package com.gtnhplanner.core.view.card;

import java.util.ArrayList;
import java.util.List;

import com.gtnhplanner.core.view.Tokens;
import com.gtnhplanner.ui.card.CardLayout;

/**
 * Where a card's parts stand, in board px from its top left: its size, the rails, each port's anchor (where a wire
 * docks: the card's edge, level with the middle of its row), the strip and the bar along the foot. The router and
 * Arrange read a card's size and anchors; the hosts' cards come out at these numbers.
 *
 * <p>
 * The two hosts' cards share the width and the rules but not every size (tokens' {@code _WEB} / {@code _MOD}): the
 * mod's is {@link CardLayout}'s ({@link #mod}); the website's is its stylesheet's ({@link #web}: clean.css's grid of a
 * 40 px head, 40 px port rows, an 80 px row for a port with a forced row under it, the picture's 120 px, the strip's
 * height and a 20 px foot bar), which the website measures in its DOM today. A probe compares the two
 * ({@code docs/view-card.md}).
 *
 * @param height     the card's own height (the website rounds its node up to whole board cells around it)
 * @param railsTop   the top of the rails, under the head
 * @param anchors    each port's anchor, inputs then outputs, in rail order
 * @param stripTop   the top of the settings strip; its height {@code stripHeight}, 0 with none
 * @param footTop    the top of the bar along the foot (the bar, not its margin); -1 for none
 */
public record CardGeometry(int width, int height, int railsTop, int railsHeight, List<Anchor> anchors, int stripTop,
    int stripHeight, int footTop) {

    /** A port's anchor: its handle, its side, and y from the card's top (x is the card's edge on its side). */
    public record Anchor(String handleId, boolean output, int y) {}

    /** One rail's rows, as the geometry needs them: each row's handle and whether a forced row hangs under it. */
    public record Row(String handleId, boolean forced) {}

    /** The website's head, port row, a row with its forced row, the picture's least height and the foot bar's. */
    public static final int WEB_HEAD = Tokens.Card.HEAD_HEIGHT_WEB, WEB_ROW = Tokens.Card.PORT_ROW_HEIGHT_WEB,
        WEB_PICTURE = 120, WEB_FOOT = 20;

    /** The website's card for a view. */
    public static CardGeometry web(final CardView v) {
        return web(rows(v.inputs()), v.power() != null, rows(v.outputs()), v.stripHeight(), v.foot() != null);
    }

    private static List<Row> rows(final List<CardView.PortView> ports) {
        final List<Row> out = new ArrayList<>();
        for (final CardView.PortView p : ports) out.add(new Row(p.handleId(), p.forced() != null));
        return out;
    }

    /**
     * The website's card from its structure: the rows on each rail (the power row is one more after the inputs), the
     * strip's height and whether a foot bar hangs under it.
     */
    public static CardGeometry web(final List<Row> inputs, final boolean power, final List<Row> outputs,
        final int stripHeight, final boolean foot) {
        final List<Anchor> anchors = new ArrayList<>();
        final int in = rail(inputs, false, anchors) + (power ? WEB_ROW : 0);
        final int out = rail(outputs, true, anchors);
        final int rails = Math.max(WEB_PICTURE, Math.max(in, out));
        final int stripTop = WEB_HEAD + rails;
        // The bar itself: 16 px with a pixel above and three below (clean.css .cc-foot).
        final int footTop = foot ? stripTop + stripHeight + 1 : -1;
        return new CardGeometry(Tokens.Card.CARD_WIDTH, stripTop + stripHeight + (foot ? WEB_FOOT : 0), WEB_HEAD,
            rails, anchors, stripTop, stripHeight, footTop);
    }

    private static int rail(final List<Row> rows, final boolean output, final List<Anchor> anchors) {
        int y = 0;
        for (final Row r : rows) {
            // The row is the wire's anchor (data-resource-edge-anchor): a port with its forced row under it is one
            // element two rows tall, so its wire docks between the two.
            final int slot = r.forced() ? 2 * WEB_ROW : WEB_ROW;
            anchors.add(new Anchor(r.handleId(), output, WEB_HEAD + y + slot / 2));
            y += slot;
        }
        return y;
    }

    /** A board node's height on the website: the card's, rounded up to whole board cells (GridBlock). */
    public static int webNode(final CardGeometry g) {
        final int cell = Tokens.Grid.BOARD_GRID;
        return (g.height() + cell - 1) / cell * cell;
    }

    /**
     * The mod's card: {@link CardLayout}'s numbers for one recipe ({@code inputs}, {@code outputs}: the handles in
     * port order).
     */
    public static CardGeometry mod(final CardLayout layout, final List<String> inputs, final List<String> outputs) {
        final List<Anchor> anchors = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) anchors.add(new Anchor(inputs.get(i), false, layout.anchorY(false, i)));
        for (int i = 0; i < outputs.size(); i++) anchors.add(new Anchor(outputs.get(i), true, layout.anchorY(true, i)));
        final int[] foot = layout.foot();
        return new CardGeometry(CardLayout.W, layout.height, CardLayout.RAILS_Y, layout.railsH, anchors, layout.stripY,
            (foot != null ? foot[1] : layout.height) - layout.stripY, foot != null ? foot[1] : -1);
    }
}
