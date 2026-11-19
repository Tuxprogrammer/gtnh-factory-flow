package com.gtnhplanner.core.solver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.Float32;
import com.gtnhplanner.core.base.JsonTree;
import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.flowchart.DrawerTypes;
import com.gtnhplanner.data.flowchart.balancer.Balancer;
import com.gtnhplanner.data.flowchart.balancer.BalanceMode;
import com.gtnhplanner.data.flowchart.balancer.ChoiceKey;
import com.gtnhplanner.data.flowchart.balancer.DrawerReadout;
import com.gtnhplanner.data.flowchart.balancer.External;
import com.gtnhplanner.data.flowchart.balancer.Note;
import com.gtnhplanner.data.flowchart.balancer.PortRef;
import com.gtnhplanner.data.flowchart.balancer.SolutionView;
import com.gtnhplanner.data.flowchart.balancer.SolveInput;
import com.gtnhplanner.data.flowchart.balancer.Spares;
import com.gtnhplanner.data.flowchart.balancer.UpToLoop;
import com.gtnhplanner.data.flowchart.balancer.UpToReadout;
import com.gtnhplanner.data.flowchart.balancer.alternatives.Alternative;
import com.gtnhplanner.data.flowchart.balancer.alternatives.Alternatives;

/**
 * The solver's exchange text: a solve input read from JSON and an answer written as the corpus's digest
 * ({@code conformance/schema/solve-digest}), both with {@link JsonTree} and no reflection, so the same code runs on the
 * JVM and in the web build. One codec: the web build's solver export answers with it, and the JVM tests read the
 * corpus and compare answers through it ({@code SolverCodecTest} holds it to the tests' reflective digest on every
 * case).
 *
 * <p>
 * The input is the file format of {@code conformance/solver/corpus/inputs} ({@code SolveInputJson.write}). The answer
 * has the shape the golden files have: records by their component names, maps with their keys sorted, sets sorted by
 * their text, enums by name, numbers as numbers (the comparison reads them with a tolerance). What the digest leaves out
 * is the wall time, and the Up to read-out, its rings and what a buffer banks when there are none.
 */
public final class SolverCodec {

    private SolverCodec() {}

    // ---- the input

    public static SolveInput readInput(final String json) {
        return readInput(JsonTree.object(JsonTree.parse(json)));
    }

