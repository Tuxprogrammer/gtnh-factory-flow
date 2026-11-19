package com.gtnhplanner.core.view;

/**
 * A named text style: what a piece of board text is, not how big it is. Each host draws a style in its own face and
 * size, so the core asks a host's {@link TextMeasure} for widths by style and never by font or pixel size.
 *
 * <p>
 * The mod draws every style in the game font, its one size (8 px) scaled; the website draws them in the clean card's
 * face (Inter unless the player picks another) at a CSS size, measured on a canvas by {@code text-measure.ts}. Each
 * constant names both, so a rule written against a style means the same box on either side.
 */
public enum TextStyle {

    /** Chips' labels and values, the foot bar's word, line and fraction, warnings. Mod: game font 1x. Web: 8 px. */
    LABEL,
    /** A port row's number at figure size. Mod: game font x1.5 ({@code Hyb.FIGURE}). Web: 14 px, -0.8 px spacing. */
    ROW_FIGURE,
    /** A port row's unit after its number. Mod: game font 1x. Web: 9 px, -0.6 px spacing, a 1 px gap before it. */
    ROW_UNIT,
    /** A port row's name at its normal size. Mod: game font 1x. Web: 9 px, -0.6 px spacing. */
    ROW_NAME,
    /** A port row's name stepped down when it does not fit. Mod: game font x0.75. Web: 7 px, -0.45 px spacing. */
    ROW_NAME_SMALL,
    /**
     * The game font at 1x on every host: on the website its glyph widths ({@code mcTextWidth}), not the drawn face. For
     * a decision both hosts must take alike whatever their faces measure (the website steps a port's name down exactly
     * where the mod does).
     */
    GAME,
    /** A card's name at its largest. Mod: game font x1.5. Web: 10 px. */
    NAME_LARGE,
    /** A card's name one step down. Mod: game font x1.25. Web: 9 px. */
    NAME_MEDIUM,
    /** A card's name at its smallest (cut past it). Mod: game font 1x. Web: 8 px. */
    NAME_SMALL
}
