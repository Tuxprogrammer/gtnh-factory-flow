package com.gtnhplanner.core.model;

import java.util.Objects;

import javax.annotation.Nullable;

/**
 * What flows through a plan, identified by data: its kind and its id, exactly as the shared plan JSON carries them
 * (the website's {@code ResourceKey}, {@code "<kind>:<id>"}). An item's id is its registry name with {@code @meta}
 * when the meta is not 0 ({@code gregtech:gt.metaitem.01@2377}); {@code @32767} is the any-damage wildcard; an ore
 * dictionary slot is {@code oredict:<name>}; a fluid is its fluid name; EU is {@code power:eu}. Nothing else
 * distinguishes two resources in a plan: a stack's NBT does not travel in the format, and the old wiring rule never
 * looked at it either.
 *
 * <p>
 * Ids compare exactly, as the website compares them. A host spells its own resources the shared way when it makes a
 * key (the mod lowercases a registry name and drops {@code @0}, as its importer always has); everything else about a
 * resource (its name, icon, containers) is asked of the host's {@link ResourceRegistry}.
 */
public final class ResourceKey implements Comparable<ResourceKey> {

    /** The kinds the plan JSON knows. Kinds never mix: a filled cell is an item and never its fluid. */
    public enum Kind {

        ITEM("item"),
        FLUID("fluid"),
        ASPECT("aspect"),
        POWER("power");

        private final String json;

        Kind(final String json) {
            this.json = json;
        }

        /** The kind as the plan JSON spells it. */
        public String json() {
            return json;
        }

        /** The kind the plan JSON spells {@code json}; null for any other word. */
        @Nullable
        public static Kind of(@Nullable final String json) {
            if (json == null) return null;
            for (final Kind k : values()) if (k.json.equals(json)) return k;
            return null;
        }
    }

    /** Minecraft's any-damage meta, as an id suffix: {@code minecraft:log@32767} is any log. */
    public static final String ANY_DAMAGE = "@32767";
    private static final String OREDICT = "oredict:";

    /** EU: a generator's output. */
    public static final ResourceKey EU = new ResourceKey(Kind.POWER, "eu");

    private final Kind kind;
    private final String id;

    private ResourceKey(final Kind kind, final String id) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.id = Objects.requireNonNull(id, "id");
    }

    public static ResourceKey of(final Kind kind, final String id) {
        return new ResourceKey(kind, id);
    }

    public static ResourceKey item(final String id) {
        return new ResourceKey(Kind.ITEM, id);
    }

    public static ResourceKey fluid(final String id) {
        return new ResourceKey(Kind.FLUID, id);
    }

    /** The key {@link #toString()} spelled ({@code item:minecraft:log@32767}); null when it names no kind. */
    @Nullable
    public static ResourceKey parse(@Nullable final String key) {
        if (key == null) return null;
        final int colon = key.indexOf(':');
        if (colon <= 0) return null;
        final Kind kind = Kind.of(key.substring(0, colon));
        return kind == null ? null : new ResourceKey(kind, key.substring(colon + 1));
    }

    public Kind kind() {
        return kind;
    }

    public String id() {
        return id;
    }

    /** An ore dictionary slot ({@code oredict:<name>}): it stands for every item of that name. */
    public boolean isOreDictionary() {
        return id.startsWith(OREDICT);
    }

    /** The ore dictionary name an {@code oredict:} key stands for; null for any other key. */
    @Nullable
    public String oreName() {
        return isOreDictionary() ? id.substring(OREDICT.length()) : null;
    }

    /** An any-damage item ({@code @32767}): not an item anyone holds, a stand-in for every damage of one. */
    public boolean isWildcard() {
        return kind == Kind.ITEM && id.endsWith(ANY_DAMAGE);
    }

    /**
     * Whether the two keys name the same resource as a slot reads them: the same kind, and the same id, or for an item
     * one of them the other's any-damage wildcard ({@code minecraft:log@32767} is {@code minecraft:log@1}; a bare id
     * is the damage-0 item). The website's {@code itemIdsMatch}.
     */
    public boolean matches(final ResourceKey other) {
        if (kind != other.kind) return false;
        if (id.equals(other.id)) return true;
        return kind == Kind.ITEM && (anyDamage(id, other.id) || anyDamage(other.id, id));
    }

    private static boolean anyDamage(final String wildcard, final String concrete) {
        if (!wildcard.endsWith(ANY_DAMAGE)) return false;
        final String base = wildcard.substring(0, wildcard.length() - ANY_DAMAGE.length());
        // Anchored on "@": minecraft:log@32767 is not minecraft:log2@1.
        return concrete.equals(base) || concrete.startsWith(base + "@");
    }

    /** The plan JSON's spelling, {@code <kind>:<id>}. */
    @Override
    public String toString() {
        return kind.json + ":" + id;
    }

    @Override
    public boolean equals(final Object o) {
        return o instanceof final ResourceKey k && k.kind == kind && k.id.equals(id);
    }

    @Override
    public int hashCode() {
        return 31 * kind.ordinal() + id.hashCode();
    }

    /** Kind, then id: a fixed order for lists a player reads and files a test compares. */
    @Override
    public int compareTo(final ResourceKey o) {
        final int byKind = Integer.compare(kind.ordinal(), o.kind.ordinal());
        return byKind != 0 ? byKind : id.compareTo(o.id);
    }
}
