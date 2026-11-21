package com.gtnhplanner.ui.popup;

import com.gtnhplanner.core.view.TipView;
import com.gtnhplanner.ui.theme.Hyb;

/** The core's tip as data ({@link TipView}) drawn as this host's {@link Tip}: the words are the core's. */
public final class TipViews {

    private TipViews() {}

    public static Tip of(final TipView view) {
        final Tip tip = Tip.of(view.title());
        if (view.sub() != null) tip.sub(view.sub());
        body(tip, view);
        for (final TipView.Action action : view.actions()) tip.action(input(action.input()), action.label());
        return tip;
    }

    /** The rows and notes of {@code view} added to {@code tip}, under what it has (a part's tip carrying another's). */
    public static Tip body(final Tip tip, final TipView view) {
        for (final TipView.Row row : view.rows()) tip.row(row.label(), row.value(), color(row.tone(), Tip.TITLE));
        for (final TipView.Note note : view.notes()) {
            if (note.text()
                .isEmpty()) tip.gap();
            else tip.say(note.text(), color(note.tone(), Tip.TEXT));
        }
        return tip;
    }

    /** A tone's colour: muted, warn and red; none is {@code plain}. */
    static int color(final String tone, final int plain) {
        if (tone == null) return plain;
        return switch (tone) {
            case "muted" -> Tip.SUBTLE;
            case "warn" -> Tip.WARN;
            case "red" -> Hyb.RED_INK;
            default -> plain;
        };
    }

    public static Tip.Input input(final String name) {
        return switch (name) {
            case "left" -> Tip.Input.LEFT;
            case "right" -> Tip.Input.RIGHT;
            case "wheel" -> Tip.Input.WHEEL;
            case "middle" -> Tip.Input.MIDDLE;
            case "drag" -> Tip.Input.DRAG;
            case "key" -> Tip.Input.KEY;
            default -> throw new IllegalArgumentException("no such mouse input: " + name);
        };
    }
}
