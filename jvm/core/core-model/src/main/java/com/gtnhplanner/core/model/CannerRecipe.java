package com.gtnhplanner.core.model;

import java.util.Objects;

import javax.annotation.Nullable;

/**
 * One of GregTech's Canner recipes that moves a fluid in or out of a container, as the planner reads it: filling puts
 * {@code litres} of {@code fluid} and one {@code empty} container in and takes the {@code filled} one out; emptying
 * takes the {@code filled} one in and gives the fluid and the empty container back. {@code empty} is null when an
 * emptying recipe leaves no container (GregTech's lava clay bucket): no fluid tank can hold such a pair.
 */
public record CannerRecipe(ResourceKey filled, @Nullable ResourceKey empty, ResourceKey fluid, double litres,
    boolean fills) {

    public CannerRecipe {
        Objects.requireNonNull(filled, "filled");
        Objects.requireNonNull(fluid, "fluid");
    }

    /** Whether the recipe has {@code key} in it, as any of its three things. */
    public boolean touches(final ResourceKey key) {
        return filled.equals(key) || fluid.equals(key) || key.equals(empty);
    }

    @Override
    public boolean equals(final Object o) {
        return o instanceof final CannerRecipe r && r.filled.equals(filled)
            && Objects.equals(r.empty, empty)
            && r.fluid.equals(fluid)
            && Double.compare(r.litres, litres) == 0
            && r.fills == fills;
    }

    @Override
    public int hashCode() {
        return Objects.hash(filled, empty, fluid, Double.hashCode(litres), fills);
    }

    @Override
    public String toString() {
        return (fills ? "fill " : "empty ") + filled + " / " + empty + " / " + fluid;
    }
}
