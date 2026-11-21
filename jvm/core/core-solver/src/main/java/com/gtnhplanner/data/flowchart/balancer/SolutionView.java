package com.gtnhplanner.data.flowchart.balancer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.CoreSettings;
import com.gtnhplanner.core.base.JavaHashMap;
import com.gtnhplanner.core.base.JavaHashSet;
import com.gtnhplanner.core.base.SolvedNumber;
import com.gtnhplanner.core.base.Uuid;

/**
 * The whole solve's answer, shaped for the GUI: per-node extents and machine counts, per-edge
 * flows, the gated sources/sinks and the free terminals, the gate count and the raw external
 * quantity, and the completion flags a reader reconstructs conservation from. Built ONLY by
 * {@link Balancer}: always for the GUI's solve-and-enumerate entry, and for {@link #run} only when
 * a profiler is attached - so the plain-run payload never constructs it.
 *
 * <p>
 * Cutoffs and derived numbers follow the solver's numerics: flows below the
 * {@code dust} cutoff of their own network ({@link SolveContext#dustScales}), counts as {@code extent * durTicks / TICKS_PER_SECOND}, the
 * terminal rule (machine ports with no drawn edge), and {@code externalQuantity} as the raw sum
 * over the reported externals. Deliberately NOT the normalized/craft-weighted stage-2 objective.
 */
public final class SolutionView {

    public final Map<Uuid, Double> extentsPerSecond = new LinkedHashMap<>();
    public final Map<Uuid, Double> machineCounts = new LinkedHashMap<>();
    public final Map<Uuid, Double> edgeFlowsPerSecond = new LinkedHashMap<>();
    public final List<External> gatedSources = new ArrayList<>();
    public final List<External> gatedSinks = new ArrayList<>();
    public final List<External> terminalInputs = new ArrayList<>();
    public final List<External> terminalOutputs = new ArrayList<>();
    /**
     * The machines no pin sets that the answer leaves at zero: nothing pinned feeds them or needs what they make, so
     * they ask for a count, as a part of the plan nothing sets does.
     */
    public final java.util.Set<Uuid> unneeded = new java.util.LinkedHashSet<>();
    public final int openGates;
    public final double externalQuantity;
    public final double totalInternalFlow;
    public final long wallMillis;
    public final List<Note> notes;
    public final ChoiceKey key;
    /** Every drawer's total at this point, and which drawers' rules it does not keep. */
    public final DrawerReadout drawers;
    /** The leftovers with more than one place that could bear them, and how each is split ({@link Spares}). */
    private List<Spares.Leftover> spares = List.of();

    public List<Spares.Leftover> spares() {
        return spares;
    }

    void spares(final List<Spares.Leftover> spares) {
        this.spares = spares;
    }

    /** Why each Up to pool runs where it does, by {@link UpToReadout#node()}; empty when the plan has none. */
    private List<UpToReadout> upTo = List.of();

    public List<UpToReadout> upTo() {
        return upTo;
    }

    void upTo(final List<UpToReadout> upTo) {
        this.upTo = List.copyOf(upTo);
    }

    /** Dead loops and clog locks over the read-out, sorted by their first node; empty when there are none. */
    private List<UpToLoop> upToLoops = List.of();

    public List<UpToLoop> upToLoops() {
        return upToLoops;
    }

    void upToLoops(final List<UpToLoop> upToLoops) {
        this.upToLoops = List.copyOf(upToLoops);
    }

