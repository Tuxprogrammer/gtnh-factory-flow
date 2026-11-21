package com.gtnhplanner.core.view.note;

import java.util.List;

/**
 * The colours a sticky note's menu offers, in order, each a colour tag and the name the menu shows. Groups take the
 * same list for their Colour menu. Both sides read it from here; {@code conformance/view/note/menu.json} holds the
 * website's list and the core's test compares the two.
 */
public final class NoteMenu {

    private NoteMenu() {}

    /** Tag, then name, per entry. */
    public static final List<String[]> ENTRIES = List.of(
        new String[] { "yellow", "Yellow" },
        new String[] { "orange", "Orange" },
        new String[] { "pink", "Pink" },
        new String[] { "scarlet", "Red" },
        new String[] { "lime", "Lime" },
        new String[] { "emerald", "Green" },
        new String[] { "light_blue", "Light blue" },
        new String[] { "azure", "Blue" },
        new String[] { "purple", "Purple" },
        new String[] { "white", "White" },
        new String[] { "steel", "Grey" });
}
