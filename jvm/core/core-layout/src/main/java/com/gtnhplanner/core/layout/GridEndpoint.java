package com.gtnhplanner.core.layout;

/**
 * One place a wire may start or end: the anchor on the card's border, the side it faces, the extra cost of choosing
 * it in px (0: none), and how far into the card past the anchor the drawn wire goes on ({@code stubDepth}, 0: none),
 * for a card whose border is off the 20 px grid.
 */
public record GridEndpoint(double x, double y, GridSide side, double penalty, double stubDepth) {

    public GridEndpoint(final double x, final double y, final GridSide side) {
        this(x, y, side, 0, 0);
    }

    GridEndpoint withPenalty(final double next) {
        return new GridEndpoint(x, y, side, next, stubDepth);
    }

    GridEndpoint shifted(final double dx, final double dy) {
        return new GridEndpoint(x + dx, y + dy, side, penalty, stubDepth);
    }
}
