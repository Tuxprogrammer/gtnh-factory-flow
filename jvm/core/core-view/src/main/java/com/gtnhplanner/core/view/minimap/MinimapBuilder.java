package com.gtnhplanner.core.view.minimap;

import java.util.ArrayList;
import java.util.List;

import com.gtnhplanner.core.view.Json;
import com.gtnhplanner.core.view.note.NoteLookBuilder;

/**
 * The minimap's scene from a board's boxes and routes: which boxes are tiles (a hidden or empty box is not; an
 * unknown kind is not), each tile's colour and count, which wires are drawn and in what colour, and the bounds of the
 * tiles. The website's board minimap and picture and the mod's map in the world draw from this one scene.
 */
public final class MinimapBuilder {

    private MinimapBuilder() {}

    private static final String NEUTRAL = "#8A93A6", SOURCE = "#78B6F4", PRODUCT = "#F59A5C", BUFFER = "#2E9C8F";
    private static final String WIRE_GREY = "#9aa1b0";
    private static final double WIRE_WIDTH = 3;

    public static MinimapView build(final MinimapInput in) {
        final List<MinimapView.Tile> tiles = new ArrayList<>();
        double x0 = Double.POSITIVE_INFINITY, y0 = Double.POSITIVE_INFINITY;
        double x1 = Double.NEGATIVE_INFINITY, y1 = Double.NEGATIVE_INFINITY;
        for (final MinimapInput.Node n : in.nodes()) {
            if (n.hidden() || !(n.w() > 0) || !(n.h() > 0)) continue;
            final MinimapView.Tile t = tile(n);
            if (t == null) continue;
            tiles.add(t);
            x0 = Math.min(x0, n.x());
            y0 = Math.min(y0, n.y());
            x1 = Math.max(x1, n.x() + n.w());
            y1 = Math.max(y1, n.y() + n.h());
        }
        final List<MinimapView.Wire> wires = new ArrayList<>();
        for (final MinimapInput.Route r : in.routes()) {
            if (r.hidden() || r.points().size() < 2) continue;
            wires.add(new MinimapView.Wire(r.id(), r.points(), r.width() == null ? WIRE_WIDTH : r.width(),
                wireColor(r.color()), r.flowing()));
        }
        return new MinimapView(in.name(), tiles, wires,
            tiles.isEmpty() ? null : new MinimapView.Bounds(x0, y0, x1, y1));
    }

    private static MinimapView.Tile tile(final MinimapInput.Node n) {
        switch (n.kind()) {
            case "card": {
                final double v = n.machines();
                return new MinimapView.Tile(n.id(), "card", n.x(), n.y(), n.w(), n.h(), own(n, tintOf(n.tag())), shown(v),
                    n.pinned(), !(v > 0), null, n.selected());
            }
            case "drawer": {
                final String role = n.role() == null ? "product" : n.role();
                final String tint = switch (role) {
                    case "source" -> SOURCE;
                    case "product" -> PRODUCT;
                    case "buffer" -> BUFFER;
                    default -> NEUTRAL;
                };
                return new MinimapView.Tile(n.id(), "drawer", n.x(), n.y(), n.w(), n.h(), tint, null, null, null, role,
                    n.selected());
            }
            case "note":
                return new MinimapView.Tile(n.id(), "note", n.x(), n.y(), n.w(), n.h(),
                    own(n, tintOf(n.tag() == null ? "yellow" : n.tag())), null, null, null, null, n.selected());
            case "board":
            case "group":
                return new MinimapView.Tile(n.id(), n.kind(), n.x(), n.y(), n.w(), n.h(),
                    n.tint() == null ? NEUTRAL : n.tint(), null, null, null, null, n.selected());
            default:
                return null;
        }
    }

    /** A card's or note's own colour wins over its tag's, for a host that keeps the colour and not the tag. */
    private static String own(final MinimapInput.Node n, final String ofTag) {
        return n.tint() != null ? n.tint() : ofTag;
    }

    /** A tag's swatch as the app keeps it (lower case); a tag nobody knows, or none, is the neutral grey. */
    static String tintOf(final String tag) {
        if (tag == null || !NoteLookBuilder.knows(tag)) return NEUTRAL;
        return hex(NoteLookBuilder.swatch(tag));
    }

    /** The count as the map writes it: none or below nothing is 0; whole above 99, else to two places. */
    static double shown(final double v) {
        if (Double.isNaN(v) || Double.isInfinite(v) || v <= 0) return 0;
        return v >= 100 ? Math.round(v) : Math.round(v * 100) / 100.0;
    }

    private static boolean isHex(final String s) {
        if (s.length() != 7 || s.charAt(0) != '#') return false;
        for (int i = 1; i < 7; i++) if ("0123456789abcdefABCDEF".indexOf(s.charAt(i)) < 0) return false;
        return true;
    }

    /** A wire's colour a fifth of the way to white, as the edge on the board is lifted; grey for none or a bad one. */
    static String wireColor(final String hex) {
        if (hex == null || !isHex(hex)) return WIRE_GREY;
        final int n = Integer.parseInt(hex.substring(1), 16);
        final int r = lift(n >> 16 & 255), g = lift(n >> 8 & 255), b = lift(n & 255);
        return hex(r << 16 | g << 8 | b);
    }

    /** {@code #rrggbb}, lower case. */
    private static String hex(final int rgb) {
        final String digits = "0123456789abcdef";
        final StringBuilder sb = new StringBuilder("#");
        for (int shift = 20; shift >= 0; shift -= 4) sb.append(digits.charAt(rgb >> shift & 0xF));
        return sb.toString();
    }

    private static int lift(final int c) {
        return (int) Math.round(c + (255 - c) * 0.2);
    }

    public static String toJson(final MinimapView v) {
        final Json j = new Json().begin().str("name", v.name());
        j.list("tiles", v.tiles(), (jj, t) -> jj.begin()
            .str("id", t.id())
            .str("kind", t.kind())
            .num("x", t.x())
            .num("y", t.y())
            .num("w", t.w())
            .num("h", t.h())
            .str("tint", t.tint())
            .opt("machines", t.machines())
            .opt("pinned", t.pinned())
            .opt("zero", t.zero())
            .opt("drawer", t.drawer())
            .bool("selected", t.selected())
            .end());
        j.list("wires", v.wires(), (jj, w) -> {
            jj.begin().str("id", w.id());
            jj.list("points", w.points(), (j3, p) -> j3.begin().num("x", p.x()).num("y", p.y()).end());
            jj.num("width", w.width()).str("color", w.color()).bool("flowing", w.flowing()).end();
        });
        if (v.bounds() != null) {
            final MinimapView.Bounds b = v.bounds();
            j.field("bounds", jj -> jj.begin().num("x0", b.x0()).num("y0", b.y0()).num("x1", b.x1()).num("y1", b.y1()).end());
        }
        return j.end().toString();
    }
}
