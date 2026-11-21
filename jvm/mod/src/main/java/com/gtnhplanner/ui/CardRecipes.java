package com.gtnhplanner.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Group;
import com.gtnhplanner.data.flowchart.MachineGroup;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.PinRule;
import com.gtnhplanner.data.flowchart.Port;

/**
 * A card's two recipe rows on the board model, the website's: "Add another recipe" puts a recipe on the card as one
 * more section of its shared machine ({@code addRecipeToNode}), "Replace the recipe" puts a recipe in the card's place
 * ({@code refactorNodeWithRecipe}). Which machine runs them all is the caller's to say (NEI's catalysts); these only
 * change the plan. A port's resource is read by {@code key} (the board's {@link Resources#key}).
 */
public final class CardRecipes {

    /** The settings that belong to the recipe rather than the machine: a shared card keeps the others the same. */
    static final Set<String> RECIPE_SETTINGS = Set.of("duration_ticks", "recipe_heat", "machines");

    private CardRecipes() {}

    /** The shared machine a recipe is a section of, or null when its card is its own. */
    @Nullable
    public static MachineGroup machineOf(final Graph graph, final Uuid nodeId) {
        for (final Group group : graph.getGroups()) if (group instanceof final MachineGroup g && g.getSections()
            .contains(nodeId)) return g;
        return null;
    }

    /**
     * Puts {@code section} on {@code host}'s card as its last recipe: it joins the host's shared machine, or the two
     * become one (the host's set count becomes the machine's, as Combine does). The section sits where the card does
     * and takes its machine and machine settings, keeping its own recipe's; {@code machine} (an item key), when given,
     * is the machine the card switches to because its own does not run the newcomer. Returns the shared machine.
     */
    public static MachineGroup join(final Graph graph, final Node host, final Node section,
        @Nullable final String machine) {
        if (graph.nodes.get(section.id) != section) graph.addNode(section);
        MachineGroup g = machineOf(graph, host.id);
        if (g == null) {
            final double capacity = host.isMachineCountFixed() ? host.machineConfig.machineCount() : 0;
            final boolean pinned = host.isMachineCountFixed(), exact = host.isMachineCountExact();
            g = new MachineGroup();
            g.setHeader("Shared machine");
            g.addSection(host.id);
            g.setMachineCount(capacity);
            g.setPinned(pinned && capacity > 0);
            g.setExact(exact && g.isPinned());
            graph.groups.put(g.getId(), g);
            host.setMachineCountFixed(false);
        }
        g.addSection(section.id);
        if (machine != null) host.machineName = machine;
        section.setMachineCountFixed(false);
        section.x = host.x;
        section.y = host.y;
        copyMachineSettings(graph, host, g.getSections());
        return g;
    }

    /** Gives every section the host's machine and its machine settings; each keeps its own recipe's. */
    public static void copyMachineSettings(final Graph graph, final Node host, final List<Uuid> sections) {
        for (final Uuid s : sections) {
            final Node n = graph.nodes.get(s);
            if (n == null || n == host) continue;
            n.machineName = host.machineName;
            for (final Map.Entry<String, Object> e : host.machineConfig.settings.entrySet()) {
                if (!RECIPE_SETTINGS.contains(e.getKey())) n.machineConfig.settings.put(e.getKey(), e.getValue());
            }
        }
    }

    /**
     * Whether {@code fresh} can take {@code old}'s place: the old card has no wires, or one of them still has its
     * resource on the new recipe. When every wire would be lost the website keeps the old card and lands the pick
     * beside it.
     */
    public static boolean fits(final Graph graph, final Node old, final Node fresh,
        final Function<Port<?>, String> key) {
        final List<String> ins = keys(old.inputs, key), outs = keys(old.outputs, key);
        final List<String> nowIns = keys(fresh.inputs, key), nowOuts = keys(fresh.outputs, key);
        boolean touched = false;
        for (final Edge e : graph.getEdges()) {
            if (e.sourceNodeId.equals(old.id)) {
                touched = true;
                if (moved(outs, nowOuts, e.sourceOutputIndex) >= 0) return true;
            }
            if (e.targetNodeId.equals(old.id)) {
                touched = true;
                if (moved(ins, nowIns, e.targetInputIndex) >= 0) return true;
            }
        }
        return !touched;
    }

    /**
     * Puts {@code fresh} (a card as NEI's + makes it: its recipe's machine, tier and settings) in {@code old}'s place:
     * its id, place, group, shared machine or pool, places in the world and count rule are the old card's, and each
     * wire and drawer link follows its resource to where the new recipe has it, gone where it has not.
     */
    public static void replace(final Graph graph, final Node old, final Node fresh,
        final Function<Port<?>, String> key) {
        final List<String> ins = keys(old.inputs, key), outs = keys(old.outputs, key);
        fresh.id = old.id;
        fresh.x = old.x;
        fresh.y = old.y;
        fresh.group = old.group;
        fresh.worldLinks.clear();
        fresh.worldLinks.addAll(old.worldLinks);
        final PinRule rule = PinRule.of(old);
        PinRule.set(fresh, rule, rule == PinRule.ANY ? 0 : old.machineConfig.machineCount());
        graph.addNode(fresh);
        followPorts(graph, fresh, ins, outs, key);
    }

    /**
     * After a card's ports were read again ({@code ins} and {@code outs} their keys before): each wire and drawer link
     * on a port follows its resource to where it now is, and goes when the card no longer has it.
     */
    public static void followPorts(final Graph graph, final Node node, final List<String> ins, final List<String> outs,
        final Function<Port<?>, String> key) {
        final List<String> nowIns = keys(node.inputs, key), nowOuts = keys(node.outputs, key);
        for (final Edge e : new ArrayList<>(graph.getEdges())) {
            if (e.sourceNodeId.equals(node.id)) {
                final int at = moved(outs, nowOuts, e.sourceOutputIndex);
                if (at < 0) graph.removeEdge(e.id);
                else e.sourceOutputIndex = at;
            }
            if (e.targetNodeId.equals(node.id)) {
                final int at = moved(ins, nowIns, e.targetInputIndex);
                if (at < 0) graph.removeEdge(e.id);
                else e.targetInputIndex = at;
            }
        }
        for (final Drawer d : graph.getDrawers()) {
            for (final Drawer.Held held : d.held()) {
                final Drawer.Link link = held.link();
                final boolean input = held.input();
                if (!link.nodeId()
                    .equals(node.id)) continue;
                final int at = input ? moved(ins, nowIns, link.portIndex()) : moved(outs, nowOuts, link.portIndex());
                if (at == link.portIndex()) continue;
                graph.unlinkDrawerPort(d.getId(), link, input);
                if (at >= 0) graph.linkDrawerPort(d.getId(), new Drawer.Link(node.id, at), input);
            }
        }
    }

    /** Each port's resource key, in order. */
    static List<String> keys(final List<Port<?>> ports, final Function<Port<?>, String> key) {
        final List<String> keys = new ArrayList<>(ports.size());
        for (final Port<?> p : ports) keys.add(key.apply(p));
        return keys;
    }

    /** Where the port that was at {@code index} is now, by its resource; -1 when it is gone. */
    static int moved(final List<String> was, final List<String> now, final int index) {
        return index >= 0 && index < was.size() ? now.indexOf(was.get(index)) : -1;
    }
}
