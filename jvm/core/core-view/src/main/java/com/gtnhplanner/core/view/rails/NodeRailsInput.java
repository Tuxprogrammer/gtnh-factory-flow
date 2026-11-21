package com.gtnhplanner.core.view.rails;

import java.util.List;

import javax.annotation.Nullable;

/**
 * What a card's two rails are decided from, as plain data: the node's recipe slots, the solve's flows at the node and
 * the wires on it, cut to this one node. A host reads its own plan and solve into it (the website its project, its
 * recipe and its {@code ThroughputResult}; the mod its node and the balancer); {@link NodeRails#build} says which rows
 * the rails get and their tone, and the node's verdict, the same on both.
 *
 * <p>
 * A resource is named by {@code key} ({@code <kind>:<id>}); slots, flows and wires meet on it, so a resource a recipe
 * lists twice is one row. A handle id is the host's own spelling of the wire dock on its side
 * ({@code input:item:<id>}, with {@code r<n>:} in front on a shared machine's recipe n): the rows carry it unread.
 *
 * @param enabled        the node exists and is switched on
 * @param hasResult      the solve has a result for the node
 * @param missingRecipe  the result says the node's recipe is missing
 * @param solveMode      the project is solved (Plan, Solve, Pool): the verdict the core decides. A Build project (its
 *                       diagnosis ladders are the website's own) gets verdict {@code legacy}
 * @param hasSlots       the node's recipe has at least one slot at either end
 * @param utilization    the share of full speed the node runs at, 0 when the solve has no figure
 * @param euT            the node's draw in EU/t (the books'), for the EU a unit costs
 * @param inputs         the input side
 * @param outputs        the output side
 */
public record NodeRailsInput(boolean enabled, boolean hasResult, boolean missingRecipe, boolean solveMode,
    boolean hasSlots, double utilization, double euT, Side inputs, Side outputs) {

    /**
     * One side of the node.
     *
     * @param slots the recipe's slots in recipe order (what the card shows)
     * @param flows the solve's flows in the solve's order; empty when it has none ({@link NodeRailsInput#hasResult} false)
     * @param wires one entry per wire on this side (one per edge)
     */
    public record Side(List<Slot> slots, List<Flow> flows, List<Wire> wires) {}

    /**
     * A recipe slot.
     *
     * @param consumed      the recipe does not say the slot is kept (a mould, a lens, a circuit)
     * @param chance        the chance an output comes with; null for always
     * @param consumeChance the share of runs that use an input up; null for every run
     * @param alternatives  how many resources fill the slot
     * @param name          the slot's display name; null for none
     * @param oreLabel      the label of an ore dictionary slot ("Any wooden planks"); null on any other
     */
    public record Slot(String key, String kind, String resourceId, @Nullable String name, @Nullable String oreLabel,
        String handleId, boolean consumed, @Nullable Double chance, @Nullable Double consumeChance, int alternatives) {}

    /** One flow of the solve: a resource and what the node moves of it at full speed. */
    public record Flow(String key, String kind, String resourceId, @Nullable String name, @Nullable String oreLabel,
        String handleId, double amountPerSecond) {}

    /** A wire on the node: the resource it carries and what it moves now. */
    public record Wire(String key, double transferredPerSecond) {}
}
