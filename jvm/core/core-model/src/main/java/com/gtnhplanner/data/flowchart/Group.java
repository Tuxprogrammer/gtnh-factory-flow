package com.gtnhplanner.data.flowchart;

import java.util.Random;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import javax.annotation.Nonnull;

import com.gtnhplanner.core.base.Uuid;

public class Group extends GraphData {

    public static final int GROUP_MIN_W = 300;
    public static final int GROUP_MIN_H = 200;

    private static Random colorRandom = new Random(12345);

    private int width = GROUP_MIN_W;
    private int height = GROUP_MIN_H;
    private int color = getRandomColor();
    private boolean collapsed;
    private boolean clampNodes;
    private boolean coverChildren;
    /**
     * Sorted for the same reason the graph's own maps are, and for one more: Gson builds a
     * SortedMap field as a TreeMap but a Map keyed on anything but String as an insertion-ordered
     * LinkedTreeMap, so the declared type here is what makes a reloaded group iterate like a
     * built one.
     */
    @Nonnull
    private final SortedMap<Uuid, GraphData> children = new TreeMap<>();
    @Nonnull
    private final Set<Uuid> nodeIds = new java.util.HashSet<>();

    public Group() {
        super(Uuid.randomUUID());
    }

    /**
     * A group with a known id (one a shared plan's card gives it), coloured by its id: read again, as a shared plan is
     * on every change, it looks the same.
     */
    protected Group(final Uuid id) {
        super(id);
        final Random byId = new Random(id.getMostSignificantBits() ^ id.getLeastSignificantBits());
        color = 0x7F000000 | (int) (byId.nextFloat() * 255) << 16
            | (int) (byId.nextFloat() * 255) << 8
            | (int) (byId.nextFloat() * 255);
    }

    @Override
    public String getType() {
        return "group";
    }

    private int getRandomColor() {
        // Plain ARGB, half opaque: the data model stays free of the UI library (and testable without it).
        final int r = (int) (colorRandom.nextFloat() * 255), g = (int) (colorRandom.nextFloat() * 255);
        final int b = (int) (colorRandom.nextFloat() * 255);
        return 0x7F000000 | r << 16 | g << 8 | b;
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

    public int getColor() {
        return this.color;
    }

    public void setColor(final int color) {
        this.color = color;
    }

    public boolean isCollapsed() {
        return this.collapsed;
    }

    public void setCollapsed(final boolean collapsed) {
        this.collapsed = collapsed;
    }

    public boolean isClampNodes() {
        return this.clampNodes;
    }

    public void setClampNodes(final boolean clampNodes) {
        this.clampNodes = clampNodes;
    }

    public boolean isCoverChildren() {
        return this.coverChildren;
    }

    public void setCoverChildren(final boolean coverChildren) {
        this.coverChildren = coverChildren;
    }

    public SortedMap<Uuid, GraphData> getChildren() {
        return this.children;
    }

    public Set<Uuid> getNodeIds() {
        return this.nodeIds;
    }
}
