package com.gtnhplanner.core.view.rail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * The overview rail worked out from a {@link RailInput}: what the plan brings in, sends out and keeps inside, what sets
 * each resource, the machines to build and their power, then the view of it under the player's filter and dial. The
 * website's {@code rail-model.ts}, {@code spare-model.ts} and {@code rail-layout.ts} (without the pixels) and the
 * mod's {@code BoardSession.buildTotals}, one logic.
 */
public final class RailBuilder {

    /** Below this a rate (per second) is solver noise, not a flow worth a line. */
    public static final double EPS = 1e-6;

    public static final String EMPTY_INPUTS = "gtnhplanner.rail.empty.inputs";
    public static final String EMPTY_OUTPUTS = "gtnhplanner.rail.empty.outputs";
    public static final String EMPTY_INTERNAL = "gtnhplanner.rail.empty.internal";
    public static final String EMPTY_MACHINES = "gtnhplanner.rail.empty.machines";
    public static final String EMPTY_MATCHES = "gtnhplanner.rail.empty.matches";

    private RailBuilder() {}

    /** A line's resource key joined to the one a wire makes it: the key a port asks for, followed up to 16 hops. */
    public static String canonicalOf(final Map<String, String> aliases, final String key) {
        String at = key;
        for (int i = 0; i < 16; i++) {
            final String next = aliases.get(at);
            if (next == null) break;
            at = next;
        }
        return at;
    }

    /** The lines and machines before the filter, the peak choice and the dial. */
    public record Model(List<RailView.Line> inputs, List<RailView.Line> outputs, List<RailView.Line> internal,
        List<RailInput.MachineIn> machines, int wholeMachines, Power peak, Power average, boolean hasMade) {}

    /** The plan's power draw: EU/t made and used, steam L/s. */
    public record Power(double used, double made, double steam) {}

    private record Look(String kind, String id, String label) {}

    private static <T> void addTo(final Map<String, List<T>> map, final String key, final T value) {
        map.computeIfAbsent(key, k -> new ArrayList<>())
            .add(value);
    }

    // region Model

