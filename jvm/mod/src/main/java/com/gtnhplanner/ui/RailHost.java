package com.gtnhplanner.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.gtnhplanner.core.view.rail.RailInput;
import com.gtnhplanner.core.view.rail.RailMath;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.PinRule;
import com.gtnhplanner.data.flowchart.balancer.PortRef;
import com.gtnhplanner.data.flowchart.balancer.UpToReadout;
import com.gtnhplanner.ui.card.CardDefaults;
import com.gtnhplanner.ui.card.CardModel;
import com.gtnhplanner.ui.drawer.DrawerModel;

/**
 * The mod's side of the overview rail: what the board knows (cards, drawers, spares, the player's switches) as the
 * core's {@link RailInput}. The core ({@code RailBuilder}) works out the lines, the machine list and every figure; this
 * only reads the board into records and keeps what is the mod's own (which card is which machine, the figures a card's
 * power gives at its peak and on average).
 */
final class RailHost {

    private RailHost() {}

    /**
     * A machine line before the core has had it: its recipes, the figures the host owns and the record for the core.
     */
    record Machine(List<CardModel> recipes, CardModel card, PinRule rule, double set, double built, double runs,
        @Nullable UpToReadout readout, boolean tank, boolean tooLow, double made, double euPeak, double euAverage,
        double steamPeak, double steamAverage, RailInput.MachineIn in) {}

    /**
     * The machine lines of the cards: every card once, at zero too, whatever its rule. A shared machine is one line:
     * its recipes together, at its host's place in the plan. A fluid tank or a crop farm is no machine to build, so it
     * is a line the core counts for power and leaves out of the list. Sorted as the website's list is: lowest tier
     * first (no tier last), the biggest draw, then the name.
     */
    static List<Machine> machines(final BoardSession session, final List<CardModel> cards) {
        final Map<Object, List<CardModel>> shared = new HashMap<>();
        final List<List<CardModel>> grouped = new ArrayList<>();
        for (final CardModel c : cards) {
            final UUID host = session.sharedOf(c.node.id) == null ? null : session.hostOf(c.node.id);
            final boolean tank = isTank(c);
            if (host == null || tank) {
                grouped.add(new ArrayList<>(List.of(c)));
                continue;
            }
            final List<CardModel> recipes = shared.get(host);
            if (recipes != null) recipes.add(c);
            else {
                final List<CardModel> first = new ArrayList<>();
                first.add(c);
                shared.put(host, first);
                grouped.add(first);
            }
        }
        final List<Machine> out = new ArrayList<>(grouped.size());
        for (final List<CardModel> recipes : grouped) out.add(machine(session, recipes));
        final boolean peak = session.peakPower();
        out.sort(Comparator.<Machine>comparingInt(m -> {
            final int tier = CardDefaults.tierIndex(m.card().tier);
            return tier < 0 ? Integer.MAX_VALUE : tier;
        })
            .thenComparing(m -> -(peak ? m.euPeak() : m.euAverage()))
            .thenComparing(m -> m.card().machineName));
        return out;
    }

    static boolean isTank(final CardModel c) {
        return com.gtnhplanner.power.FluidTank.is(c.node) || com.gtnhplanner.power.CropFarm.is(c.node);
    }

    /** The card a machine line stands at: its shared machine's host, else its first recipe. */
    private static CardModel hostCard(final BoardSession session, final List<CardModel> recipes) {
        return recipes.stream()
            .filter(
                c -> session.hostOf(c.node.id)
                    .equals(c.node.id))
            .findFirst()
            .orElse(recipes.get(0));
    }

    /** What a machine line draws on average, EU/t: each recipe at the machines it runs. */
    static double average(final List<CardModel> recipes) {
        if (com.gtnhplanner.machines.game.CropCards.isCrop(recipes.get(0).node)) {
            double crops = 0;
            for (final CardModel c : recipes) crops += c.machines;
            return com.gtnhplanner.machines.game.CropCards.drawEut(recipes.get(0).node, crops);
        }
        double sum = 0;
        for (final CardModel c : recipes) sum += c.euPerTick * c.machines;
        return sum;
    }

    /**
     * What a machine line draws at its peak, EU/t: the hungriest recipe on every machine the line is billed. A line is
     * billed the machines it has set, or what the solve needs when that is more; with none set, whole machines (a
     * shared machine's pool as one). A crop line draws as whole harvesters whatever the switch says.
     */
    static double peak(final BoardSession session, final List<CardModel> recipes) {
        if (com.gtnhplanner.machines.game.CropCards.isCrop(recipes.get(0).node)) return average(recipes);
        final CardModel card = hostCard(session, recipes);
        double built = 0, most = 0;
        for (final CardModel c : recipes) {
            built += c.machines;
            most = Math.max(most, c.euPerTick);
        }
        return most * billed(session, card, built);
    }

