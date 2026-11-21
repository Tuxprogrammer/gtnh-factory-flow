package com.gtnhplanner.ui.note;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.gtnhplanner.core.view.note.NoteLookBuilder;
import com.gtnhplanner.core.view.note.NoteLookInput;
import com.gtnhplanner.core.view.note.NoteLookView;
import com.gtnhplanner.core.view.note.NoteMenu;

/**
 * A sticky note's colours, from Factory Flow's colour tags (the sixteen dyes and the app's own inks): the paper is
 * the tag's swatch lifted a quarter of the way to white, the ink dark or light by how bright the paper is, as the
 * website picks it.
 */
public final class NoteColors {

    private NoteColors() {}

    /** The colours the note menu offers, in order, with their names (the core's list, shared with the website). */
    public static final List<String[]> MENU = NoteMenu.ENTRIES;

    /** The tag's swatch, opaque; an unknown tag reads as yellow. */
    public static int swatch(final String tag) {
        return 0xFF000000 | NoteLookBuilder.swatch(tag);
    }

    // The note's own colours are the core's (shared with the website, conformance/view/note).

    /** The paper: the swatch a quarter of the way to white. */
    public static int paper(final String tag) {
        return rgb(look(tag).paper());
    }

    /** The glued strip along the top, a shade darker than the paper. */
    public static int band(final String tag) {
        return rgb(look(tag).band());
    }

    /** The folded corner, darker still. */
    public static int fold(final String tag) {
        return rgb(look(tag).fold());
    }

    /** The edge where the paper meets the board, faint. */
    public static int edge(final String tag) {
        return rgb(look(tag).edge());
    }

    /** The ink: near black on bright paper, near white on dark. */
    public static int ink(final String tag) {
        return rgb(look(tag).ink());
    }

    /** A note paints every frame, so each tag's colours are made once. */
    private static final Map<String, NoteLookView> LOOKS = new HashMap<>();

    private static NoteLookView look(final String tag) {
        return LOOKS.computeIfAbsent(tag == null ? "" : tag, k -> NoteLookBuilder.build(new NoteLookInput(tag)));
    }

    private static int rgb(final String hex) {
        return 0xFF000000 | Integer.parseInt(hex.substring(1), 16);
    }
}
