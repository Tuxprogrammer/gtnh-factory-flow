package com.gtnhplanner.core.model;

import java.util.List;

/**
 * Which wires a plan allows: an output feeds an input when the input's recipe slot takes what the output carries. A
 * slot takes its own resource and the ones that recipe lists for it in its place (an ore dictionary slot's members,
 * GregTech's alternative fluids), and nothing else: two items that only share an ore dictionary name do not stand in
 * for each other, because in the game a recipe accepts what it lists and no more. Kinds never mix (a filled cell never
 * feeds its fluid's slot: it goes through a fluid tank).
 *
 * <p>
 * The website's {@code resourceMatchesInput} in Java, case for case ({@code conformance/model/wiring.json} holds both
 * to it). The same rule decides which ports the solver may join through a buffer drawer.
 */
public final class Wiring {

    private Wiring() {}

    /**
     * Whether a slot holding {@code slot}, which lists {@code alternatives} in its place, takes {@code offered}. The
     * alternatives are the slot's own list (the recipe's), empty when it takes one thing only; a slot set to one of them
     * ({@code slot} is the pick) takes the others still, as the recipe does.
     */
    public static boolean accepts(final ResourceKey slot, final List<ResourceKey> alternatives,
        final ResourceKey offered) {
        if (slot.kind() != offered.kind()) return false;
        if (slot.matches(offered)) return true;
        for (final ResourceKey alternative : alternatives) if (alternative.matches(offered)) return true;
        return false;
    }
}
