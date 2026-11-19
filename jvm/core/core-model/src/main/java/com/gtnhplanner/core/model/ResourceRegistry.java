package com.gtnhplanner.core.model;

import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

import com.gtnhplanner.data.flowchart.Port;

/**
 * Everything about a resource that is not its {@link ResourceKey}, asked of the host that knows it: the game (NEI,
 * Forge, the ore dictionary, GregTech's Canner) in the mod, the dataset on the website. The model and the views ask
 * here and never hold a game object for a resource. Which resources a recipe slot takes in its place is the slot's own
 * list (the recipe's, read with it), not a question for the registry; {@link Wiring} decides a wire from that list.
 *
 * <p>
 * One host, installed once at start-up before any plan is read ({@link #install}); until then {@link #NONE}, which
 * knows nothing: names are the keys' own text, nothing has an icon, a colour, an ore name or a container.
 */
public interface ResourceRegistry {

    /** What a player reads for the resource; the key's own text when the host does not know it. */
    String displayName(ResourceKey key);

    /**
     * The host's icon key for the resource (the mod's {@code item:<registry>:<meta>} or {@code fluid:<name>}, the
     * website's icon path), which only that host's renderer resolves; null when it has none.
     */
    @Nullable
    String icon(ResourceKey key);

    /** The resource's representative colour as bare RGB, -1 when it has none. */
    int color(ResourceKey key);

    /**
     * The ore dictionary names an item carries, without the {@code oredict:} prefix, for search and for naming an ore
     * slot. Never a reason to wire two items: a slot takes what its recipe lists ({@link Wiring}).
     */
    List<String> oreNames(ResourceKey key);

    /** The items an ore dictionary key ({@code oredict:<name>}) stands for, in the host's order; empty for any other. */
    List<ResourceKey> members(ResourceKey oreDictionary);

    /**
     * Every Canner recipe that fills or empties a container with a fluid and has {@code key} in it (as the filled
     * container, the empty one or the fluid), in the host's order. {@link FluidContainers} decides from these what a
     * fluid tank does.
     */
    List<CannerRecipe> canner(ResourceKey key);

    /**
     * A port carrying {@code perCraft} of the resource a craft, exactly (its whole amount that rounded, at least one),
     * holding the host's own value for it: how a card that is not a recipe (a custom rate, a fluid tank) gets its ports.
     * Null when the host has no such resource.
     */
    @Nullable
    Port<?> port(ResourceKey key, double perCraft);

    /** The installed registry. */
    static ResourceRegistry get() {
        return Holder.registry;
    }

    /** Installs the host's own. */
    static void install(final ResourceRegistry registry) {
        Holder.registry = Objects.requireNonNull(registry, "registry");
    }

    /** A registry that knows nothing (the default until a host installs its own). */
    ResourceRegistry NONE = new ResourceRegistry() {

        @Override
        public String displayName(final ResourceKey key) {
            return key.id();
        }

        @Nullable
        @Override
        public String icon(final ResourceKey key) {
            return null;
        }

        @Override
        public int color(final ResourceKey key) {
            return -1;
        }

        @Override
        public List<String> oreNames(final ResourceKey key) {
            return List.of();
        }

        @Override
        public List<ResourceKey> members(final ResourceKey oreDictionary) {
            return List.of();
        }

        @Override
        public List<CannerRecipe> canner(final ResourceKey key) {
            return List.of();
        }

        @Nullable
        @Override
        public Port<?> port(final ResourceKey key, final double perCraft) {
            return null;
        }
    };

    /** Where the installed registry is kept. */
    final class Holder {

        static ResourceRegistry registry = NONE;

        private Holder() {}
    }
}
