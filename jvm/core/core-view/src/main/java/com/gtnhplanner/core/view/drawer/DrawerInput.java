package com.gtnhplanner.core.view.drawer;

import javax.annotation.Nullable;

/**
 * What a host knows of one drawer, worked out the way its own model keeps it: the core turns this into
 * {@link DrawerView}. Rates are per second; {@code multiplier} and {@code unit} are the host's display dial (the
 * board's rate unit, or the power unit), so a change of dial repaints the figures and never touches the plan.
 *
 * <ul>
 * <li>{@code kind}: source, product, trash or buffer (a byproduct is a product that is {@code inert}).</li>
 * <li>{@code resource}: item, fluid or power.</li>
 * <li>{@code rule}: ignore (Any), at-least, exact or at-most: the rule stored on the drawer, which a buffer carries
 * like a product.</li>
 * <li>{@code target}: the stored rate (either sign), if any.</li>
 * <li>{@code moved}: what the plan moves through the drawer, no sign (a buffer's throughput).</li>
 * <li>{@code unmet}: the plan cannot reach the target.</li>
 * <li>{@code unanchored}: nothing sets this part of the plan.</li>
 * <li>{@code spill}: what the makers of a buffer force out beyond what its users take, summed.</li>
 * </ul>
 */
public record DrawerInput(String id, String kind, String resource, String label, String rule, boolean inert,
    @Nullable Double target, double moved, boolean unmet, boolean unanchored, boolean linked, @Nullable Double spill,
    double multiplier, String unit) {}