    public static Model model(final RailInput in) {
        final Map<String, Double> made = new LinkedHashMap<>(), used = new LinkedHashMap<>();
        final Map<String, Look> looks = new HashMap<>();
        final Map<String, List<RailView.Pin>> pinsByLine = new LinkedHashMap<>();
        final Map<String, List<RailView.Open>> openByLine = new LinkedHashMap<>();
        for (final RailInput.Flow flow : in.flows()) {
            final boolean flowing = in.answered() && (flow.machines() == null ? 0 : flow.machines()) > 1e-9;
            final double running = flowing
                ? Math.min(1, Math.max(0, flow.utilization() == null ? 1 : flow.utilization()))
                : 0;
            for (final RailInput.Port port : flow.ports()) {
                final String key = canonicalOf(in.aliases(), port.key());
                final Map<String, Double> bucket = port.output() ? made : used;
                bucket.merge(key, port.perSecond() * running, Double::sum);
                if (!looks.containsKey(key) || (port.output() && port.key().equals(key))) {
                    looks.put(key, new Look(port.kind(), port.id(), port.label()));
                }
                if (port.wired()) continue;
                final String lineKey = (port.output() ? "o|" : "i|") + key;
                if (port.set() > 0) {
                    addTo(
                        pinsByLine,
                        lineKey,
                        new RailView.Pin(flow.card(), port.handle(), port.output(), port.set(), flow.machine()));
                }
                addTo(
                    openByLine,
                    lineKey,
                    new RailView.Open(
                        flow.card(),
                        port.handle(),
                        port.output(),
                        port.perSecond() * running,
                        flow.machine()));
            }
        }

        // What trash voids leaves the plan without being one of its outputs.
        final Map<String, Double> trashed = new HashMap<>();
        final Map<String, List<RailView.Drawer>> sourceDrawers = new LinkedHashMap<>(),
            productDrawers = new LinkedHashMap<>();
        for (final RailInput.DrawerIn d : in.drawers()) {
            final String key = canonicalOf(in.aliases(), d.kind() + ":" + d.resourceId());
            if (d.role().equals("trash")) {
                trashed.merge(key, Math.max(0, d.eaten()), Double::sum);
                continue;
            }
            final boolean source = d.role().equals("source");
            if (!source && !d.role().equals("product") && !d.role().equals("byproduct")) continue;
            addTo(
                source ? sourceDrawers : productDrawers,
                key,
                new RailView.Drawer(d.id(), source, d.role(), d.rule(), d.rate(), d.set(), d.unmet()));
            if (!looks.containsKey(key)) looks.put(key, new Look(d.kind(), d.resourceId(), d.label()));
        }

        final Set<String> keys = new LinkedHashSet<>(made.keySet());
        keys.addAll(used.keySet());
        final List<RailView.Line> inputs = new ArrayList<>(), outputs = new ArrayList<>(),
            internal = new ArrayList<>();
        for (final String key : keys) {
            final double m = made.getOrDefault(key, 0.0), u = used.getOrDefault(key, 0.0);
            final double leaves = m - u - trashed.getOrDefault(key, 0.0);
            if (u - m > EPS) inputs.add(line(in, looks, sourceDrawers, productDrawers, pinsByLine, openByLine, key, u - m, 'i'));
            if (leaves > EPS) outputs.add(line(in, looks, sourceDrawers, productDrawers, pinsByLine, openByLine, key, leaves, 'o'));
            if (Math.min(m, u) > EPS) {
                internal.add(line(in, looks, sourceDrawers, productDrawers, pinsByLine, openByLine, key, Math.min(m, u), 'x'));
            }
        }
        // Biggest first; equal amounts keep the order the plan met them in (the sort is stable).
        final java.util.Comparator<RailView.Line> byAmount = (a, b) -> Double.compare(b.amount(), a.amount());
        inputs.sort(byAmount);
        outputs.sort(byAmount);
        internal.sort(byAmount);
        // A resource a card's unwired port takes or makes stays listed at 0, so it can be set from here.
        for (final String lineKey : openByLine.keySet()) {
            final String key = lineKey.substring(2);
            final boolean out = lineKey.startsWith("o|");
            final List<RailView.Line> list = out ? outputs : inputs;
            if (!has(list, key)) {
                list.add(line(in, looks, sourceDrawers, productDrawers, pinsByLine, openByLine, key, 0, out ? 'o' : 'i'));
            }
        }
        // A resource with a drawer stays listed at 0, so its rule and rate can be set from here.
        for (final String key : sourceDrawers.keySet()) {
            if (!has(inputs, key)) inputs.add(line(in, looks, sourceDrawers, productDrawers, pinsByLine, openByLine, key, 0, 'i'));
        }
        for (final String key : productDrawers.keySet()) {
            if (!has(outputs, key)) outputs.add(line(in, looks, sourceDrawers, productDrawers, pinsByLine, openByLine, key, 0, 'o'));
        }

        double peakUsed = 0, peakMade = 0, peakSteam = 0, avgUsed = 0, avgMade = 0, avgSteam = 0;
        boolean hasMade = false;
        int whole = 0;
        final List<RailInput.MachineIn> machines = new ArrayList<>();
        for (final RailInput.MachineIn e : in.machines()) {
            peakUsed += or0(e.euT());
            peakMade += or0(e.madeEuT());
            peakSteam += or0(e.steamLs());
            avgUsed += or0(e.avgEuT());
            avgMade += or0(e.avgMadeEuT());
            avgSteam += or0(e.avgSteamLs());
            if (e.madeEuT() != null) hasMade = true;
            if (e.tank()) continue;
            machines.add(e);
            whole += (int) Math.ceil(e.count() - 1e-9);
        }
        return new Model(inputs, outputs, internal, machines, whole, new Power(peakUsed, peakMade, peakSteam),
            new Power(avgUsed, avgMade, avgSteam), hasMade);
    }

    private static double or0(@Nullable final Double d) {
        return d == null ? 0 : d;
    }

