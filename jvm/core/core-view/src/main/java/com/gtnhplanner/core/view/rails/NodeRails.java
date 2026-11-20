package com.gtnhplanner.core.view.rails;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.ExactMath;
import com.gtnhplanner.core.view.Json;
import com.gtnhplanner.data.provider.FreeInputs;

/**
 * Which rows a card's rails get and what each carries, and the card's verdict: the one set of rules both hosts follow.
 *
 * <p>
 * The rows: one per resource, in the solve's order (the recipe's when the solve has no result yet). A flow at nothing
 * with no wire on it is no row; a wired one is kept at any rate (its wire is drawn to its row). An input the recipe
 * keeps (a mould, a lens) is no row. The inputs the game gives (the Rock Breaker's lava, a spawner's mob) and a bee's
 * species follow as inert rows with no figures and no wire, when the solve does not list them.
 *
 * <p>
 * The verdict: only what a solved plan can say. A node that is off, has no recipe, or has a slot with no wire (or is
 * slotted and has no wire at all) is {@code off}, {@code no-recipe} or {@code unwired}; every other one is
 * {@code balanced} when it runs and {@code demand-set} when it does not. A project that is not solved says
 * {@code legacy} with no rows: the host's own ladder of reasons answers there.
 */
public final class NodeRails {

    private NodeRails() {}

    /** A rate below this is the solver's round-off. */
    public static final double RATE_EPSILON = 1e-6;
    /** Half a percent: below this a run is nothing. */
    public static final double VERDICT_EPSILON = 0.005;

    /** The prefix of a spawner's mob resource: held in the controller for good, so free like the lava. */
    private static final String CONTROLLER_SLOT = "factoryflow:eec_mob:";
    private static final String BEE_SPECIES = "factoryflow:bee_species:";

    /** An input the game hands out (or a controller slot holds for good): never supplied, never wired. */
    public static boolean isFreeInput(final String kind, final String resourceId) {
        return "item".equals(kind) && (FreeInputs.isFreeId(resourceId) || resourceId.startsWith(CONTROLLER_SLOT));
    }

    /** A bee's species: the only thing on a bee card that names it; kept, never used up. */
    public static boolean isBeeSpecies(final String resourceId) {
        return resourceId.startsWith(BEE_SPECIES);
    }

    /** Whether the recipe uses the input up: false for a free input and for one the recipe keeps. */
    public static boolean isConsumed(final String kind, final String resourceId, final boolean consumed) {
        return !isFreeInput(kind, resourceId) && consumed;
    }

    private static double clamp01(final double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? 0 : Math.min(Math.max(value, 0), 1);
    }

    public static NodeRailsView build(final NodeRailsInput in) {
        if (!in.enabled()) return new NodeRailsView("off", 0, null, List.of(), List.of());
        if (!in.hasResult() || in.missingRecipe()) {
            return new NodeRailsView("no-recipe", 0, null, rows(in, "no-recipe", true), rows(in, "no-recipe", false));
        }
        final double utilization = clamp01(in.utilization());
        final double pct = ExactMath.round(utilization * 1000) / 10.0;
        final NodeRailsView.Bare bare = bare(in);
        final boolean noWires = in.inputs().wires().isEmpty() && in.outputs().wires().isEmpty();
        final String verdict;
        if (bare != null || in.hasSlots() && noWires) verdict = "unwired";
        else if (!in.solveMode()) return new NodeRailsView("legacy", pct, null, List.of(), List.of());
        else verdict = utilization > VERDICT_EPSILON ? "balanced" : "demand-set";
        return new NodeRailsView(verdict, pct, "unwired".equals(verdict) ? bare : null, rows(in, verdict, true), rows(in, verdict, false));
    }