    private static double billed(final BoardSession session, final CardModel card, final double built) {
        final double set = session.rule(card.node) == PinRule.ANY ? 0 : session.setCount(card.node);
        return RailMath.peakCount(built, set > 0 ? set : null, false);
    }

    /**
     * The steam a machine line burns, L/s, from each recipe's steam per machine a tick and the machines it runs:
     * {peak, average}. Peak is the most any recipe burns on every machine the line is billed ({@code billed}); average
     * what they burn at the machines that run. Null when no recipe burns steam.
     */
    static double[] steam(final double[] perTick, final double[] machines, final double billed) {
        double most = 0, average = 0;
        boolean any = false;
        for (int i = 0; i < perTick.length; i++) {
            if (!(perTick[i] > 0)) continue;
            any = true;
            most = Math.max(most, perTick[i]);
            average += perTick[i] * 20 * machines[i];
        }
        return any ? new double[] { most * 20 * billed, average } : null;
    }

    private static Machine machine(final BoardSession session, final List<CardModel> recipes) {
        final CardModel card = hostCard(session, recipes);
        final PinRule rule = session.rule(card.node);
        final double set = rule == PinRule.ANY ? 0 : session.setCount(card.node);
        double built = 0, made = 0;
        boolean tooLow = false;
        final double[] perTick = new double[recipes.size()], machines = new double[recipes.size()];
        int at = 0;
        for (final CardModel c : recipes) {
            built += c.machines;
            made += c.madeEuPerTick();
            tooLow |= c.tierTooLow();
            perTick[at] = c.steam() ? c.steamPerTick : 0;
            machines[at++] = c.machines;
        }
        final double average = average(recipes);
        final double peak = peak(session, recipes);
        final double billed = billed(session, card, built);
        final UpToReadout readout = rule == PinRule.UP_TO ? session.upTo(card.node.id) : null;
        final double runs = readout != null ? Math.max(0, readout.reached()) : built;
        final double[] burn = steam(perTick, machines, billed);
        final boolean steam = burn != null;
        final double steamPeak = steam ? burn[0] : Double.NaN;
        final double steamAverage = steam ? burn[1] : Double.NaN;
        final boolean tank = isTank(card);
        final boolean hasTier = card.gregtech && CardDefaults.tierIndex(card.tier) >= 0;
        final String tier = hasTier ? card.tier : null;
        final RailInput.MachineIn in = new RailInput.MachineIn(
            card.node.id.toString(),
            card.machineName,
            com.gtnhplanner.machines.game.CropCards.builds(card.node, runs),
            tank,
            rule != PinRule.ANY,
            rule == PinRule.UP_TO ? "upTo" : rule == PinRule.EXACT ? "exact" : "any",
            rule == PinRule.ANY ? null : set,
            readout == null ? null
                : new RailInput.UpToIn(
                    readout.reason() == null ? null
                        : readout.reason()
                            .name(),
                    Math.max(0, readout.reached()),
                    readout.capacity()),
            tier,
            card.amps > 0 ? card.amps : null,
            card.multiblock,
            made > 0 ? tier : null,
            tooLow ? "under-powered" : "ok",
            peak,
            average,
            steam ? steamPeak : null,
            steam ? steamAverage : null,
            made > 0 ? made : null,
            made > 0 ? made : null);
        return new Machine(
            List.copyOf(recipes),
            card,
            rule,
            set,
            built,
            runs,
            readout,
            tank,
            tooLow,
            made,
            peak,
            average,
            steamPeak,
            steamAverage,
            in);
    }

