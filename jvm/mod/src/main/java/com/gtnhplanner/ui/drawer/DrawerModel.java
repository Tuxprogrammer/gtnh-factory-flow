package com.gtnhplanner.ui.drawer;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import org.jetbrains.annotations.Nullable;

import com.gtnhplanner.core.view.drawer.DrawerBuilder;
import com.gtnhplanner.core.view.drawer.DrawerInput;
import com.gtnhplanner.core.view.drawer.DrawerView;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.balancer.BalanceResult;
import com.gtnhplanner.data.flowchart.balancer.DrawerReadout;
import com.gtnhplanner.ui.Resources;
import com.gtnhplanner.ui.theme.HybMeasure;

/** What a drawer shows: its resource, kind and rule, and what the latest solve moved through it. Immutable. */
public final class DrawerModel {

    public final Drawer drawer;
    public final Drawer.Kind kind;
    public final Drawer.Rule rule;
    /** The rule's rate, per second (fluids in L/s). */
    public final double target;
    public final String label;
    public final ItemStack item;
    public final FluidStack fluid;
    /** What the plan moves through the drawer, per second. */
    public final double rate;
    public final boolean unmet;
    @Nullable
    public final DrawerReadout.Shortfall shortfall;
    public final boolean linked;
    /** What the makers of a buffer force out beyond what its users take, per second; 0 for any other drawer. */
    public final double spill;

    private DrawerModel(final Drawer drawer, final double rate, final boolean unmet,
        @Nullable final DrawerReadout.Shortfall shortfall, final double spill) {
        this.drawer = drawer;
        this.kind = drawer.getKind();
        this.rule = drawer.effectiveRule();
        this.target = drawer.getRate();
        final String key = drawer.getResourceKey();
        this.label = drawer.getLabel()
            .isEmpty() ? Resources.name(key) : drawer.getLabel();
        this.fluid = Resources.fluid(key);
        this.item = fluid == null ? Resources.item(key) : null;
        this.rate = rate;
        this.unmet = unmet;
        this.shortfall = shortfall;
        this.spill = spill;
        this.linked = !drawer.getLinks()
            .isEmpty();
    }

    public static DrawerModel of(final Drawer drawer, @Nullable final BalanceResult result) {
        if (result == null) return new DrawerModel(drawer, 0, false, null, 0);
        final DrawerReadout readout = result.drawers();
        return new DrawerModel(
            drawer,
            readout.rate(drawer.getId()),
            !readout.met(drawer.getId()),
            readout.shortfall(drawer.getId()),
            drawer.getKind() == Drawer.Kind.BUFFER ? readout.spillTotal(drawer.getId()) : 0);
    }

    public boolean isFluid() {
        return fluid != null;
    }

    /** An EU drawer: a generator's output, always shown in EU/t whatever the board's rate unit. */
    public boolean isPower() {
        return Resources.isPower(drawer.getResourceKey());
    }

    /** A rate (per second) as the drawer shows it: EU/t for EU, the board's unit for the rest. */
    public double shown(final double perSecond, final com.gtnhplanner.ui.theme.Fmt.RateUnit unit) {
        return isPower() ? perSecond / 20 : perSecond * unit.perSecond;
    }

    /** The unit after a shown figure: " EU/t", " L/s", "/s" (without the space when {@code tight}). */
    public String suffix(final com.gtnhplanner.ui.theme.Fmt.RateUnit unit, final boolean tight) {
        if (isPower()) return tight ? "EU/t" : " EU/t";
        return (isFluid() ? tight ? "L" : " L" : "") + unit.suffix;
    }

    /** A rate (per second) with its unit, as the tooltips say it. */
    public String rate(final double perSecond, final com.gtnhplanner.ui.theme.Fmt.RateUnit unit) {
        return isPower() ? com.gtnhplanner.ui.theme.Fmt.power(perSecond / 20) + " EU/t"
            : com.gtnhplanner.ui.theme.Fmt.rate(perSecond, unit, isFluid());
    }

    /**
     * What the core's view of this drawer is: every string the drawer shows, said once for both hosts. {@code unset}:
     * nothing sets this part of the plan.
     */
    public DrawerView view(final com.gtnhplanner.ui.theme.Fmt.RateUnit unit, final boolean unset) {
        // Kept for as long as the model and its dials stand: drawing asks every frame, a solve makes a new model.
        if (cached == null || cachedUnit != unit || cachedUnset != unset) {
            cached = build(unit, unset);
            cachedUnit = unit;
            cachedUnset = unset;
        }
        return cached;
    }

    private DrawerView cached;
    private com.gtnhplanner.ui.theme.Fmt.RateUnit cachedUnit;
    private boolean cachedUnset;

    private DrawerView build(final com.gtnhplanner.ui.theme.Fmt.RateUnit unit, final boolean unset) {
        final String kindName = switch (kind) {
            case SOURCE -> "source";
            case PRODUCT, BYPRODUCT -> "product";
            case TRASH -> "trash";
            case BUFFER -> "buffer";
        };
        final String ruleName = switch (rule) {
            case ANY -> "ignore";
            case AT_LEAST -> "at-least";
            case EXACTLY -> "exact";
            case AT_MOST -> "at-most";
        };
        final String resource = isPower() ? "power" : isFluid() ? "fluid" : "item";
        final DrawerInput in = new DrawerInput(
            drawer.getId()
                .toString(),
            kindName,
            resource,
            label,
            ruleName,
            kind == Drawer.Kind.BYPRODUCT,
            rule == Drawer.Rule.ANY ? null : target,
            rate,
            unmet,
            unset,
            linked,
            spill > 0 ? spill : null,
            isPower() ? 1.0 / 20 : unit.perSecond,
            suffix(unit, true));
        return DrawerBuilder.build(in, HybMeasure.INSTANCE);
    }
}