    private SolutionView(final SolveContext ctx, final long wallMillis) {
        final ModelData model = ctx.model;
        final double dust = ctx.heuristics.numerics().dust;
        final SolveContext.DustScales scales = ctx.dustScales(ctx.extents(), ctx.flows(), ctx.externals());
        // Every number the view hands out is the ratio it is within the engine's noise of (SolvedNumber), read once
        // here so the website and the mod print the same digits. What is dust, what a drawer holds and every sum are
        // still taken on the solver's own values; a sum is cleaned as a whole.
        final double[] extents = cleaned(ctx.extents()), flows = cleaned(ctx.flows()),
            externals = cleaned(ctx.externals());
        // An idle Up to machine is UNUSED in its read-out, never "(set count)" (docs/design/up-to.md, "Anchoring").
        final Set<Integer> upToMachines = new JavaHashSet<>();
        for (final int p : ctx.upToPools) upToMachines.addAll(
            model.pools.get(p)
                .machines());
        for (int m = 0; m < model.machines.size(); m++) {
            final ModelData.Machine md = model.machines.get(m);
            extentsPerSecond.put(md.spec.id(), extents[m]);
            machineCounts.put(
                md.spec.id(),
                SolvedNumber.clean(ctx.extents()[m] * md.durTicks / (double) Numerics.TICKS_PER_SECOND));
            if (Double.isNaN(ctx.pinnedExtent[m]) && !upToMachines.contains(m)
                && ctx.extents()[m] <= dust * scales.machines()[m])
                unneeded.add(md.spec.id());
        }
        // The plan's own wires only: a buffer's edges are the solver's, read out per port on the drawer.
        for (int e = 0; e < model.drawnEdges; e++) {
            edgeFlowsPerSecond.put(
                model.edges.get(e)
                    .id(),
                flows[e]);
        }
        double quantity = 0;
        for (int p = 0; p < ctx.externals().length; p++) {
            final double ext = ctx.externals()[p];
            // What a buffer banks is the buffer's (DrawerReadout.banked), not a forced flow.
            if (ext <= dust * scales.ports()[p] || ctx.bankPorts.containsKey(p)) continue;
            final ModelData.ConnectedPort port = model.connectedPorts.get(p);
            final External external = new External(ctx.refOf(p), externals[p]);
            (port.input() ? gatedSources : gatedSinks).add(external);
            quantity += ext;
        }
        collectTerminals(ctx, scales.machines());
        double flow = 0;
        for (final double f : ctx.flows()) {
            flow += f;
        }
        this.openGates = ctx.support()
            .size();
        this.externalQuantity = SolvedNumber.clean(quantity);
        this.totalInternalFlow = SolvedNumber.clean(flow);
        this.wallMillis = wallMillis;
        this.key = ctx.keyOf(ctx.support());
        this.drawers = DrawerReadout.of(ctx, ctx.extents(), ctx.flows(), ctx.externals())
            .cleaned();
        final List<Note> allNotes = new ArrayList<>();
        allNotes.addAll(ctx.notes);
        allNotes.addAll(ctx.stageNotes);
        allNotes.addAll(wiringDiagnostics(ctx, scales.ports(), terminalInputs));
        this.notes = List.copyOf(allNotes);
    }

