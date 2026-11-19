package com.gtnhplanner.data.flowchart.balancer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.flowchart.DrawerTypes;

/**
 * Everything one solve reads: the plan as plain data, so the solve can run on any thread and on any host.
 *
 * <p>
 * The host builds it from its own plan (the mod: {@code GraphSolve.input}, on the client thread, where the machine
 * effects are computed, ingredient names resolved and ports grouped into ingredients; a plan file: {@code
 * SolveInputJson.read}). What it holds is plain data - numbers, strings, ids and enums in unmodifiable collections - so
 * {@link Balancer#solve(SolveInput)} touches no game, localization or plan code. Notes still carry keys and arguments;
 * rendering them ({@link Note#render()}) is the host's.
 *
 * <p>
 * A snapshot never changes, and editing the plan afterwards does not reach it: submit a new one.
 *
 * @param mode       the balance mode to solve in.
 * @param choice     a stored excess choice to honour, or null for the solver's own answer.
 * @param machines   one per node, in the graph's (id) order.
 * @param edges      every drawn edge, in the graph's (id) order.
 * @param pools      the capped machine groups.
 * @param drawers    every drawer, in the graph's (id) order.
 * @param extentPins per-node extent pins in crafts per second, beyond what the nodes carry.
 * @param spares     the plan's stored spare splits: per leftover, each bearer's share ({@link Spares}).
 */
