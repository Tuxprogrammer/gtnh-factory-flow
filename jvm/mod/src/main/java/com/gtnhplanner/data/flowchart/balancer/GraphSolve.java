package com.gtnhplanner.data.flowchart.balancer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.oredict.OreDictionary;

import com.gtnhplanner.CoreIds;
import com.gtnhplanner.core.base.CoreSettings;
import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.MachineConfig;
import com.gtnhplanner.data.effect.EffectResult;
import com.gtnhplanner.data.flowchart.Drawer;
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
 * writes into the node, so it must never run anywhere else), where ingredient names are resolved, and where ports are
 * grouped into ingredients with {@link Port#canConnect}. What comes out is the core's plain {@link SolveInput}, safe on
 * any thread.
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
        final Map<UUID, Double> extentPins) {
        final Ingredients ingredients = new Ingredients();
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
            final int count = node.machineConfig.getMachineCount();
            // An Up to count is a one-machine pool: the solver finds how many run, never above it (docs/design/up-to.md).
            // Exactly is the fixed count it always was.
            if (rule == PinRule.UP_TO && count > 0) pools.add(new PoolIn(List.of(CoreIds.core(node.id)), count, false, true));
            machines.add(machineOf(node, ingredients, rule == PinRule.EXACT));
        }
        final List<EdgeIn> edges = new ArrayList<>(graph.edges.size());
        for (final Edge edge : graph.getEdges()) {
            edges.add(
                new EdgeIn(
                    CoreIds.core(edge.id),
                    CoreIds.core(edge.sourceNodeId),
                    edge.sourceOutputIndex,
                    CoreIds.core(edge.targetNodeId),
                    edge.targetInputIndex));
        }
        for (final Group group : graph.getGroups()) {
            if (group instanceof final MachineGroup machineGroup && machineGroup.getMachineCapacity() > 0
                && !group.getNodeIds()
                    .isEmpty()) {
                // Sorted: the group's id set is a HashSet, and the pool's member order should be the chart's.
                final List<UUID> members = new ArrayList<>(group.getNodeIds());
                members.sort(UUID::compareTo);
                // A set count is Up to or Exactly; a capacity nobody set stays the plain cap.
                final PinRule rule = PinRule.of(machineGroup);
                final boolean exact = auto ? rule == PinRule.EXACT : machineGroup.isPinned();
                pools.add(
                    new PoolIn(
                        members.stream()
                            .map(CoreIds::core)
                            .toList(),
                        machineGroup.getMachineCapacity(),
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
                    CoreIds.core(drawer.getId()),
                    drawer.getLabel()
                        .isEmpty() ? drawer.getResourceKey() : drawer.getLabel(),
                    drawer.getKind(),
                    drawer.effectiveRule(),
                    drawer.getRate(),
                    links(drawer.getLinks()),
                    links(drawer.getTakers())));
        }
        final Map<Uuid, Double> pins = new LinkedHashMap<>();
        for (final Map.Entry<UUID, Double> e : extentPins.entrySet()) pins.put(CoreIds.core(e.getKey()), e.getValue());
        return new SolveInput(mode, choice, machines, edges, pools, drawers, pins, graph.spareShares);
    }

    /** A drawer's links as the solver reads them: node and port, without the wire's steering dots. */
    private static List<LinkIn> links(final List<Drawer.Link> links) {
        final List<LinkIn> out = new ArrayList<>(links.size());
        for (final Drawer.Link l : links) out.add(new LinkIn(CoreIds.core(l.nodeId()), l.portIndex()));
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
            CoreIds.core(node.id),
            node.machineName == null ? "" : node.machineName,
            power ? 20 : effect.duration(),
            power ? 0 : effect.energy(),
            power ? 1 : effect.throughputFactor(),
            inputs,
            outputs,
            exact,
            cfg.getMachineCount(),
            targets,
            properties,
            inputTargets);
    }

    /**
     * Numbers the ingredients of one snapshot: a port takes the number of the first ingredient it connects to, else a
     * new one. Each ingredient is filed under every key a port connecting to it must share (an item's id and damage
     * and each of its ore dictionary ids; a fluid; any other kind as a whole), so a port is only tested against the
     * ingredients filed under its own keys, in order: the same numbers as testing every ingredient, without the plan
     * squared.
     */
    private static final class Ingredients {

        private final List<Port<?>> representatives = new ArrayList<>();
        private final Map<Object, List<Integer>> filed = new HashMap<>();

        PortIn portOf(final Port<?> port, final double multiplier) {
            final String name = port.getDisplayName();
            return new PortIn(
                port.amount(),
                port.getChance(),
                multiplier,
                resourceOf(port),
                name == null ? "" : name,
                name != null && CoreSettings.get()
                    .isFreeIngredient(name));
        }

        private int resourceOf(final Port<?> port) {
            final List<Object> keys = keysOf(port);
            int found = Integer.MAX_VALUE;
            for (final Object key : keys) {
                final List<Integer> candidates = filed.get(key);
                if (candidates == null) continue;
                for (final int i : candidates) {
                    if (i >= found) break;
                    if (representatives.get(i)
                        .canConnect(port)) {
                        found = i;
                        break;
                    }
                }
            }
            if (found != Integer.MAX_VALUE) return found;
            final int index = representatives.size();
            representatives.add(port);
            for (final Object key : keys) filed.computeIfAbsent(key, k -> new ArrayList<>())
                .add(index);
            return index;
        }

        /**
         * Every key a port that connects to this one shares with it: {@code RecipePropertyAPI}'s item rule (same item and
         * damage, or a shared ore dictionary id) and fluid rule (same fluid), recognised by their keys so that nothing
         * here loads the fluid registry; any other kind is filed whole.
         */
        private static List<Object> keysOf(final Port<?> port) {
            final String kind = port.getType()
                .getKey();
            if ("item".equals(kind) && port.getValue() instanceof final ItemStack stack) {
                if (stack.getItem() == null) return List.of(); // connects to nothing
                final List<Object> keys = new ArrayList<>();
                keys.add(List.of(stack.getItem(), stack.getItemDamage()));
                for (final int ore : OreDictionary.getOreIDs(stack)) keys.add(ore);
                return keys;
            }
            if ("fluid".equals(kind) && port.getValue() instanceof final FluidStack fluid) {
                return Collections.singletonList(fluid.getFluid());
            }
            return List.of(port.getType());
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
        final Map<UUID, Double> extraExtentPins) {
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
        @Nullable final ChoiceKey choice, final Map<UUID, Double> extraExtentPins) {
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