    private static boolean has(final List<RailView.Line> list, final String key) {
        for (final RailView.Line l : list) if (l.key().equals(key)) return true;
        return false;
    }

    private static RailView.Line line(final RailInput in, final Map<String, Look> looks,
        final Map<String, List<RailView.Drawer>> sourceDrawers, final Map<String, List<RailView.Drawer>> productDrawers,
        final Map<String, List<RailView.Pin>> pinsByLine, final Map<String, List<RailView.Open>> openByLine,
        final String key, final double amount, final char side) {
        Look look = looks.get(key);
        if (look == null) {
            final int colon = key.indexOf(':');
            look = new Look(colon < 0 ? key : key.substring(0, colon), colon < 0 ? "" : key.substring(colon + 1), key);
        }
        final String named = in.names().get(look.kind() + ":" + look.id());
        final String lineKey = side + "|" + key;
        final List<RailView.Drawer> drawers = side == 'i' ? sourceDrawers.get(key)
            : side == 'o' ? productDrawers.get(key) : null;
        final List<RailView.Pin> pins = side == 'x' ? null : pinsByLine.get(lineKey);
        final List<RailView.Open> open = side == 'x' ? null : openByLine.get(lineKey);
        return new RailView.Line(key, look.kind(), look.id(), named != null ? named : look.label(), amount,
            look.kind().equals("power"), drawers == null ? List.of() : drawers, pins == null ? List.of() : pins,
            open == null ? List.of() : open);
    }

    // endregion

    // region Spares

    /** How far each card is from where the plan starts along its wires (the nearest way, so a loop does not run on). */
    public static Map<String, Integer> cardDepths(final List<String> cards, final List<RailInput.Wire> wires) {
        final Set<String> known = new LinkedHashSet<>(cards);
        final Map<String, List<String>> feeds = new HashMap<>();
        final Set<String> fed = new LinkedHashSet<>();
        for (final RailInput.Wire w : wires) {
            if (!known.contains(w.from()) || !known.contains(w.to())) continue;
            addTo(feeds, w.from(), w.to());
            fed.add(w.to());
        }
        final Map<String, Integer> depth = new HashMap<>();
        final List<String> queue = new ArrayList<>();
        for (final String id : cards) {
            if (!fed.contains(id)) {
                depth.put(id, 0);
                queue.add(id);
            }
        }
        for (int head = 0; head < queue.size(); head++) {
            final String at = queue.get(head);
            final List<String> to = feeds.get(at);
            if (to == null) continue;
            for (final String next : to) {
                if (!depth.containsKey(next)) {
                    depth.put(next, depth.get(at) + 1);
                    queue.add(next);
                }
            }
        }
        return depth;
    }

    /** The shares in force for a leftover: the stored split (normalised), else the solver's. */
    public static double[] effectiveShares(final List<String> bearerKeys, final double[] solved,
        @Nullable final Map<String, Double> stored) {
        if (stored == null) return solved.clone();
        final double[] raw = new double[bearerKeys.size()];
        double total = 0;
        for (int i = 0; i < raw.length; i++) {
            final Double v = stored.get(bearerKeys.get(i));
            raw[i] = Math.max(0, v == null ? 0 : v);
            total += raw[i];
        }
        if (!(total > 0)) return solved.clone();
        for (int i = 0; i < raw.length; i++) raw[i] = raw[i] / total;
        return raw;
    }