    /**
     * The rail's input: every card's solved flows (a custom rate card is the plan's edge, as a drawer is, so it has
     * none
     * here), the drawers, the machine lines, the spares with a choice of place, and the player's view. The ports
     * already
     * carry the solved rate, so no card is held back for its machine count.
     */
    static RailInput input(final BoardSession session, final Map<UUID, CardModel> models, final List<CardModel> counted,
        final List<Machine> machines, final Map<String, String> aliases, final String filter) {
        final Graph graph = session.graph();
        final Set<String> held = new HashSet<>();
        for (final Drawer d : graph.getDrawers()) for (final Drawer.Held h : d.held()) held.add(
            portKey(
                h.link()
                    .nodeId(),
                h.input(),
                h.link()
                    .portIndex()));
        final List<RailInput.Flow> flows = new ArrayList<>(counted.size());
        for (final CardModel card : counted) {
            final List<RailInput.Port> ports = new ArrayList<>();
            for (final CardModel.PortView p : card.outputs) ports.add(port(card, p, false, held));
            for (final CardModel.PortView p : card.inputs) ports.add(port(card, p, true, held));
            flows.add(new RailInput.Flow(card.node.id.toString(), card.machineName, 1.0, null, List.copyOf(ports)));
        }
        final List<RailInput.DrawerIn> drawers = new ArrayList<>();
        for (final DrawerModel d : session.drawerModels()
            .values()) {
            final String role = switch (d.kind) {
                case SOURCE -> "source";
                case PRODUCT -> "product";
                case BYPRODUCT -> "byproduct";
                case TRASH -> "trash";
                default -> null;
            };
            if (role == null) continue;
            final String key = d.drawer.getResourceKey();
            final int colon = key.indexOf(':');
            drawers.add(
                new RailInput.DrawerIn(
                    d.drawer.getId()
                        .toString(),
                    colon < 0 ? key : key.substring(0, colon),
                    colon < 0 ? "" : key.substring(colon + 1),
                    d.label,
                    role,
                    switch (d.rule) {
                    case AT_LEAST -> "at-least";
                    case EXACTLY -> "exact";
                    case AT_MOST -> "at-most";
                    default -> "ignore";
                    },
                    d.rule != Drawer.Rule.ANY || d.target > 0,
                    Math.abs(d.target),
                    d.unmet,
                    Math.max(0, d.rate)));
        }
        final List<RailInput.MachineIn> lines = new ArrayList<>(machines.size());
        for (final Machine m : machines) lines.add(m.in());
        final List<RailInput.SpareIn> spares = new ArrayList<>();
        for (final BoardSession.SpareLine line : session.spares()) {
            final List<RailInput.BearerIn> bearers = new ArrayList<>();
            for (int i = 0; i < line.bearers()
                .size(); i++) {
                final BoardSession.SpareBearer b = line.bearers()
                    .get(i);
                final List<RailInput.PortRef> ports = new ArrayList<>();
                for (final PortRef r : b.ports()) ports.add(
                    new RailInput.PortRef(
                        r.nodeId()
                            .toString(),
                        handle(r.input(), r.portIndex())));
                bearers.add(
                    new RailInput.BearerIn(
                        b.key(),
                        b.node()
                            .toString(),
                        handle(!b.output(), b.port()),
                        b.output(),
                        b.name(),
                        b.machine(),
                        b.resourceKey(),
                        b.max(),
                        b.solverPick(),
                        Resources.isPower(b.resourceKey()),
                        line.solvedShares()[i],
                        ports));
            }
            spares.add(
                new RailInput.SpareIn(
                    line.key(),
                    line.importing(),
                    session.spareCustom(line),
                    graph.spareShares.get(line.key()),
                    bearers));
        }
        final List<String> cards = new ArrayList<>(
            graph.getNodes()
                .size());
        for (final Node n : graph.getNodes()) cards.add(n.id.toString());
        final List<RailInput.Wire> wires = new ArrayList<>();
        for (final Edge e : graph.getEdges())
            wires.add(new RailInput.Wire(e.sourceNodeId.toString(), e.targetNodeId.toString()));
        return new RailInput(
            true,
            aliases,
            flows,
            drawers,
            lines,
            Map.of(),
            spares,
            cards,
            wires,
            view(session, filter));
    }

    /**
     * The player's view: the filter, peak or average power and the dial. The mod sets a rate on an unwired port from
     * its line, so a line with an open port stays listed at zero ({@code keepOpen}).
     */
    static RailInput.ViewIn view(final BoardSession session, final String filter) {
        return new RailInput.ViewIn(
            filter,
            session.peakPower(),
            session.powerKey() == BoardSession.PowerKey.AMPS ? "amps" : "eu",
            true);
    }

    private static RailInput.Port port(final CardModel card, final CardModel.PortView p, final boolean input,
        final Set<String> held) {
        final String key = p.key();
        final int colon = key.indexOf(':');
        final Map<Integer, Double> rates = input ? card.node.targetInputRates : card.node.targetOutputRates;
        final Double set = rates.get(p.index());
        return new RailInput.Port(
            !input,
            key,
            colon < 0 ? key : key.substring(0, colon),
            colon < 0 ? "" : key.substring(colon + 1),
            p.name(),
            p.perSecond(),
            handle(input, p.index()),
            p.wired() || held.contains(portKey(card.node.id, input, p.index())),
            set == null ? 0 : Math.max(0, set));
    }

    /** A port as the core names it: its side and place on its card. */
    static String handle(final boolean input, final int index) {
        return (input ? "i" : "o") + index;
    }

    private static String portKey(final UUID node, final boolean input, final int index) {
        return node + (input ? "<" : ">") + index;
    }
}
