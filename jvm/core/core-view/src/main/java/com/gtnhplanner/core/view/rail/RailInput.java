package com.gtnhplanner.core.view.rail;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Everything the overview rail is worked out from, as plain records: what each card makes and uses, the drawers, the
 * machines the host lists, the spares and the player's view settings. The two hosts build one of these from their own
 * plan and solve (the website from its project and {@code ThroughputResult}, the mod from its board and answer); the
 * core knows neither. Names the player reads are already resolved here (a port's label, a drawer's label, an ore
 * dictionary slot's name), so the core never looks a resource up.
 *
 * <p>
 * Every list is in the order the host met the thing in: the core keeps it where it can (a tie in amount keeps the order
 * the plan met it in), and the conformance cases pin it.
 */
public record RailInput(boolean answered, Map<String, String> aliases, List<Flow> flows, List<DrawerIn> drawers,
    List<MachineIn> machines, Map<String, String> names, List<SpareIn> spares, List<String> cards, List<Wire> wires,
    ViewIn view) {

    /** One card's solved flows. A shared machine's recipes are each a flow of the same card. */
    public record Flow(String card, String machine, @Nullable Double machines, @Nullable Double utilization,
        List<Port> ports) {}

    /**
     * A port of a card: outputs are listed before inputs, as the website walks them. {@code key} is the resource key
     * the port asks for or makes ({@code item:iron_ingot}); {@code perSecond} what it moves at the card's nameplate
     * (scaled by the card's utilization); {@code set} the rate the player pinned on it, 0 when none.
     */
    public record Port(boolean output, String key, String kind, String id, String label, double perSecond,
        String handle, boolean wired, double set) {}

    /**
     * A drawer. {@code role} is source, product, byproduct or trash (anything else the host leaves out); {@code rule}
     * the drawer's rule word (at-least, exact, at-most, ignore); {@code set} whether a rule or a rate is in force;
     * {@code rate} its magnitude a second; {@code eaten} what a trash drawer voids a second.
     */
    public record DrawerIn(String id, String kind, String resourceId, String label, String role, String rule,
        boolean set, double rate, boolean unmet, double eaten) {}

    /** What an Up to card says about itself. */
    public record UpToIn(@Nullable String reason, double reached, double capacity) {}

    /**
     * A machine line. {@code rule} is any, upTo or exact; {@code count} what the card builds (an Up to card's is
     * already what runs). The figures are EU/t (steam L/s) at the peak and on average, absent when the machine has
     * none.
     */
    public record MachineIn(String nodeId, String label, double count, boolean tank, boolean pinned, String rule,
        @Nullable Double pin, @Nullable UpToIn upTo, @Nullable String tier, @Nullable Integer amps, boolean multiblock,
        @Nullable String madeTier, String state, @Nullable Double euT, @Nullable Double avgEuT,
        @Nullable Double steamLs, @Nullable Double avgSteamLs, @Nullable Double madeEuT,
        @Nullable Double avgMadeEuT) {}

    /** A place that can bear a spare. {@code resource} is {@code kind:id}. */
    public record BearerIn(String key, String nodeId, String handle, boolean output, String name, String machine,
        String resource, double max, boolean solverPick, boolean energy, double share, List<PortRef> ports) {}

    public record PortRef(String nodeId, String handle) {}

    /** A leftover. {@code stored} is the player's saved split by bearer key, absent when none. */
    public record SpareIn(String key, boolean importing, boolean custom, @Nullable Map<String, Double> stored,
        List<BearerIn> bearers) {}

    /** A wire between two cards, for how far each card is from where the plan starts. */
    public record Wire(String from, String to) {}

    /**
     * The player's view: the search text, peak or average power, and the power dial (eu, amps, or a tier name).
     */
    public record ViewIn(String filter, boolean peak, String power) {}
}
