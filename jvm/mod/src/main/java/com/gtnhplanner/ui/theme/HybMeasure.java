package com.gtnhplanner.ui.theme;

import java.util.ArrayList;
import java.util.List;

import com.gtnhplanner.core.view.TextMeasure;
import com.gtnhplanner.core.view.TextStyle;

/**
 * The game's text widths for the core's view rules: every style is the game font, its one size scaled as the board
 * draws it ({@link Hyb#FIGURE} for a row's figure, 0.75 for a stepped-down name), and lines break where the font
 * renderer breaks them.
 */
public final class HybMeasure implements TextMeasure {

    public static final HybMeasure INSTANCE = new HybMeasure();

    private HybMeasure() {}

    /** How much bigger than the font's own 8 px the board draws a style. */
    public static float scale(final TextStyle style) {
        return switch (style) {
            case ROW_FIGURE -> Hyb.FIGURE;
            case ROW_NAME_SMALL -> 0.75f;
            case LABEL, ROW_UNIT, ROW_NAME, GAME, NAME_SMALL -> 1f;
            case NAME_LARGE -> 1.5f;
            case NAME_MEDIUM -> 1.25f;
        };
    }

    @Override
    public double width(final TextStyle style, final String text) {
        final float scale = scale(style);
        return scale == 1f ? Hyb.width(text) : Hyb.width(text) * scale;
    }

    @Override
    public List<String> wrap(final TextStyle style, final String text, final double room) {
        final List<?> lines = Hyb.font()
            .listFormattedStringToWidth(text, (int) (room / scale(style)));
        final List<String> out = new ArrayList<>(lines.size());
        for (final Object line : lines) out.add((String) line);
        return out;
    }
}
