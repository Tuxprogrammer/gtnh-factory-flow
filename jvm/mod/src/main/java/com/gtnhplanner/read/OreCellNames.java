package com.gtnhplanner.read;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which ore dictionary names a recipe cell is. Forge hands a ShapedOreRecipe (and Thaumcraft its recipes) a name's
 * item list, not the name, so a list is every name whose items are exactly those items, and the cell is named by the
 * first of them ({@code oredict:<first>}): that is the id a stored plan holds. "First" is the ore dictionary's own
 * order, {@code OreDictionary.getOreNames()}, which is the order the names were first registered. It is never sorted:
 * a sort renames cells plans hold ({@code oreCopper} to {@code oreAnyCopper}, {@code quicksilver} to
 * {@code gemMercury}). Where a mod's registration order moves between launches, the name moves with the game.
 * Plain Java, shared by the crafting reader and the export run.
 */
public final class OreCellNames {

    private OreCellNames() {}

    /** A name's item list as text: equal for names that hold the same items, "" for a name that holds none. */
    public interface Signatures {

        String of(String name);
    }

    /**
     * The names by the signature of their item list, each list in {@code namesInIdOrder}'s order (pass
     * {@code OreDictionary.getOreNames()}); names that hold no item are left out.
     */
    public static Map<String, List<String>> bySignature(final String[] namesInIdOrder, final Signatures signatures) {
        final Map<String, List<String>> out = new HashMap<String, List<String>>();
        for (final String name : namesInIdOrder) {
            final String signature = signatures.of(name);
            if (signature == null || signature.length() == 0) continue;
            List<String> names = out.get(signature);
            if (names == null) {
                names = new ArrayList<String>();
                out.put(signature, names);
            }
            names.add(name);
        }
        return out;
    }
}