    public static SolveInput readInput(final Map<String, Object> o) {
        final BalanceMode mode = BalanceMode.valueOf(JsonTree.str(o, "mode"));
        ChoiceKey choice = null;
        if (o.get("choice") instanceof List) {
            final List<PortRef> anchors = new ArrayList<>();
            for (final Object a : JsonTree.array(o.get("choice"))) anchors.add(portRef(JsonTree.object(a)));
            choice = new ChoiceKey(List.copyOf(anchors));
        }
        final List<SolveInput.Machine> machines = new ArrayList<>();
        for (final Object e : JsonTree.array(o.get("machines"))) {
            final Map<String, Object> j = JsonTree.object(e);
            machines.add(
                new SolveInput.Machine(
                    Uuid.fromString(JsonTree.str(j, "id")),
                    JsonTree.str(j, "name"),
                    JsonTree.num(j, "durationTicks"),
                    JsonTree.num(j, "energyPerTick"),
                    (int) JsonTree.num(j, "throughputFactor"),
                    ports(j.get("inputs")),
                    ports(j.get("outputs")),
                    Boolean.TRUE.equals(JsonTree.bool(j, "countFixed")),
                    JsonTree.num(j, "machineCount"),
                    rates(j.get("targetOutputRates")),
                    Map.of(),
                    rates(j.get("targetInputRates"))));
        }
        final List<SolveInput.EdgeIn> edges = new ArrayList<>();
        for (final Object e : JsonTree.array(o.get("edges"))) {
            final Map<String, Object> j = JsonTree.object(e);
            edges.add(
                new SolveInput.EdgeIn(
                    Uuid.fromString(JsonTree.str(j, "id")),
                    Uuid.fromString(JsonTree.str(j, "source")),
                    (int) JsonTree.num(j, "sourceOutput"),
                    Uuid.fromString(JsonTree.str(j, "target")),
                    (int) JsonTree.num(j, "targetInput")));
        }
        final List<SolveInput.PoolIn> pools = new ArrayList<>();
        for (final Object e : JsonTree.array(o.get("pools"))) {
            final Map<String, Object> j = JsonTree.object(e);
            final List<Uuid> members = new ArrayList<>();
            for (final Object m : JsonTree.array(j.get("machines"))) members.add(Uuid.fromString((String) m));
            pools.add(
                new SolveInput.PoolIn(
                    members,
                    JsonTree.num(j, "capacity"),
                    Boolean.TRUE.equals(JsonTree.bool(j, "exact")),
                    Boolean.TRUE.equals(JsonTree.bool(j, "upTo"))));
        }
        final List<SolveInput.DrawerIn> drawers = new ArrayList<>();
        for (final Object e : JsonTree.array(o.get("drawers"))) {
            final Map<String, Object> j = JsonTree.object(e);
            drawers.add(
                new SolveInput.DrawerIn(
                    Uuid.fromString(JsonTree.str(j, "id")),
                    JsonTree.str(j, "label"),
                    DrawerTypes.Kind.valueOf(JsonTree.str(j, "kind")),
                    DrawerTypes.Rule.valueOf(JsonTree.str(j, "rule")),
                    JsonTree.num(j, "rate"),
                    links(j.get("links")),
                    links(j.get("takers"))));
        }
        final Map<Uuid, Double> pins = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> e : JsonTree.object(o.get("extentPins")).entrySet())
            pins.put(Uuid.fromString(e.getKey()), (Double) e.getValue());
        final Map<String, Map<String, Double>> spares = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> e : JsonTree.object(o.get("spares")).entrySet()) {
            final Map<String, Double> shares = new LinkedHashMap<>();
            for (final Map.Entry<String, Object> s : JsonTree.object(e.getValue()).entrySet())
                shares.put(s.getKey(), (Double) s.getValue());
            spares.put(e.getKey(), shares);
        }
        return new SolveInput(mode, choice, machines, edges, pools, drawers, pins, spares);
    }

    private static List<SolveInput.PortIn> ports(@Nullable final Object list) {
        final List<SolveInput.PortIn> out = new ArrayList<>();
        for (final Object e : JsonTree.array(list)) {
            final Map<String, Object> j = JsonTree.object(e);
            out.add(
                new SolveInput.PortIn(
                    JsonTree.num(j, "amount"),
                    // A float: rounded through its bit pattern, which every target narrows (a plain cast does not on JS).
                    (float) Float32.round(JsonTree.num(j, "chance")),
                    JsonTree.num(j, "multiplier"),
                    (int) JsonTree.num(j, "resource"),
                    JsonTree.str(j, "name"),
                    Boolean.TRUE.equals(JsonTree.bool(j, "free"))));
        }
        return out;
    }

    private static Map<Integer, Double> rates(@Nullable final Object object) {
        final Map<Integer, Double> out = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> e : JsonTree.object(object).entrySet())
            out.put(Integer.valueOf(e.getKey()), (Double) e.getValue());
        return out;
    }

    private static PortRef portRef(final Map<String, Object> j) {
        return new PortRef(
            Uuid.fromString(JsonTree.str(j, "node")),
            (int) JsonTree.num(j, "port"),
            Boolean.TRUE.equals(JsonTree.bool(j, "input")));
    }

    private static List<SolveInput.LinkIn> links(@Nullable final Object list) {
        final List<SolveInput.LinkIn> out = new ArrayList<>();
        for (final Object l : JsonTree.array(list)) {
            final Map<String, Object> j = JsonTree.object(l);
            out.add(new SolveInput.LinkIn(Uuid.fromString(JsonTree.str(j, "node")), (int) JsonTree.num(j, "port")));
        }
        return out;
    }

    // ---- the answer

    /** The digest of an answer, as JSON text. */
    public static String writeAnswer(final Balancer.Answer answer) {
        return JsonTree.write(digest(answer));
    }

    /** The digest of an answer as a tree of maps, lists, doubles, strings and booleans. */
    public static Map<String, Object> digest(final Balancer.Answer answer) {
        final Map<String, Object> out = new LinkedHashMap<>();
        if (answer instanceof final Balancer.Answer.Solved solved) {
            out.put("solution", solution(solved.solution()));
            out.put("alternatives", alternatives(solved.alternatives()));
        } else {
            final Balancer.Answer.Failed failed = (Balancer.Answer.Failed) answer;
            out.put("failure", note(failed.failure()));
            out.put("notes", notes(failed.notes()));
            out.put("drawers", drawers(failed.drawers()));
        }
        return out;
    }

    private static Map<String, Object> solution(final SolutionView v) {
        final Map<String, Object> j = new LinkedHashMap<>();
        j.put("extentsPerSecond", rateMap(v.extentsPerSecond));
        j.put("machineCounts", rateMap(v.machineCounts));
        j.put("edgeFlowsPerSecond", rateMap(v.edgeFlowsPerSecond));
        j.put("gatedSources", externals(v.gatedSources));
        j.put("gatedSinks", externals(v.gatedSinks));
        j.put("terminalInputs", externals(v.terminalInputs));
        j.put("terminalOutputs", externals(v.terminalOutputs));
        j.put("unneeded", uuidSet(v.unneeded));
        j.put("openGates", (double) v.openGates);
        j.put("externalQuantity", v.externalQuantity);
        j.put("totalInternalFlow", v.totalInternalFlow);
        j.put("notes", notes(v.notes));
        j.put("key", choiceKey(v.key));
        j.put("drawers", drawers(v.drawers));
        j.put("spares", spares(v.spares()));
        if (!v.upTo().isEmpty()) j.put("upTo", upTo(v.upTo()));
        if (!v.upToLoops().isEmpty()) j.put("upToLoops", upToLoops(v.upToLoops()));
        return j;
    }

    private static Map<String, Object> alternatives(final Alternatives a) {
        final Map<String, Object> j = new LinkedHashMap<>();
        j.put("chosen", choiceKey(a.chosen()));
        final List<Object> options = new ArrayList<>();
        for (final Alternative o : a.options()) {
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", choiceKey(o.key()));
            m.put("replaces", portRef(o.replaces()));
            m.put("opens", portRef(o.opens()));
            m.put("externals", externals(o.externals()));
            m.put("rank", o.rank().name());
            options.add(m);
        }
        j.put("options", options);
        j.put("complete", a.complete());
        j.put("notes", notes(a.notes()));
        return j;
    }

    private static Map<String, Object> drawers(final DrawerReadout d) {
        final Map<String, Object> j = new LinkedHashMap<>();
        j.put("rates", rateMap(d.rates()));
        j.put("unmet", uuidSet(d.unmet()));
        final List<Object> shortfalls = new ArrayList<>();
        for (final DrawerReadout.Shortfall s : d.shortfalls()) {
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("drawer", s.drawer().toString());
            m.put("rule", s.rule().name());
            m.put("target", s.target());
            m.put("reachable", s.reachable());
            m.put("limitingNodes", uuids(s.limitingNodes()));
            m.put("limitingDrawers", uuids(s.limitingDrawers()));
            m.put("limitingGroups", (double) s.limitingGroups());
            shortfalls.add(m);
        }
        j.put("shortfalls", shortfalls);
        final TreeMap<String, Object> ports = new TreeMap<>();
        for (final Map.Entry<Uuid, List<External>> e : d.ports().entrySet()) ports.put(e.getKey().toString(), externals(e.getValue()));
        j.put("ports", new LinkedHashMap<>(ports));
        // What a buffer banks only when the plan banks (Up to plans).
        if (d.banked() != null) j.put("banked", rateMap(d.banked()));
        if (d.spills() != null) {
            final TreeMap<String, Object> spills = new TreeMap<>();
            for (final Map.Entry<Uuid, List<External>> e : d.spills().entrySet()) spills.put(e.getKey().toString(), externals(e.getValue()));
            j.put("spills", new LinkedHashMap<>(spills));
        }
        return j;
    }

    private static List<Object> spares(final List<Spares.Leftover> list) {
        final List<Object> out = new ArrayList<>();
        for (final Spares.Leftover l : list) {
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", l.key());
            m.put("importing", l.importing());
            final List<Object> bearers = new ArrayList<>();
            for (final Spares.Bearer b : l.bearers()) {
                final Map<String, Object> x = new LinkedHashMap<>();
                x.put("anchor", portRef(b.anchor()));
                x.put("flows", externals(b.flows()));
                x.put("max", b.max());
                x.put("share", b.share());
                x.put("solverPick", b.solverPick());
                bearers.add(x);
            }
            m.put("bearers", bearers);
            m.put("custom", l.custom());
            out.add(m);
        }
        return out;
    }

    private static List<Object> upTo(final List<UpToReadout> list) {
        final List<Object> out = new ArrayList<>();
        for (final UpToReadout r : list) {
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("node", r.node().toString());
            m.put("machines", uuids(r.machines()));
            m.put("capacity", r.capacity());
            m.put("reached", r.reached());
            m.put("reason", r.reason() == null ? null : r.reason().name());
            m.put("port", r.port() == null ? null : portRef(r.port()));
            final List<Object> tied = new ArrayList<>();
            for (final PortRef p : r.tied()) tied.add(portRef(p));
            m.put("tied", tied);
            m.put("wants", r.wants());
            m.put("upstream", uuids(r.upstream()));
            out.add(m);
        }
        return out;
    }

    private static List<Object> upToLoops(final List<UpToLoop> list) {
        final List<Object> out = new ArrayList<>();
        for (final UpToLoop l : list) {
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", l.kind().name());
            m.put("nodes", uuids(l.nodes()));
            final List<Object> ports = new ArrayList<>();
            for (final PortRef p : l.ports()) ports.add(portRef(p));
            m.put("ports", ports);
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> choiceKey(@Nullable final ChoiceKey k) {
        if (k == null) return null;
        final Map<String, Object> j = new LinkedHashMap<>();
        final List<Object> anchors = new ArrayList<>();
        for (final PortRef p : k.gateAnchors()) anchors.add(portRef(p));
        j.put("gateAnchors", anchors);
        return j;
    }

    private static Map<String, Object> portRef(final PortRef p) {
        final Map<String, Object> j = new LinkedHashMap<>();
        j.put("nodeId", p.nodeId().toString());
        j.put("portIndex", (double) p.portIndex());
        j.put("input", p.input());
        return j;
    }

    private static List<Object> externals(final List<External> list) {
        final List<Object> out = new ArrayList<>();
        for (final External e : list) {
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("port", portRef(e.port()));
            m.put("ratePerSecond", e.ratePerSecond());
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> rateMap(final Map<Uuid, Double> map) {
        final TreeMap<String, Object> sorted = new TreeMap<>();
        for (final Map.Entry<Uuid, Double> e : map.entrySet()) sorted.put(e.getKey().toString(), e.getValue());
        return new LinkedHashMap<>(sorted);
    }

    private static List<Object> uuids(final List<Uuid> list) {
        final List<Object> out = new ArrayList<>();
        for (final Uuid u : list) out.add(u.toString());
        return out;
    }

    private static List<Object> uuidSet(final Set<Uuid> set) {
        final List<String> text = new ArrayList<>();
        for (final Uuid u : set) text.add(u.toString());
        text.sort(null);
        return new ArrayList<Object>(text);
    }

    private static List<Object> notes(final List<Note> list) {
        final List<Object> out = new ArrayList<>();
        for (final Note n : list) out.add(note(n));
        return out;
    }

    private static Map<String, Object> note(final Note n) {
        final Map<String, Object> j = new LinkedHashMap<>();
        j.put("message", n.message().name());
        final List<Object> args = new ArrayList<>();
        for (final Object a : n.args()) args.add(arg(a));
        j.put("args", args);
        return j;
    }

    /** A note's argument: numbers, text, booleans, enums, ids, notes and lists of them. */
    private static Object arg(@Nullable final Object a) {
        if (a == null) return null;
        if (a instanceof Number) return ((Number) a).doubleValue();
        if (a instanceof Boolean) return a;
        if (a instanceof CharSequence) return a.toString();
        if (a instanceof Enum<?>) return ((Enum<?>) a).name();
        if (a instanceof Uuid) return a.toString();
        if (a instanceof Note) return note((Note) a);
        if (a instanceof PortRef) return portRef((PortRef) a);
        if (a instanceof List<?>) {
            final List<Object> out = new ArrayList<>();
            for (final Object x : (List<?>) a) out.add(arg(x));
            return out;
        }
        if (a instanceof Object[]) {
            final List<Object> out = new ArrayList<>();
            for (final Object x : (Object[]) a) out.add(arg(x));
            return out;
        }
        throw new IllegalStateException("no digest for a note argument of " + a.getClass().getName());
    }
}
