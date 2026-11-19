package com.gtnhplanner.core.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * What a fluid tank card can do with a container, decided by GregTech's Canner recipes, as the website's Tank map is:
 * how many litres a container holds, which containers a fluid can be filled into, and which ones can be emptied. A
 * tank fills a container only where the Canner has that filling recipe and empties one only where it has that emptying
 * recipe, so the five platinum-group molten metals the game never empties (GregTech registers no emptying recipe for
 * them) fill but never empty, on both hosts. Every container the game lists is filled in the Canner, buckets and
 * capsules as well as cells.
 *
 * <p>
 * A host hands the recipes in through {@link ResourceRegistry#canner}: the dataset's Tank map on the website,
 * GregTech's Canner map in the mod. A host with only Forge's container list builds them with
 * {@link #fromContainers}, GregTech's own rule for turning that list into Canner recipes.
 */
public final class FluidContainers {

    private FluidContainers() {}

    /**
     * The molten metals GregTech registers a filling Canner recipe for and never an emptying one
     * ({@code GTPostLoad.registerFluidCannerRecipes}): Ruthenium, Rhodium, Osmium, Iridium, Platinum.
     */
    public static final Set<ResourceKey> NEVER_EMPTIED = Set.of(
        ResourceKey.fluid("molten.ruthenium"),
        ResourceKey.fluid("molten.rhodium"),
        ResourceKey.fluid("molten.osmium"),
        ResourceKey.fluid("molten.iridium"),
        ResourceKey.fluid("molten.platinum"));

    /** IC2's Empty Cell, which GregTech fills with every fluid it has: the container a tank picks first. */
    public static final ResourceKey PLAIN_CELL = ResourceKey.item("ic2:itemcellempty");

    /** A filled container, the fluid it holds ({@code litres} of it) and the container it leaves. */
    public record Pair(ResourceKey cell, ResourceKey fluid, double litres, ResourceKey empty) {

        @Override
        public boolean equals(final Object o) {
            return o instanceof final Pair p && p.cell.equals(cell)
                && p.fluid.equals(fluid)
                && Double.compare(p.litres, litres) == 0
                && p.empty.equals(empty);
        }

        @Override
        public int hashCode() {
            return (cell.hashCode() * 31 + fluid.hashCode()) * 31 + empty.hashCode();
        }

        @Override
        public String toString() {
            return cell + " / " + fluid + " / " + empty;
        }
    }

    /** What a tank does with its pair: empties the cell ({@code emptying}) or fills it. */
    public record Hold(boolean emptying, Pair pair) {

        @Override
        public boolean equals(final Object o) {
            return o instanceof final Hold h && h.emptying == emptying && h.pair.equals(pair);
        }

        @Override
        public int hashCode() {
            return pair.hashCode() * 2 + (emptying ? 1 : 0);
        }
    }

    /**
     * The pair a filled container makes a tank handle in a direction ({@code emptying}): the Canner recipe that does
     * it, null when there is none (an item that holds no fluid, a container the Canner fills but never empties).
     */
    @Nullable
    public static Pair pair(final ResourceKey cell, final boolean emptying, final ResourceRegistry registry) {
        if (cell.kind() != ResourceKey.Kind.ITEM) return null;
        for (final CannerRecipe r : registry.canner(cell)) {
            if (r.fills() != !emptying || !r.filled()
                .equals(cell) || r.empty() == null || !(r.litres() > 0)) continue;
            return new Pair(r.filled(), r.fluid(), r.litres(), r.empty());
        }
        return null;
    }

    /**
     * What a tank does when {@code resource} is wired to it on a side ({@code supply}: the tank gives the resource out,
     * else takes it in). A fluid asked of it comes out of containers (emptying); a fluid given to it goes into them
     * (filling); a container given to it is emptied; a container asked of it is filled. Of the containers that do, the
     * plain Empty Cell's first, then any cell's, then the first the Canner lists. Null when nothing does: the drop is
     * refused, never guessed.
     */
    @Nullable
    public static Hold take(final ResourceKey resource, final boolean supply, final ResourceRegistry registry) {
        if (resource.kind() == ResourceKey.Kind.FLUID) {
            final boolean emptying = supply;
            final List<CannerRecipe> fits = new ArrayList<>();
            for (final CannerRecipe r : registry.canner(resource)) {
                if (r.fills() == emptying || !r.fluid()
                    .equals(resource) || r.empty() == null || !(r.litres() > 0)) continue;
                fits.add(r);
            }
            final CannerRecipe pick = best(fits, registry);
            return pick == null ? null
                : new Hold(emptying, new Pair(pick.filled(), pick.fluid(), pick.litres(), pick.empty()));
        }
        // A container given to it is emptied; one asked of it is filled.
        final boolean emptying = !supply;
        final Pair pair = pair(resource, emptying, registry);
        return pair == null ? null : new Hold(emptying, pair);
    }

    /** The plain Empty Cell's recipe, else a cell's (its empty container's name says so), else the first. */
    @Nullable
    private static CannerRecipe best(final List<CannerRecipe> fits, final ResourceRegistry registry) {
        if (fits.isEmpty()) return null;
        for (final CannerRecipe r : fits) if (PLAIN_CELL.equals(r.empty())) return r;
        for (final CannerRecipe r : fits) {
            if (registry.displayName(r.empty())
                .toLowerCase(Locale.ROOT)
                .contains("cell")) return r;
        }
        return fits.get(0);
    }

    /** One entry of Forge's fluid container list: a filled container, its fluid and litres, and its empty one. */
    public record Container(ResourceKey filled, ResourceKey empty, boolean leavesEmpty, ResourceKey fluid,
        double litres) {}

    /**
     * GregTech's rule for Forge's container list ({@code GTPostLoad.registerFluidCannerRecipes}): every entry with
     * some fluid in it becomes a filling recipe, and an emptying one too unless its fluid is {@linkplain #NEVER_EMPTIED
     * never emptied}; an emptying recipe gives the empty container back only when the entry has one to give.
     */
    public static List<CannerRecipe> fromContainers(final List<Container> containers) {
        final List<CannerRecipe> out = new ArrayList<>();
        for (final Container c : containers) {
            if (!(c.litres() > 0)) continue;
            out.add(new CannerRecipe(c.filled(), c.empty(), c.fluid(), c.litres(), true));
            if (NEVER_EMPTIED.contains(c.fluid())) continue;
            out.add(new CannerRecipe(c.filled(), c.leavesEmpty() ? c.empty() : null, c.fluid(), c.litres(), false));
        }
        return out;
    }
}
