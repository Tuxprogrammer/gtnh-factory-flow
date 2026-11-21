package com.gtnhplanner.core.view.minimap;

import java.util.List;

import javax.annotation.Nullable;

/**
 * What a host knows of its board when it draws the minimap: the boxes of every card, drawer, note and group in board
 * units, and the routes its router made, in the order it holds them. The host reads its own board (the website its
 * React Flow nodes, the mod its {@code PlanSnapshot}); the core says which of them are tiles, what colour and figure
 * each carries, what the map's bounds are and how the wires look.
 */
public record MinimapInput(String name, List<Node> nodes, List<Route> routes) {

    /**
     * One box. {@code kind}: {@code card}, {@code drawer}, {@code note}, {@code board} (an open group's frame) or
     * {@code group} (a collapsed group's card). {@code tag}: a card's or note's colour tag. {@code tint}: the colour as
     * {@code #RRGGBB} when the host has it and not a tag (an open or collapsed group's always; it wins over a tag). {@code machines}: the count a card
     * shows (what runs for an Up to card, the set count of an Exactly card, else what is needed), parallels included.
     * {@code pinned}: the count is one the player set. {@code role}: a drawer's {@code source}, {@code product},
     * {@code trash} or {@code buffer}.
     */
    public record Node(String id, String kind, double x, double y, double w, double h, boolean hidden, boolean selected,
        @Nullable String tag, @Nullable String tint, double machines, boolean pinned, @Nullable String role) {}

    /**
     * One routed wire. {@code color}: the wire's own colour as {@code #RRGGBB}, none for the default grey. {@code width}:
     * the line's width on the board, none for 3. {@code flowing}: something moves along it. {@code hidden}: the host
     * does not draw this wire on the board.
     */
    public record Route(String id, List<Point> points, @Nullable Double width, @Nullable String color, boolean flowing,
        boolean hidden) {}

    public record Point(double x, double y) {}
}