    /** Every slot of the solve with a flow and no wire, either end; an unwired EU output is not one (it dissipates). */
    @Nullable
    private static NodeRailsView.Bare bare(final NodeRailsInput in) {
        final List<NodeRailsView.BareSlot> inputs = new ArrayList<>(), outputs = new ArrayList<>();
        for (final NodeRailsInput.Flow f : in.inputs().flows())
            if (f.amountPerSecond() > RATE_EPSILON && !wired(in.inputs(), f.key()))
                inputs.add(new NodeRailsView.BareSlot(f.key(), f.kind(), f.name() != null ? f.name() : f.resourceId()));
        for (final NodeRailsInput.Flow f : in.outputs().flows()) {
            if ("power".equals(f.kind())) continue;
            if (f.amountPerSecond() > RATE_EPSILON && !wired(in.outputs(), f.key()))
                outputs.add(new NodeRailsView.BareSlot(f.key(), f.kind(), f.name() != null ? f.name() : f.resourceId()));
        }
        return inputs.isEmpty() && outputs.isEmpty() ? null : new NodeRailsView.Bare(inputs, outputs);
    }

    private static boolean wired(final NodeRailsInput.Side side, final String key) {
        for (final NodeRailsInput.Wire w : side.wires()) if (w.key().equals(key)) return true;
        return false;
    }

    private static List<NodeRailsView.Row> rows(final NodeRailsInput in, final String verdict, final boolean input) {
        final NodeRailsInput.Side side = input ? in.inputs() : in.outputs();
        final boolean books = in.hasResult();
        final double utilization = clamp01(in.utilization());
        final boolean demandSet = "demand-set".equals(verdict);
        final List<NodeRailsView.Row> rows = new ArrayList<>();
        final Set<String> seen = new HashSet<>();

        if (books) {
            for (final NodeRailsInput.Flow f : side.flows()) {
                if (f.amountPerSecond() <= RATE_EPSILON && !wired(side, f.key())) continue;
                push(rows, seen, side, input, in, utilization, demandSet, f.key(), f.kind(), f.resourceId(), f.name(),
                    f.oreLabel(), f.handleId(), f.amountPerSecond(), true);
            }
        } else {
            for (final NodeRailsInput.Slot s : side.slots())
                push(rows, seen, side, input, in, utilization, demandSet, s.key(), s.kind(), s.resourceId(), s.name(),
                    s.oreLabel(), s.handleId(), 0, false);
        }

        // The game's gifts and a bee's species never reach the solve's flows and are no row above, so they follow.
        if (input) for (final NodeRailsInput.Slot s : side.slots()) {
            if (!(isFreeInput(s.kind(), s.resourceId()) || isBeeSpecies(s.resourceId())) || seen.contains(s.key())) continue;
            seen.add(s.key());
            rows.add(
                new NodeRailsView.Row(
                    s.key(),
                    s.handleId(),
                    s.kind(),
                    s.resourceId(),
                    s.name() != null ? s.name() : s.resourceId(),
                    slotOf(side, s),
                    false,
                    false,
                    true,
                    0,
                    0,
                    "calm",
                    null,
                    false,
                    null,
                    true,
                    s.chance(),
                    s.consumeChance(),
                    s.alternatives()));
        }
        return rows;
    }

    @Nullable
    private static Integer slotOf(final NodeRailsInput.Side side, final NodeRailsInput.Slot slot) {
        for (int i = 0; i < side.slots().size(); i++) if (side.slots().get(i) == slot) return i;
        return null;
    }

