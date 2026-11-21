package com.gtnhplanner.core.view;

import java.util.List;

import javax.annotation.Nullable;

/**
 * A rich tip as data: a title, a muted line under it, rows of label and value, notes and the mouse actions. Both hosts
 * draw what a view puts in one (the mod's {@code popup.Tip}, the website's {@code TipSpec}); the words are the core's,
 * said once. A tone is {@code muted}, {@code warn} or {@code red}; none means plain. A word between asterisks in a note
 * is drawn in gold.
 */
public record TipView(String id, String title, @Nullable String sub, List<Row> rows, List<Note> notes,
    List<Action> actions) {

    public record Row(String label, String value, @Nullable String tone) {}

    public record Note(String text, @Nullable String tone) {}

    /** {@code input} is left, right, wheel, middle, drag or key. */
    public record Action(String input, String label) {}

    public static TipView of(final String id, final String title) {
        return new TipView(id, title, null, List.of(), List.of(), List.of());
    }

    /** Writes the tip as the next value of an object or array. */
    public void write(final Json j) {
        j.begin()
            .str("id", id)
            .str("title", title)
            .opt("sub", sub);
        j.list("rows", rows, (jj, r) -> jj.begin().str("label", r.label()).str("value", r.value()).opt("tone", r.tone()).end());
        j.list("notes", notes, (jj, n) -> jj.begin().str("text", n.text()).opt("tone", n.tone()).end());
        j.list("actions", actions, (jj, a) -> jj.begin().str("input", a.input()).str("label", a.label()).end());
        j.end();
    }
}