    /** The spares the rail shows: each one's places most upstream first, with the shares in force. */
    public static List<RailView.Spare> spares(final RailInput in) {
        final List<RailView.Spare> out = new ArrayList<>();
        if (in.spares().isEmpty()) return out;
        final Map<String, Integer> depth = cardDepths(in.cards(), in.wires());
        for (final RailInput.SpareIn s : in.spares()) {
            final List<RailInput.BearerIn> bearers = s.bearers();
            if (bearers.isEmpty()) continue;
            final Integer[] order = new Integer[bearers.size()];
            for (int i = 0; i < order.length; i++) order[i] = i;
            final double[] rank = new double[order.length];
            for (int i = 0; i < order.length; i++) {
                final RailInput.BearerIn b = bearers.get(i);
                final Integer d = depth.get(b.nodeId());
                rank[i] = (d == null ? 1e9 : d) + (b.output() ? 0.5 : -0.5);
            }
            // Most upstream first: a place leaving after a card sits just past it, one coming in just before it.
            java.util.Arrays.sort(order, (a, b) -> {
                final int c = Double.compare(rank[a], rank[b]);
                return c != 0 ? c : Integer.compare(a, b);
            });
            final List<RailView.Bearer> sorted = new ArrayList<>();
            final List<String> keys = new ArrayList<>();
            final double[] solved = new double[order.length];
            for (int k = 0; k < order.length; k++) {
                final RailInput.BearerIn b = bearers.get(order[k]);
                sorted.add(
                    new RailView.Bearer(b.key(), b.nodeId(), b.handle(), b.output(), b.name(), b.machine(),
                        canonicalOf(in.aliases(), b.resource()), b.max(), b.solverPick(), b.energy(), b.ports()));
                keys.add(b.key());
                solved[k] = b.share();
            }
            final double[] shares = effectiveShares(keys, solved, s.stored());
            out.add(
                new RailView.Spare(s.key(), s.importing(), s.custom() || s.stored() != null, sorted, list(solved),
                    list(shares)));
        }
        return out;
    }

    private static List<Double> list(final double[] values) {
        final List<Double> out = new ArrayList<>(values.length);
        for (final double v : values) out.add(v);
        return out;
    }

    /** How much of each resource the spares with a choice of place carry in ({@code importing}) or out. */
    public static Map<String, Double> sparedByResource(final List<RailView.Spare> spares, final boolean importing) {
        final Map<String, Double> out = new HashMap<>();
        for (final RailView.Spare s : spares) {
            if (s.importing() != importing || s.bearers().size() < 2) continue;
            for (int i = 0; i < s.bearers().size(); i++) {
                final RailView.Bearer b = s.bearers().get(i);
                out.merge(b.resourceKey(), s.shares().get(i) * b.max(), Double::sum);
            }
        }
        return out;
    }

    // endregion

    // region View

    private static boolean matches(final String filter, final String label) {
        final String f = filter.trim()
            .toLowerCase(Locale.ROOT);
        return f.isEmpty() || label.toLowerCase(Locale.ROOT)
            .contains(f);
    }

    /** The rail as the player sees it: every section, the machine list, and the dial. */
    public static RailView build(final RailInput in) {
        final Model model = model(in);
        final List<RailView.Spare> spares = spares(in);
        final RailInput.ViewIn view = in.view();
        final RailView.Dial dial = new RailView.Dial(view.power(), RailMath.dialSuffix(view.power(), null),
            RailMath.divisor(view.power(), null));
        final List<RailView.Section> sections = new ArrayList<>();
        sections.add(resources("INPUTS", model.inputs(), spares, true, view, EMPTY_INPUTS));
        sections.add(resources("OUTPUTS", model.outputs(), spares, false, view, EMPTY_OUTPUTS));
        sections.add(resources("INTERNAL", model.internal(), spares, false, view, EMPTY_INTERNAL));
        return new RailView(dial, sections, machines(model, view));
    }

