package com.gtnhplanner.data.flowchart;

import java.util.List;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.Uuid;

/**
 * A group on the board: a named, coloured frame round part of the plan, moved as one and collapsed down to a summary
 * card ({@code docs/design/groups.md}). It is the website's board ({@code FactoryPocket}, plan JSON {@code pockets[]}),
 * so a plan's groups travel there and back: members say which group they are in ({@code Node.group},
 * {@code Drawer.getGroup()}, {@link Note#getGroup()}), groups nest through {@link #parent}. Groups take no part in the
 * solve.
 *
 * <p>
 * Not the old {@link Group} (the base of {@link MachineGroup}, a shared machine): that one is about the hardware.
 */
public class BoardGroup {

    /** The title strip's height, in board px. */
    public static final int STRIP = 20;
    /** The room kept round the members inside the frame, a grid cell... */
    public static final int PAD = 10;
    /** ...and the smallest a frame gets. */
    public static final int MIN_W = 160, MIN_H = 80;

    Uuid id;
    /**
     * The website's id of the board this came from ({@code pocket-...}), kept so it goes back out the same; or null.
     */
    @Nullable
    String ffId;
    String name = "";
    /** A colour tag ({@code NoteColors}); null takes the theme's, or the one its id gives it. */
    @Nullable
    String color;
    /** The group it sits in, or null for the board. */
    @Nullable
    Uuid parent;
    /** The frame, absolute board px; a collapsed group's summary card sits at its corner. */
    int x, y, width = MIN_W, height = MIN_H;
    boolean collapsed;
    /** The website's paper and ruling, kept for the round trip; never drawn. */
    @Nullable
    String theme, pattern;

    public BoardGroup() {
        this(Uuid.randomUUID());
    }

    public BoardGroup(final Uuid id) {
        this.id = id;
    }

    /** The id the website knows it by. */
    public String webId() {
        return ffId != null && !ffId.isEmpty() ? ffId : "pocket-" + id;
    }

    /** The colour tag drawn: its own, else its paper's, else the paper its id gives it. */
    public String colorTag() {
        if (color != null && !color.isEmpty()) return color;
        return themeColor(theme != null && !theme.isEmpty() ? theme : paperForId(webId()));
    }

    /** The website's dark papers, in its order ({@code BOARD_PAPER_IDS}). */
    public static final List<String> PAPERS = List
        .of("slate", "blueprint", "chalkboard", "graphite", "gunmetal", "midnight", "charcoal", "void");

    /** The website's {@code paperForBoardId}: the same 31 hash Java's String has, into the dark papers. */
    public static String paperForId(final String id) {
        return PAPERS.get(Math.abs(id.hashCode()) % PAPERS.size());
    }

    /** A paper's colour tag (docs/design/groups.md, Colours); an unknown paper reads as steel. */
    public static String themeColor(final String theme) {
        return switch (theme) {
            case "blueprint" -> "azure";
            case "chalkboard" -> "green";
            case "graphite" -> "brown";
            case "gunmetal" -> "light_gray";
            case "midnight" -> "blue";
            case "charcoal" -> "onyx";
            case "void" -> "black";
            case "parchment" -> "amber";
            case "paper" -> "white";
            default -> "steel";
        };
    }

    /** The colours a new group takes, the first nobody wears. */
    public static final List<String> NEW_COLORS = List.of(
        "light_blue",
        "lime",
        "orange",
        "magenta",
        "yellow",
        "cyan",
        "pink",
        "purple",
        "scarlet",
        "emerald",
        "azure",
        "amber");

    /** The colour for a new group on a plan whose groups wear {@code worn}. */
    public static String newColor(final java.util.Collection<String> worn) {
        for (final String c : NEW_COLORS) if (!worn.contains(c)) return c;
        return NEW_COLORS.get(0);
    }

    /** A copy with a new id (copy and paste); the website id is not kept. */
    public BoardGroup copy() {
        final BoardGroup g = new BoardGroup();
        g.name = name;
        g.color = colorTag();
        g.parent = parent;
        g.x = x;
        g.y = y;
        g.width = width;
        g.height = height;
        g.collapsed = collapsed;
        g.theme = theme;
        g.pattern = pattern;
        return g;
    }

    public Uuid getId() {
        return this.id;
    }

    public String getFfId() {
        return this.ffId;
    }

    public void setFfId(final String ffId) {
        this.ffId = ffId;
    }

    public String getName() {
        return this.name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public String getColor() {
        return this.color;
    }

    public void setColor(final String color) {
        this.color = color;
    }

    public Uuid getParent() {
        return this.parent;
    }

    public void setParent(final Uuid parent) {
        this.parent = parent;
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

    public int getWidth() {
        return this.width;
    }

    public void setWidth(final int width) {
        this.width = width;
    }

    public int getHeight() {
        return this.height;
    }

    public void setHeight(final int height) {
        this.height = height;
    }

    public boolean isCollapsed() {
        return this.collapsed;
    }

    public void setCollapsed(final boolean collapsed) {
        this.collapsed = collapsed;
    }

    public String getTheme() {
        return this.theme;
    }

    public void setTheme(final String theme) {
        this.theme = theme;
    }

    public String getPattern() {
        return this.pattern;
    }

    public void setPattern(final String pattern) {
        this.pattern = pattern;
    }
}