    private static double[] cleaned(final double[] values) {
        final double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) out[i] = SolvedNumber.clean(values[i]);
        return out;
    }

    /** The view over the run's committed point. */
    static SolutionView of(final SolveContext ctx, final long wallMillis) {
        return new SolutionView(ctx, wallMillis);
    }

    /** Machine ports with no drawn edge, flowing at their own rate - the free terminals. */
    private void collectTerminals(final SolveContext ctx, final double[] machineScales) {
        final ModelData model = ctx.model;
        for (int m = 0; m < model.machines.size(); m++) {
            final ModelData.Machine md = model.machines.get(m);
            final double tol = ctx.heuristics.numerics().dust * machineScales[m];
            terminalScan(ctx, m, md.inQty.length, true, tol, terminalInputs);
            terminalScan(ctx, m, md.outQty.length, false, tol, terminalOutputs);
        }
    }

    private void terminalScan(final SolveContext ctx, final int m, final int portCount, final boolean input,
        final double tol, final List<External> out) {
        final ModelData model = ctx.model;
        final ModelData.Machine md = model.machines.get(m);
        for (int i = 0; i < portCount; i++) {
            final double qty = input ? md.inQty[i] : md.outQty[i];
            if (qty <= 0) continue;
            final long key = ((long) m << 32) | ((long) i << 1) | (input ? 1 : 0);
            if (model.portLookup.containsKey(key)) continue; // connected, not a terminal
            final double rate = ctx.extents()[m] * qty;
            if (rate <= tol) continue;
            out.add(new External(new PortRef(md.spec.id(), i, input), SolvedNumber.clean(rate)));
        }
    }

    /**
     * Flags externals that look like missing edges rather than intent. The free-terminal rule
     * means an unwired input silently becomes "supplied from outside" - correct for oil, wrong
     * when the same ingredient is right there on the chart. Two smells: (1) a terminal input
     * whose ingredient the chart also produces (through drawn edges or another terminal); (2) a
     * gated source whose ingredient is produced in a DIFFERENT edge-connected component (two
     * unlinked islands of the same fluid). Same-component gated sources are the normal deficit
     * case and stay quiet.
     *
     * <p>
     * Severities differ because the two smells do: importing from outside while the chart also
     * makes some is routine, so smell 1 is an observation. Two unlinked islands of the SAME
     * ingredient is a chart that does not describe one factory, so smell 2 is a warning.
     * Ingredients the pack gives away are filtered out of both - see {@link CoreSettings#isFreeIngredient}, read
     * when the snapshot was taken.
     */
    private List<Note> wiringDiagnostics(final SolveContext ctx, final double[] portScales,
        final List<External> terminalIn) {
        final ModelData model = ctx.model;
        final Producers producers = new Producers(model);
        // A set: the same ingredient imported at two ports of one machine is one wiring mistake to
        // the reader, and the message that describes it is identical either way.
        final Set<Note> result = new LinkedHashSet<>();
        // A port a source drawer supplies is imported on purpose: the player said where it comes from.
        final Set<PortRef> supplied = new JavaHashSet<>();
        for (final ModelData.DrawerRow row : model.drawers) {
            if (row.kind()
                .linksInputs()) supplied.addAll(row.links());
        }
        for (final External in : terminalIn) {
            if (supplied.contains(in.port())) continue;
            final SolveInput.PortIn ingredient = portOf(ctx, in);
            if (ingredient.free()) continue;
            final ModelData.Machine match = producers.find(ingredient.resource(), -1);
            if (match != null) {
                // The two cards' ids last, for the board's "Show me".
                result.add(
                    new Note(
                        SolverMessage.WIRING_IMPORT,
                        machineNameOf(ctx, in),
                        ingredient.name(),
                        match.spec.name(),
                        in.port()
                            .nodeId(),
                        match.spec.id()));
            }
        }
        for (int p = 0; p < model.connectedPorts.size(); p++) {
            final ModelData.ConnectedPort port = model.connectedPorts.get(p);
            if (!port.input() || ctx.externals()[p] <= ctx.heuristics.numerics().dust * portScales[p]) continue;
            final External src = new External(ctx.refOf(p), ctx.externals()[p]);
            if (supplied.contains(src.port())) continue;
            final SolveInput.PortIn ingredient = portOf(ctx, src);
            if (ingredient.free()) continue;
            final ModelData.Machine match = producers.find(ingredient.resource(), model.portComponent[p]);
            if (match != null) {
                result.add(
                    new Note(
                        SolverMessage.WIRING_UNLINKED,
                        machineNameOf(ctx, src),
                        ingredient.name(),
                        match.spec.name(),
                        src.port()
                            .nodeId(),
                        match.spec.id()));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Who makes each ingredient, in the order the diagnostics look: connected output ports in port order, then
     * unconnected outputs in machine order. Read once, so asking about every import is linear in the plan.
     */
    private static final class Producers {

        private final ModelData model;
        /** Per ingredient, the connected output ports making it, in port order. */
        private final Map<Integer, List<Integer>> connected = new JavaHashMap<>();
        /** Per ingredient, the first machine making it at an output with no edge. */
        private final Map<Integer, ModelData.Machine> loose = new JavaHashMap<>();

        Producers(final ModelData model) {
            this.model = model;
            for (int p = 0; p < model.connectedPorts.size(); p++) {
                final ModelData.ConnectedPort port = model.connectedPorts.get(p);
                if (port.input()) continue;
                final int resource = model.machines.get(port.machine())
                    .port(port.portIndex(), false)
                    .resource();
                connected.computeIfAbsent(resource, k -> new ArrayList<>())
                    .add(p);
            }
            for (int m = 0; m < model.machines.size(); m++) {
                final ModelData.Machine md = model.machines.get(m);
                for (int i = 0; i < md.outQty.length; i++) {
                    if (md.outQty[i] <= 0) continue;
                    if (model.portLookup.containsKey(ModelData.portKey(m, i, false))) continue; // connected, above
                    loose.putIfAbsent(
                        md.port(i, false)
                            .resource(),
                        md);
                }
            }
        }

        /**
         * A machine making {@code resource}: at a connected output outside port component {@code excludeComponent}
         * (-1 skips none), else at an unconnected output; null when none does.
         */
        @Nullable
        ModelData.Machine find(final int resource, final int excludeComponent) {
            final List<Integer> ports = connected.get(resource);
            if (ports != null) {
                for (final int p : ports) {
                    if (model.portComponent[p] == excludeComponent) continue;
                    return model.machines.get(
                        model.connectedPorts.get(p)
                            .machine());
                }
            }
            return loose.get(resource);
        }
    }

    private SolveInput.PortIn portOf(final SolveContext ctx, final External external) {
        final ModelData model = ctx.model;
        return model.machines.get(
            model.machineIndex.get(
                external.port()
                    .nodeId()))
            .port(
                external.port()
                    .portIndex(),
                external.port()
                    .input());
    }

    private String machineNameOf(final SolveContext ctx, final External external) {
        return ctx.model.machines.get(
            ctx.model.machineIndex.get(
                external.port()
                    .nodeId())).spec.name();
    }
}
