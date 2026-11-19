package com.gtnhplanner.core.layout;

/** The side of a card a wire end faces, and the direction (0 east, clockwise in eighths) it leaves in. */
public enum GridSide {

    LEFT("left", 4),
    RIGHT("right", 0),
    TOP("top", 6),
    BOTTOM("bottom", 2);

    /** The website's name for it. */
    public final String json;
    /** {@code outwardDirection}: the heading out of the card through this side. */
    final int outward;

    GridSide(final String json, final int outward) {
        this.json = json;
        this.outward = outward;
    }

    /** The side the website names so; null for none. */
    public static GridSide of(final String json) {
        for (final GridSide side : values()) if (side.json.equals(json)) return side;
        return null;
    }
}