    private static RailView.Section resources(final String id, final List<RailView.Line> lines,
        final List<RailView.Spare> spares, final boolean sources, final RailInput.ViewIn view, final String empty) {
        final String filter = view.filter();
        final boolean internal = id.equals("INTERNAL");
        final Map<String, Double> spared = internal ? Map.of() : sparedByResource(spares, sources);
        final List<RailView.Line> kept = new ArrayList<>();
        for (final RailView.Line line : lines) {
            if (internal) {
                kept.add(line);
                continue;
            }
            final Double sp = spared.get(line.key());
            final double left = Math.max(0, line.amount() - (sp == null ? 0 : sp));
            if (left <= Math.max(EPS, line.amount() * 1e-4) && line.drawers().isEmpty() && line.pins().isEmpty()
                && !(view.keepOpen() && !line.open().isEmpty())) continue;
            kept.add(withAmount(line, left));
        }
        final List<RailView.Line> shown = new ArrayList<>();
        for (final RailView.Line line : kept) if (matches(filter, line.label())) shown.add(line);
        final List<RailView.Spare> groups = new ArrayList<>();
        if (!internal) {
            for (final RailView.Spare s : spares) {
                if (s.importing() != sources || s.bearers().size() < 2) continue;
                boolean any = false;
                for (final RailView.Bearer b : s.bearers()) any |= matches(filter, b.name());
                if (any) groups.add(s);
            }
        }
        final String why = shown.isEmpty() && groups.isEmpty() ? (kept.isEmpty() ? empty : EMPTY_MATCHES) : null;
        final List<RailView.Line> out = new ArrayList<>();
        out.addAll(shown);
        return new RailView.Section(id, kept.size() + groups.size(), shown.size() + groups.size(), why, out, groups);
    }

    private static RailView.Line withAmount(final RailView.Line l, final double amount) {
        return new RailView.Line(l.key(), l.kind(), l.id(), l.label(), amount, l.energy(), l.drawers(), l.pins(),
            l.open());
    }

    private static RailView.Machines machines(final Model model, final RailInput.ViewIn view) {
        final Map<String, List<RailInput.MachineIn>> groupsOf = new LinkedHashMap<>();
        for (final RailInput.MachineIn e : model.machines()) {
            if (!matches(view.filter(), e.label())) continue;
            addTo(groupsOf, e.label(), e);
        }
        int shownWhole = 0;
        final List<RailView.Group> groups = new ArrayList<>();
        for (final Map.Entry<String, List<RailInput.MachineIn>> g : groupsOf.entrySet()) {
            final List<RailView.Entry> entries = new ArrayList<>();
            for (final RailInput.MachineIn e : g.getValue()) {
                shownWhole += (int) Math.ceil(e.count() - 1e-9);
                entries.add(entry(e, view));
            }
            groups.add(new RailView.Group(g.getKey(), entries));
        }
        final Power power = view.peak() ? model.peak() : model.average();
        final String unit = view.power();
        final String suffix = RailMath.dialSuffix(unit, null);
        final RailView.Fig total = new RailView.Fig(RailMath.dialValue(power.used(), unit, null), suffix);
        RailView.Fig made = null, net = null;
        if (model.hasMade()) {
            made = new RailView.Fig(RailMath.dialValue(power.made(), unit, null), suffix);
            net = new RailView.Fig(RailMath.dialValue(power.made() - power.used(), unit, null), suffix);
        }
        final String empty = groups.isEmpty() ? (model.machines().isEmpty() ? EMPTY_MACHINES : EMPTY_MATCHES) : null;
        return new RailView.Machines(model.wholeMachines(), shownWhole, total, made, net, empty, groups);
    }

    private static RailView.Entry entry(final RailInput.MachineIn e, final RailInput.ViewIn view) {
        final boolean peak = view.peak();
        final Double made = peak ? e.madeEuT() : e.avgMadeEuT();
        final Double used = peak ? e.euT() : e.avgEuT();
        final Double steam = peak ? e.steamLs() : e.avgSteamLs();
        final String unit = view.power();
        final RailView.Eu eu;
        if (e.state().equals("under-powered")) eu = new RailView.Eu("tooLow", null, null);
        else if (made != null && made > 0) {
            eu = new RailView.Eu("made", RailMath.dialValue(made, unit, e.madeTier()),
                RailMath.dialSuffix(unit, e.madeTier()));
        } else if (steam != null) eu = new RailView.Eu("steam", steam, "L/s");
        else if (used != null) {
            eu = new RailView.Eu("used", RailMath.dialValue(used, unit, e.tier()), RailMath.dialSuffix(unit, e.tier()));
        } else eu = new RailView.Eu("none", null, null);
        return new RailView.Entry(e.nodeId(), e.label(), e.count(), e.pinned(), e.rule(), e.pin(), e.upTo(), e.tier(),
            e.amps(), e.multiblock(), e.state(), eu);
    }

    // endregion
}