    private static void push(final List<NodeRailsView.Row> rows, final Set<String> seen, final NodeRailsInput.Side side,
        final boolean input, final NodeRailsInput in, final double utilization, final boolean demandSet, final String key,
        final String kind, final String resourceId, @Nullable final String flowName, @Nullable final String flowOreLabel,
        final String handleId, final double nameplate, final boolean flows) {
        if (!seen.add(key)) return;
        NodeRailsInput.Slot slot = null;
        Integer slotAt = null;
        for (int i = 0; i < side.slots().size(); i++) {
            final NodeRailsInput.Slot s = side.slots().get(i);
            if (s.kind().equals(kind) && s.resourceId().equals(resourceId)) {
                slot = s;
                slotAt = i;
                break;
            }
        }
        // A kept input is never a row (the key stays seen, as the website's rail has always had it).
        if (input && slot != null && !isConsumed(slot.kind(), slot.resourceId(), slot.consumed())) return;

        double transferred = 0;
        boolean connected = false;
        for (final NodeRailsInput.Wire w : side.wires()) {
            if (!w.key().equals(key)) continue;
            connected = true;
            transferred += w.transferredPerSecond();
        }
        final boolean ore = resourceId.startsWith("oredict:");
        final String name = ore ? firstNonNull(slot != null ? slot.oreLabel() : null, flowOreLabel, resourceId)
            : flowName != null ? flowName : slot != null && slot.name() != null ? slot.name() : resourceId;
        final double current = input ? connected ? transferred : nameplate * utilization : nameplate * utilization;
        String tone = "ok";
        Double shortBadge = null;
        if (!connected) {
            tone = "bind";
            if (input) shortBadge = nameplate;
        } else if (demandSet) {
            tone = "calm";
        }
        final Double energy = !"power".equals(kind) && flows ? energyPerUnit(in.euT(), nameplate) : null;
        rows.add(
            new NodeRailsView.Row(
                key,
                handleId,
                kind,
                resourceId,
                name,
                slotAt,
                connected,
                input && !connected,
                false,
                current,
                nameplate,
                tone,
                shortBadge,
                false,
                energy,
                false,
                slot != null ? slot.chance() : null,
                slot != null ? slot.consumeChance() : null,
                slot != null ? slot.alternatives() : 0));
    }

    private static String firstNonNull(@Nullable final String a, @Nullable final String b, final String c) {
        return a != null ? a : b != null ? b : c;
    }

    /** EU spent per unit: the node's EU/t over one flow's per-second figure; null when nothing flows. */
    @Nullable
    static Double energyPerUnit(final double euPerTick, final double perSecond) {
        if (Double.isNaN(euPerTick) || Double.isInfinite(euPerTick) || Double.isNaN(perSecond)
            || Double.isInfinite(perSecond) || perSecond <= 1e-12) return null;
        return Math.max(0, euPerTick) * 20 / perSecond;
    }

    public static String toJson(final NodeRailsView v) {
        final Json j = new Json().begin().str("verdict", v.verdict()).num("pct", v.pct());
        if (v.bare() != null) {
            final NodeRailsView.Bare b = v.bare();
            j.field("bare", jj -> {
                jj.begin();
                jj.list("inputs", b.inputs(), NodeRails::bareSlot);
                jj.list("outputs", b.outputs(), NodeRails::bareSlot);
                jj.end();
            });
        }
        j.list("inputs", v.inputs(), NodeRails::row);
        j.list("outputs", v.outputs(), NodeRails::row);
        return j.end().toString();
    }

    private static void bareSlot(final Json j, final NodeRailsView.BareSlot s) {
        j.begin().str("resourceKey", s.resourceKey()).str("kind", s.kind()).str("displayName", s.displayName()).end();
    }

    private static void row(final Json j, final NodeRailsView.Row r) {
        j.begin()
            .str("key", r.key())
            .str("handleId", r.handleId())
            .str("kind", r.kind())
            .str("resourceId", r.resourceId())
            .str("name", r.name());
        if (r.slot() != null) j.num("slot", r.slot());
        j.bool("connected", r.connected())
            .bool("unsupplied", r.unsupplied())
            .bool("boundaryFree", r.boundaryFree())
            .num("currentPerSecond", r.currentPerSecond())
            .num("nameplatePerSecond", r.nameplatePerSecond())
            .str("tone", r.tone())
            .opt("shortBadge", r.shortBadge())
            .bool("showNameplate", r.showNameplate())
            .opt("energyPerUnit", r.energyPerUnit())
            .bool("free", r.free())
            .opt("chance", r.chance())
            .opt("consumeChance", r.consumeChance())
            .num("alternatives", r.alternatives())
            .end();
    }
}
