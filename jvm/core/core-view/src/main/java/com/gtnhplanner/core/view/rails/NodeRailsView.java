package com.gtnhplanner.core.view.rails;

import java.util.List;

import javax.annotation.Nullable;

/**
 * A card's rails and verdict as {@link NodeRails#build} decides them. {@code verdict}: {@code off}, {@code no-recipe},
 * {@code unwired}, {@code balanced}, {@code demand-set}, or {@code legacy} for a project that is not solved (the host's
 * own diagnosis then reads its board). {@code pct}: the run as a percent, to a tenth. {@code bare}: the slots with no
 * wire, on an {@code unwired} verdict (null for a slotted card with no wires the solve has no flow for).
 */
public record NodeRailsView(String verdict, double pct, @Nullable Bare bare, List<Row> inputs, List<Row> outputs) {

    /** The slots no wire is on. */
    public record Bare(List<BareSlot> inputs, List<BareSlot> outputs) {}

    public record BareSlot(String resourceKey, String kind, String displayName) {}

    /**
     * One rail row. {@code slot}: the recipe slot behind it (its place on its side), none for a flow the recipe does
     * not list. {@code tone}: {@code ok}, {@code bind} (a slot with no wire), {@code calm}. {@code shortBadge}: the
     * rate the bare slot is short of. {@code free}: a row the game gives or a bee's species (inert: no figures, no wire).
     */
    public record Row(String key, String handleId, String kind, String resourceId, String name, @Nullable Integer slot,
        boolean connected, boolean unsupplied, boolean boundaryFree, double currentPerSecond, double nameplatePerSecond,
        String tone, @Nullable Double shortBadge, boolean showNameplate, @Nullable Double energyPerUnit, boolean free,
        @Nullable Double chance, @Nullable Double consumeChance, int alternatives) {}
}
