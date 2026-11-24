package com.gtnhplanner.read.gt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The order the export reads a GregTech map's recipes in, which is the order it renders their icons in: by first
 * output (registry name, damage and size), then EU/t, then time, the map's own order breaking ties.
 * <p>
 * The icon renderer names an item stack's picture by the stack's NBT text, and gives every stack of equal content the
 * name it gave the first one it saw. Two stacks of one content can print their tags in different key orders (a data
 * orb whose title and name share a hash bucket prints them in the order they were set), so which stack comes first
 * decides the file name, and stored plans hold those names. The served dataset's export read in this order; the
 * export may list the recipes in another, but it reads them in this one.
 */
public final class GtIconOrder {

    /** What the order reads off a recipe. */
    public interface Keys<T> {

        /** The first output's {@code registry@damage x size}, or the first fluid output's name, or "". */
        String firstOutput(T recipe);

        long eut(T recipe);

        int duration(T recipe);
    }

    private GtIconOrder() {}

    /** {@code recipes} in read order: a stable sort, so equal keys keep the map's order. */
    public static <T> List<T> sorted(final Collection<T> recipes, final Keys<T> keys) {
        final List<T> out = new ArrayList<T>(recipes);
        Collections.sort(out, new Comparator<T>() {

            @Override
            public int compare(final T left, final T right) {
                final int byOutput = keys.firstOutput(left)
                    .compareTo(keys.firstOutput(right));
                if (byOutput != 0) return byOutput;
                final int byEut = Long.compare(keys.eut(left), keys.eut(right));
                if (byEut != 0) return byEut;
                return Integer.compare(keys.duration(left), keys.duration(right));
            }
        });
        return out;
    }
}
