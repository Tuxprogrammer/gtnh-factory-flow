package com.gtnhplanner.core.model;

import java.util.Locale;

import javax.annotation.Nullable;

/**
 * The resource keys the mod's saves carry, as they always have: {@code item:<registry name>:<meta>} (the registry's own
 * case, meta always written), {@code fluid:<name>}, {@code power:eu}. A card's pick among its slot's items, a drawer's
 * resource, a fluid tank's cell and a custom rate's resource are saved this way, so their bytes stay as they were; this
 * reads them as the shared {@link ResourceKey}.
 */
public final class SavedKeys {

    private SavedKeys() {}

    /** The shared key a saved key names; null for a key of no kind the plan knows. */
    @Nullable
    public static ResourceKey resource(@Nullable final String saved) {
        if (saved == null) return null;
        if (saved.equals("power:eu")) return ResourceKey.EU;
        if (saved.startsWith("fluid:")) return ResourceKey.fluid(
            saved.substring("fluid:".length())
                .toLowerCase(Locale.ROOT));
        if (!saved.startsWith("item:")) return null;
        final int last = saved.lastIndexOf(':');
        if (last <= "item:".length()) return null;
        int meta = 0;
        try {
            meta = Integer.parseInt(saved.substring(last + 1));
        } catch (final NumberFormatException ignored) {
            // A key with no number after its last colon is the damage-0 item, as the mod has always read one.
        }
        return ResourceKey.item(itemId(saved.substring("item:".length(), last), meta));
    }

    /** The shared id of an item: its registry name lowercased, with {@code @meta} only when the meta is not 0. */
    public static String itemId(final String registryName, final int meta) {
        final String name = registryName.toLowerCase(Locale.ROOT);
        return meta == 0 ? name : name + "@" + meta;
    }
}
