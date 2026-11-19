package com.gtnhplanner.data.flowchart.balancer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.gtnhplanner.core.base.CoreSettings;
import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.core.model.ResourceKey;
import com.gtnhplanner.data.MachineConfig;
import com.gtnhplanner.data.effect.EffectResult;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.DrawerTypes;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Group;
import com.gtnhplanner.data.flowchart.MachineGroup;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.PinRule;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.data.flowchart.balancer.SolveInput.DrawerIn;
import com.gtnhplanner.data.flowchart.balancer.SolveInput.EdgeIn;
import com.gtnhplanner.data.flowchart.balancer.SolveInput.LinkIn;
import com.gtnhplanner.data.flowchart.balancer.SolveInput.Machine;
import com.gtnhplanner.data.flowchart.balancer.SolveInput.PoolIn;
import com.gtnhplanner.data.flowchart.balancer.SolveInput.PortIn;
import com.gtnhplanner.data.flowchart.balancer.alternatives.Alternatives;
import com.gtnhplanner.data.properties.RecipeProperty;

/**
 * The solver on a {@link Graph}: the snapshot ({@link #input}) and the entry points that take a graph, kept for the
 * callers and tests written against the graph. Client thread only: the snapshot is taken here, on the calling thread -
 * that is where the machine effects are computed (the GregTech overclock calculator keeps shared mutable state and
 * writes into the node, so it must never run anywhere else) and where ingredient names are resolved. Ports are numbered
 * into ingredients by their resource, as the website's adapter numbers them ({@link Ingredients}), and a wire reaches
 * the solver only when its input's slot takes what its output carries ({@link Port#feeds}): one rule on both sides, so
 * the same plan is the same input. What comes out is the core's plain {@link SolveInput}, safe on any thread.
 */
public final class GraphSolve {

    private GraphSolve() {}

    /** The graph in its own balance mode, honouring the stored excess choice. Client thread only. */
    public static SolveInput input(final Graph graph) {
        return input(graph, graph.getBalanceMode(), graph.getExcessChoice(), Map.of());
    }

    /** The graph in the given mode. Client thread only. */
    public static SolveInput input(final Graph graph, final BalanceMode mode, @Nullable final ChoiceKey choice) {
        return input(graph, mode, choice, Map.of());
    }

    /**
     * The graph in the given mode with extra per-node extent pins (crafts/s). Client thread only:
     * this computes every node's effect and resolves every ingredient's name.
     */
    public static SolveInput input(final Graph graph, final BalanceMode mode, @Nullable final ChoiceKey choice,
        final Map<Uuid, Double> extentPins) {
        final List<Edge> wires = takenWires(graph);
        final Ingredients ingredients = Ingredients.of(graph, wires);
        final List<Machine> machines = new ArrayList<>(graph.nodes.size());
        final List<PoolIn> pools = new ArrayList<>();
        // The count rules are AUTO's; the older modes read every set count as a pin, as they always did.
        final boolean auto = mode == BalanceMode.AUTO;
        for (final Node node : graph.getNodes()) {
            if (!auto) {
                machines.add(machineOf(node, ingredients, node.isMachineCountFixed()));
                continue;
            }
            final PinRule rule = PinRule.of(node);
            final double count = node.machineConfig.machineCount();
            // An Up to count is a one-machine pool: the solver finds how many run, never above it (docs/design/up-to.md).
            // Exactly is the fixed count it always was.
            if (rule == PinRule.UP_TO && count > 0) pools.add(new PoolIn(List.of(node.id), count, false, true));
            machines.add(machineOf(node, ingredients, rule == PinRule.EXACT));
        }
        final List<EdgeIn> edges = new ArrayList<>(wires.size());
        for (final Edge edge : wires) {
            edges.add(
                new EdgeIn(
                    edge.id,
                    edge.sourceNodeId,
                    edge.sourceOutputIndex,
                    edge.targetNodeId,
                    edge.targetInputIndex));
        }
        for (final Group group : graph.getGroups()) {
            if (group instanceof final MachineGroup machineGroup && machineGroup.getMachineCapacity() > 0
                && !group.getNodeIds()
                    .isEmpty()) {
                // Sorted: the group's id set is a HashSet, and the pool's member order should be the chart's.
                final List<Uuid> members = new ArrayList<>(group.getNodeIds());
                members.sort(Uuid::compareTo);
                // A set count is Up to or Exactly; a capacity nobody set stays the plain cap.
                final PinRule rule = PinRule.of(machineGroup);
                final boolean exact = auto ? rule == PinRule.EXACT : machineGroup.isPinned();
                pools.add(
                    new PoolIn(
                        members.stream()
                            .toList(),
                        machineGroup.machineCount(),
                        exact,
                        auto && rule == PinRule.UP_TO));
            }
        }
        // By first machine, as the website orders them: the same plan is the same input on both sides.
        pools.sort(
            (a, b) -> a.machines()
                .get(0)
                .compareTo(
                    b.machines()
                        .get(0)));
        final List<DrawerIn> drawers = new ArrayList<>(graph.drawers.size());
        for (final Drawer drawer : graph.getDrawers()) {
            drawers.add(
                new DrawerIn(
                    drawer.getId(),
                    drawer.getLabel()
                        .isEmpty() ? drawer.getResourceKey() : drawer.getLabel(),
                    drawer.getKind(),
                    drawer.effectiveRule(),
                    drawer.getRate(),
                    links(drawer.getLinks()),
                    links(drawer.getTakers())));
        }
        final Map<Uuid, Double> pins = new LinkedHashMap<>();
        for (final Map.Entry<Uuid, Double> e : extentPins.entrySet()) pins.put(e.getKey(), e.getValue());
        return new SolveInput(mode, choice, machines, edges, pools, drawers, pins, graph.spareShares);
    }

