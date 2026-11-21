package com.gtnhplanner.core.view.minimap;

import java.util.List;

import javax.annotation.Nullable;

/**
 * The minimap's scene: the tiles in the order the host gave them, the wires worth drawing, and the box round every tile
 * (none for an empty plan). A tile's {@code tint} is {@code #RRGGBB}. {@code machines}, for a card, is the count as the
 * map writes it: whole above 99, else to two places, 0 for none; {@code zero} for a card that runs nothing. {@code
 * pinned} is set on a card whose count the player set.
 */
public record MinimapView(String name, List<Tile> tiles, List<Wire> wires, @Nullable Bounds bounds) {

    public record Tile(String id, String kind, double x, double y, double w, double h, String tint,
        @Nullable Double machines, @Nullable Boolean pinned, @Nullable Boolean zero, @Nullable String drawer,
        boolean selected) {}

    /** {@code color} is the wire's colour lifted a fifth towards white, so it reads on the dark board. */
    public record Wire(String id, List<MinimapInput.Point> points, double width, String color, boolean flowing) {}

    public record Bounds(double x0, double y0, double x1, double y1) {}
}
