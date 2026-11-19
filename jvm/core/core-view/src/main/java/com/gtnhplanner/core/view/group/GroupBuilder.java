package com.gtnhplanner.core.view.group;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.gtnhplanner.core.view.Json;

/** The group summary: machines (a shared one once), EU/t, and the net flows in and out, busiest first. */
public final class GroupBuilder {

    /** A net flow at or under this reads as nothing. */
    private static final double EPS = 1e-6;

    private GroupBuilder() {}

    public static GroupView build(final GroupInput in) {
        int machines = 0;
        double euT = 0;
        final Set<String> hosts = new HashSet<>();
        final Map<String, double[]> net = new LinkedHashMap<>();
        final Map<String, String> names = new LinkedHashMap<>();
        for (final GroupInput.Member m : in.members()) {
            if (m.builds() != null && hosts.add(m.host())) machines += (int) Math.ceil(m.builds() - 1e-9);
            euT += m.euT();
            for (final GroupInput.Flow f : m.flows()) {
                net.computeIfAbsent(f.key(), k -> new double[1])[0] += f.perSecond();
                names.putIfAbsent(f.key(), f.name());
            }
        }
        final List<GroupView.Flow> inputs = new ArrayList<>(), outputs = new ArrayList<>();
        for (final Map.Entry<String, double[]> e : net.entrySet()) {
            final double v = e.getValue()[0];
            if (Math.abs(v) <= EPS) continue;
            (v > 0 ? inputs : outputs).add(new GroupView.Flow(e.getKey(), names.get(e.getKey()), Math.abs(v)));
        }
        // Busiest first; a tie keeps the order the members met it in (the sort is stable).
        inputs.sort((a, b) -> Double.compare(b.perSecond(), a.perSecond()));
        outputs.sort((a, b) -> Double.compare(b.perSecond(), a.perSecond()));
        return new GroupView(machines, euT, List.copyOf(inputs), List.copyOf(outputs),
            Math.max(1, Math.max(inputs.size(), outputs.size())));
    }

    public static String toJson(final GroupView v) {
        final Json j = new Json();
        j.begin()
            .num("machines", v.machines())
            .num("euT", v.euT());
        j.list("inputs", v.inputs(), (jj, f) -> flow(jj, f));
        j.list("outputs", v.outputs(), (jj, f) -> flow(jj, f));
        j.num("rows", v.rows())
            .end();
        return j.toString();
    }

    private static void flow(final Json j, final GroupView.Flow f) {
        j.begin()
            .str("key", f.key())
            .str("name", f.name())
            .num("perSecond", f.perSecond())
            .end();
    }
}