    /**
     * The plan's wires the solver reads: all of them but a wire whose input no longer takes what its output carries
     * (one a save made under the older rule, when two items sharing an ore name wired to each other). Such a wire stays
     * in the plan and on the board, flagged ({@link #notTaken}), and carries nothing, as the website's adapter leaves
     * it out of the solve. A wire whose port is gone from its card goes through as before: the solver skips it.
     */
    private static List<Edge> takenWires(final Graph graph) {
        final List<Edge> out = new ArrayList<>(graph.edges.size());
        for (final Edge edge : graph.getEdges()) if (!notTaken(graph, edge)) out.add(edge);
        return out;
    }

    /** Whether the wire's input exists and does not take what its output carries ({@link Port#feeds}). */
    public static boolean notTaken(final Graph graph, final Edge edge) {
        final Node src = graph.nodes.get(edge.sourceNodeId), dst = graph.nodes.get(edge.targetNodeId);
        if (src == null || dst == null
            || edge.sourceOutputIndex < 0
            || edge.sourceOutputIndex >= src.outputs.size()
            || edge.targetInputIndex < 0
            || edge.targetInputIndex >= dst.inputs.size()) return false;
        return !src.outputs.get(edge.sourceOutputIndex)
            .feeds(dst.inputs.get(edge.targetInputIndex));
    }

    /** A drawer's links as the solver reads them: node and port, without the wire's steering dots. */
    private static List<LinkIn> links(final List<Drawer.Link> links) {
        final List<LinkIn> out = new ArrayList<>(links.size());
        for (final Drawer.Link l : links) out.add(new LinkIn(l.nodeId(), l.portIndex()));
        return out;
    }

    private static Machine machineOf(final Node node, final Ingredients ingredients, final boolean exact) {
        final MachineConfig cfg = node.machineConfig;
        final EffectResult effect = node.isPower() ? null : cfg.computeEffect(node.properties);
        final List<PortIn> inputs = new ArrayList<>(node.inputs.size());
        for (int i = 0; i < node.inputs.size(); i++) {
            inputs.add(
                ingredients.portOf(
                    node.inputs.get(i),
                    cfg.inputMultiplier(i) * (effect == null ? 1 : effect.inputMultiplier(i))));
        }
        final List<PortIn> outputs = new ArrayList<>(node.outputs.size());
        for (int i = 0; i < node.outputs.size(); i++) {
            outputs.add(
                ingredients.portOf(
                    node.outputs.get(i),
                    cfg.outputMultiplier(i) * (effect == null ? 1 : effect.outputMultiplier(i))));
        }
        final Map<Object, Number> properties = new LinkedHashMap<>();
        for (final Map.Entry<RecipeProperty<?>, Object> e : node.properties.entrySet()) {
            if (e.getValue() instanceof final Number n) properties.put(e.getKey(), n);
        }
        final Map<Integer, Double> targets = new LinkedHashMap<>(), inputTargets = new LinkedHashMap<>();
        for (final Map.Entry<Integer, Double> t : node.targetOutputRates.entrySet()) {
            if (t.getKey() != null && t.getValue() != null) targets.put(t.getKey(), t.getValue());
        }
        for (final Map.Entry<Integer, Double> t : node.targetInputRates.entrySet()) {
            if (t.getKey() != null && t.getValue() != null) inputTargets.put(t.getKey(), t.getValue());
        }
        // A non-recipe machine (a generator) runs one craft a second at its exact rates, draws through no overclock,
        // and its EU is an output port: nothing here for the effect to compute.
        final boolean power = node.isPower();
        return new Machine(
            node.id,
            node.machineName == null ? "" : node.machineName,
            power ? 20 : effect.duration(),
            power ? 0 : effect.energy(),
            power ? 1 : effect.throughputFactor(),
            inputs,
            outputs,
            exact,
            cfg.machineCount(),
            targets,
            properties,
            inputTargets);
    }

    /**
     * The ingredient numbers of one snapshot, as the website's adapter gives them ({@code adapter/model.ts}): every
     * port the number of its resource, first seen first (cards in plan order, a card's inputs then its outputs); then a
     * wired input the number of the output feeding it (an ore dictionary slot fed one of its items is that item), and a
     * buffer's takers the number of its first maker (the solver joins a buffer's ports of one number). The solver reads
     * the numbers as which ports are one ingredient: the buffers it may join and the wiring hints it gives.
     */
    private static final class Ingredients {

