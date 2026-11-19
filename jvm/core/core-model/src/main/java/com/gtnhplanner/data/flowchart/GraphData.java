package com.gtnhplanner.data.flowchart;

import com.gtnhplanner.core.base.Uuid;

/**
 * A thing on the board that is not a card or a wire (a note, a group, a drawer, the summary panel): an id, a place and
 * a header, and its type, which a save names it by. Moved from the mod with the plan model.
 */
public abstract class GraphData {

    protected final Uuid id;
    protected int x;
    protected int y;
    protected String header;
    protected final String type = getType();

    protected GraphData(Uuid id) {
        this.id = id;
        header = capitalize(getType());
    }

    /** commons-lang's StringUtils.capitalize: the first character in title case, the rest as it is. */
    private static String capitalize(final String s) {
        if (s == null || s.isEmpty()) return s;
        final char first = s.charAt(0), title = Character.toTitleCase(first);
        return first == title ? s : title + s.substring(1);
    }

    public abstract String getType();

    public Uuid getId() {
        return this.id;
    }

    public int getX() {
        return this.x;
    }

    public void setX(final int x) {
        this.x = x;
    }

    public int getY() {
        return this.y;
    }

    public void setY(final int y) {
        this.y = y;
    }

    public String getHeader() {
        return this.header;
    }

    public void setHeader(final String header) {
        this.header = header;
    }
}
