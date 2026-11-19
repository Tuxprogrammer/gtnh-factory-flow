package com.gtnhplanner.data.flowchart;

import com.gtnhplanner.core.base.Uuid;

/**
 * A group whose recipes run on the same machines. A plain {@link Group} frames a part of the chart
 * and says nothing about the hardware; this one is a claim about it, so it holds one machine type
 * only (by recipe handler, never by the display name a player can rewrite), its members share one
 * configuration, and their machine counts add up into a single pool the solver can be asked to fit.
 *
 * <p>
 * A separate type rather than a flag on {@link Group}: the capacity, the single-type rule and the
 * shared settings mean nothing for an ordinary group, and the canvas draws this one differently so
 * the two are not mistaken for each other.
 */
public class MachineGroup extends Group {

    public static final String TYPE = "machine_group";

    /**
     * How many machines the pool is, or 0 for as many as it takes. A positive capacity caps the
     * group's summed machine time in the solve, so the chart is balanced to fit the hardware that
     * exists instead of being measured after the fact.
     */
    private int machineCapacity;

    /**
     * Whether {@link #machineCapacity} is the count the player set rather than a plain cap: Up to that many, or
     * Exactly with {@link #isExact()} ({@link PinRule#of(MachineGroup)}).
     */
    private boolean pinned;

    /**
     * Whether the set count is Exactly: its recipes' machine time adds up to it. Null, not false, when it is not, so a
     * save without it reads the same as one written before it (Up to).
     */
    private Boolean exact;

    /** Whether the set count is Exactly: only on a pinned group. */
    public boolean isExact() {
        return pinned && Boolean.TRUE.equals(exact);
    }

    public void setExact(final boolean exact) {
        this.exact = exact ? Boolean.TRUE : null;
    }

    /** Sets whether a count is set. Unsetting it lets go of Exactly too: a count set again is Up to. */
    public void setPinned(final boolean pinned) {
        this.pinned = pinned;
        if (!pinned) exact = null;
    }

    /**
     * The recipes in the order the shared card shows them, top first: the first is the host, whose card it is. Kept
     * alongside the group's node ids, which have no order. Empty in a group from an older save, which is not drawn as
     * a shared card.
     */
    private final java.util.List<Uuid> sections = new java.util.ArrayList<>();

    /** A shared machine: a group drawn as one card, its recipes as sections. */
    public boolean isShared() {
        return sections.size() >= 2;
    }

    /** Adds a recipe as the last section. */
    public void addSection(final Uuid nodeId) {
        if (sections.contains(nodeId)) return;
        sections.add(nodeId);
        getNodeIds().add(nodeId);
    }

    public void removeSection(final Uuid nodeId) {
        sections.remove(nodeId);
        getNodeIds().remove(nodeId);
    }

    public MachineGroup() {
        // GraphData names a fresh chart element after its type, which spells this one "Machine_group".
        setHeader("Machine Group");
    }

    /** A machine group with a known id (one a shared plan's card gives it). */
    public MachineGroup(final Uuid id) {
        super(id);
        setHeader("Machine Group");
    }

    @Override
    public String getType() {
        return TYPE;
    }

    public int getMachineCapacity() {
        return this.machineCapacity;
    }

    public void setMachineCapacity(final int machineCapacity) {
        this.machineCapacity = machineCapacity;
    }

    public boolean isPinned() {
        return this.pinned;
    }

    public java.util.List<Uuid> getSections() {
        return this.sections;
    }
}