        private final Map<ResourceKey, Integer> numbers = new HashMap<>();
        /** Per port, by identity: a port is one object in one place on one card. */
        private final Map<Port<?>, Integer> ofPort = new IdentityHashMap<>();

        static Ingredients of(final Graph graph, final List<Edge> wires) {
            final Ingredients in = new Ingredients();
            for (final Node node : graph.getNodes()) {
                for (final Port<?> p : node.inputs) in.ofPort.put(p, in.numberOf(p.resource()));
                for (final Port<?> p : node.outputs) in.ofPort.put(p, in.numberOf(p.resource()));
            }
            for (final Edge e : wires) {
                final Port<?> from = port(graph, e.sourceNodeId, e.sourceOutputIndex, false),
                    to = port(graph, e.targetNodeId, e.targetInputIndex, true);
                if (from != null && to != null) in.ofPort.put(to, in.ofPort.get(from));
            }
            for (final Drawer drawer : graph.getDrawers()) {
                if (drawer.getKind() != DrawerTypes.Kind.BUFFER || drawer.getLinks()
                    .isEmpty()) continue;
                final Drawer.Link first = drawer.getLinks()
                    .get(0);
                final Port<?> maker = port(graph, first.nodeId(), first.portIndex(), false);
                if (maker == null) continue;
                final int made = in.ofPort.get(maker);
                for (final Drawer.Link taker : drawer.getTakers()) {
                    final Port<?> p = port(graph, taker.nodeId(), taker.portIndex(), true);
                    if (p != null) in.ofPort.put(p, made);
                }
            }
            return in;
        }

        private int numberOf(final ResourceKey key) {
            return numbers.computeIfAbsent(key, k -> numbers.size());
        }

        @Nullable
        private static Port<?> port(final Graph graph, final Uuid nodeId, final int index, final boolean input) {
            final Node node = graph.nodes.get(nodeId);
            if (node == null) return null;
            final List<Port<?>> ports = input ? node.inputs : node.outputs;
            return index >= 0 && index < ports.size() ? ports.get(index) : null;
        }

        PortIn portOf(final Port<?> port, final double multiplier) {
            final String name = port.getDisplayName();
            return new PortIn(
                port.amount(),
                port.getChance(),
                multiplier,
                ofPort.get(port),
                name == null ? "" : name,
                name != null && CoreSettings.get()
                    .isFreeIngredient(name));
        }
    }

    // ── Balancer's entry points on a graph (Balancer itself takes a SolveInput) ──

    /**
     * Runs the mode's chain against the chart and returns the context plus its {@link Settlement}
     * (a point, or the reason there is none).
     */
    public static Balancer.RunContext run(final BalanceMode mode, final Graph graph) {
        return run(mode, graph, Profiler.disabled());
    }

    /** {@link Balancer#run(SolveInput, Profiler)} on a snapshot of the graph in {@code mode}. */
    public static Balancer.RunContext run(final BalanceMode mode, final Graph graph, final Profiler profiler) {
        return Balancer.run(input(graph, mode, null), profiler);
    }

    /** {@link Balancer#alternatives(SolveInput)} on the graph in {@code mode}, with no stored choice. */
    public static Alternatives alternatives(final BalanceMode mode, final Graph graph) {
        return alternatives(mode, graph, null, Map.of());
    }

    /**
     * As above, but with a stored {@link ChoiceKey} to honour (applied between the solve and the
     * enumeration) and per-machine extent pins.
     */
    public static Alternatives alternatives(final BalanceMode mode, final Graph graph, @Nullable final ChoiceKey choice,
        final Map<Uuid, Double> extraExtentPins) {
        return Balancer.alternatives(input(graph, mode, choice, extraExtentPins));
    }

    /**
     * {@link Balancer#solveWithAlternatives(SolveInput)} on a snapshot of the graph.
     *
     * @param choice          a stored {@link ChoiceKey} to honour between the solve and the
     *                        enumeration, or null for the solver's own answer.
     * @param extraExtentPins per-machine extent pins (crafts/s), e.g. from a target-rate pin.
     */
    public static Balancer.Answer solveWithAlternatives(final BalanceMode mode, final Graph graph,
        @Nullable final ChoiceKey choice, final Map<Uuid, Double> extraExtentPins) {
        return Balancer.solveWithAlternatives(input(graph, mode, choice, extraExtentPins));
    }

    /**
     * The graph's balance in {@code mode}, honouring the stored excess choice: a snapshot taken
     * here, on the calling thread, then {@link Balancer#solve(SolveInput)}. Client thread only (the
     * snapshot computes machine effects and the excess choice lives on the {@code Plan}).
     */
    @Nonnull
    public static BalanceResult balance(final Graph graph, final BalanceMode mode) {
        return Balancer.solve(input(graph, mode, graph.getExcessChoice()));
    }
}