public record SolveInput(BalanceMode mode, @Nullable ChoiceKey choice, List<Machine> machines, List<EdgeIn> edges,
    List<PoolIn> pools, List<DrawerIn> drawers, Map<Uuid, Double> extentPins, Map<String, Map<String, Double>> spares) {

    public SolveInput {
        machines = List.copyOf(machines);
        edges = List.copyOf(edges);
        pools = List.copyOf(pools);
        drawers = List.copyOf(drawers);
        extentPins = Collections.unmodifiableMap(new LinkedHashMap<>(extentPins));
        final Map<String, Map<String, Double>> copy = new LinkedHashMap<>();
        for (final Map.Entry<String, Map<String, Double>> e : spares.entrySet())
            copy.put(e.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(e.getValue())));
        spares = Collections.unmodifiableMap(copy);
    }

    /** A snapshot with no stored spare splits. */
    public SolveInput(final BalanceMode mode, @Nullable final ChoiceKey choice, final List<Machine> machines,
        final List<EdgeIn> edges, final List<PoolIn> pools, final List<DrawerIn> drawers,
        final Map<Uuid, Double> extentPins) {
        this(mode, choice, machines, edges, pools, drawers, extentPins, Map.of());
    }

    /**
     * One port of a machine.
     *
     * @param amount     units per craft, before chance (fractional for a generator's exact flows).
     * @param chance     the chance the units appear (1 for a plain port).
     * @param multiplier the node's own consumption / productivity multiplier on this port.
     * @param resource   the ingredient, as a number shared by every port of the same snapshot that
     *                   {@code canConnect}s to it; equal numbers mean the same ingredient.
     * @param name       the ingredient's display name, resolved when the snapshot was taken.
     * @param free       whether the pack gives this ingredient away ({@code CoreSettings.isFreeIngredient}).
     */
    public record PortIn(double amount, float chance, double multiplier, int resource, String name, boolean free) {}

    /**
     * One machine (node) with its effect already computed.
     *
     * @param durationTicks     ticks per craft after overclocking and tick modifiers, exactly.
     * @param energyPerTick     energy per tick of one craft, exactly.
     * @param throughputFactor  how many crafts one machine runs at once (parallels).
     * @param countFixed        whether the machine count is pinned.
     * @param machineCount      the configured machine count (the pin when {@code countFixed}).
     * @param targetOutputRates the node's target output rates, units/s by output index.
     * @param properties        the node's numeric recipe properties, for the per-run totals ({@link BalanceResult}),
     *                          by the host's own property key (the mod's is its {@code RecipeProperty}): the solver only
     *                          sums them by key, so a key needs nothing but {@code equals} and {@code hashCode}.
     * @param targetInputRates  the node's target input rates, units/s by input index.
     */
    public record Machine(Uuid id, String name, double durationTicks, double energyPerTick, int throughputFactor,
        List<PortIn> inputs, List<PortIn> outputs, boolean countFixed, int machineCount,
        Map<Integer, Double> targetOutputRates, Map<Object, Number> properties,
        Map<Integer, Double> targetInputRates) {

        public Machine {
            inputs = List.copyOf(inputs);
            outputs = List.copyOf(outputs);
            targetOutputRates = Collections.unmodifiableMap(new LinkedHashMap<>(targetOutputRates));
            properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
            targetInputRates = Collections.unmodifiableMap(new LinkedHashMap<>(targetInputRates));
        }

        /** A machine with no input targets. */
        public Machine(final Uuid id, final String name, final double durationTicks, final double energyPerTick,
            final int throughputFactor, final List<PortIn> inputs, final List<PortIn> outputs, final boolean countFixed,
            final int machineCount, final Map<Integer, Double> targetOutputRates,
            final Map<Object, Number> properties) {
            this(
                id,
                name,
                durationTicks,
                energyPerTick,
                throughputFactor,
                inputs,
                outputs,
                countFixed,
                machineCount,
                targetOutputRates,
                properties,
                Map.of());
        }

        public List<PortIn> ports(final boolean input) {
            return input ? inputs : outputs;
        }
    }

    /** One drawn edge, by node id and port index. */
    public record EdgeIn(Uuid id, Uuid source, int sourceOutput, Uuid target, int targetInput) {}

    /**
     * A machine group: its member node ids and how many machines it is. A cap by default (the recipes fit in that many
     * machines); {@code exact}, the count is pinned and the recipes' machine time adds up to exactly that many, as a
     * pinned shared machine on Factory Flow's board.
     *
     * @param capacity how many machines: a whole number from the board, fractional only in a plan a test or the
     *                 website wrote ({@code SolveInputJson} reads both).
     * @param upTo     the Up to rule (docs/design/up-to.md), read only when {@code exact} is false: the machines run
     *                 as hard as the plan lets them, never above {@code capacity}, and no forced flow makes up what
     *                 they cannot do. Anchors its part of the plan. A one-card pool is how a single card's Up to count
     *                 reaches the solver. False: a plain cap.
     */
    public record PoolIn(List<Uuid> machines, double capacity, boolean exact, boolean upTo) {

        public PoolIn {
            machines = List.copyOf(machines);
        }

        public PoolIn(final List<Uuid> machines, final double capacity, final boolean exact) {
            this(machines, capacity, exact, false);
        }

        public PoolIn(final List<Uuid> machines, final double capacity) {
            this(machines, capacity, false);
        }
    }

    /**
     * One drawer. {@code rule} is what the solver applies (the board drawer's effective rule: trash keeps a rule it does
     * not apply), and {@code label} the name its notes use: the drawer's label, else its resource key. A buffer's
     * {@code links} are the output ports feeding it and its {@code takers} the input ports it feeds; {@code takers} is
     * empty for every other kind.
     */
    public record DrawerIn(Uuid id, String label, DrawerTypes.Kind kind, DrawerTypes.Rule rule, double rate,
        List<LinkIn> links, List<LinkIn> takers) {

        public DrawerIn {
            links = List.copyOf(links);
            takers = List.copyOf(takers);
        }

        /** A drawer with no takers: every kind but a buffer. */
        public DrawerIn(final Uuid id, final String label, final DrawerTypes.Kind kind, final DrawerTypes.Rule rule,
            final double rate, final List<LinkIn> links) {
            this(id, label, kind, rule, rate, links, List.of());
        }

        /** Every port the drawer is linked to, makers' and users' alike (for joining islands). */
        public List<LinkIn> allLinks() {
            if (takers.isEmpty()) return links;
            final List<LinkIn> all = new ArrayList<>(links);
            all.addAll(takers);
            return all;
        }
    }

    /**
     * One node port a drawer is attached to, by node id and port index; the side is the drawer's (a source's links are
     * inputs, a buffer's takers inputs, every other link an output). Equal by value; hashed and printed as the board's
     * drawer link is ({@code node#port}).
     */
    public record LinkIn(Uuid nodeId, int portIndex) {

        public LinkIn {
            Objects.requireNonNull(nodeId, "nodeId");
        }

        @Override
        public int hashCode() {
            return 31 * nodeId.hashCode() + portIndex;
        }

        @Override
        public String toString() {
            return nodeId + "#" + portIndex;
        }
    }
}
