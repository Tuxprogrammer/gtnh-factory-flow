package com.gtnhplanner.pattern;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nullable;

/**
 * Which products go in a pattern, without the game. A product is a resource key ({@code item:<name>:<meta>},
 * {@code fluid:<name>}). Byproducts in a pattern make AE2 reach for odd recipes to get them, so a pattern holds only
 * the
 * recipe's first product unless the player picks more. Inputs are never touched: a product left out is simply not among
 * the pattern's outputs, as if its output slot had been cleared in the terminal before encoding.
 */
public final class PatternOutputs {

    private PatternOutputs() {}

    /**
     * The products a pattern can hold, each once: the keys of the packed outputs, in the order the card shows its
     * products ({@code cardKeys}); a packed output the card does not show comes after them, in packed order. Empty keys
     * (an output nothing could read) are left out.
     */
    public static List<String> products(final List<String> packedKeys, final List<String> cardKeys) {
        final LinkedHashSet<String> packed = new LinkedHashSet<>();
        for (final String k : packedKeys) if (k != null && !k.isEmpty()) packed.add(k);
        final List<String> out = new ArrayList<>(packed.size());
        for (final String k : cardKeys) if (packed.remove(k) && !out.contains(k)) out.add(k);
        out.addAll(packed);
        return out;
    }

    /** Whether the player is asked which products to include: only when there is more than one to choose from. */
    public static boolean asks(final List<String> products) {
        return products.size() > 1;
    }

    /** A pattern needs at least one product: the Make key is off while none is included. */
    public static boolean canMake(final Collection<String> included) {
        return !included.isEmpty();
    }

    /**
     * The products included: the remembered choice, as far as it still names products of the recipe, in product order;
     * else (never chosen, or nothing of it left) the first product alone.
     */
    public static List<String> chosen(final List<String> products, @Nullable final Collection<String> remembered) {
        final List<String> out = new ArrayList<>();
        if (remembered != null) for (final String p : products) if (remembered.contains(p)) out.add(p);
        if (out.isEmpty() && !products.isEmpty()) out.add(products.get(0));
        return out;
    }

    /**
     * {@code included} with {@code product} switched, in product order. It may come out empty (see {@link #canMake}).
     */
    public static List<String> toggle(final List<String> products, final Collection<String> included,
        final String product) {
        final List<String> out = new ArrayList<>();
        for (final String p : products) if (p.equals(product) != included.contains(p)) out.add(p);
        return out;
    }

    /**
     * The packed output slots that stay: each one whose product is included. When none is (nothing of the choice could
     * be read back), the first slot stays, so a pattern is never made with no output.
     */
    public static List<Integer> keep(final List<String> packedKeys, final Collection<String> included) {
        final List<Integer> out = new ArrayList<>();
        for (int i = 0; i < packedKeys.size(); i++) if (included.contains(packedKeys.get(i))) out.add(i);
        if (out.isEmpty() && !packedKeys.isEmpty()) out.add(0);
        return out;
    }

    /**
     * The choices remembered per plan and card, for the life of the plan in the mod: kept beside the plans, never in a
     * plan, so nothing the website reads or writes changes. Plan ids are {@code Graph.planId()}; card ids the node's.
     */
    public static final class Choices {

        private final Map<String, Map<String, List<String>>> byPlan = new TreeMap<>();

        /** The card's remembered products; null when it was never chosen. {@code plans} are the plan's ids. */
        @Nullable
        public List<String> get(final Collection<String> plans, final String card) {
            for (final String plan : plans) {
                final Map<String, List<String>> cards = byPlan.get(plan);
                final List<String> got = cards == null ? null : cards.get(card);
                if (got != null) return got;
            }
            return null;
        }

        /** Remembers the card's products; an empty choice is never kept. */
        public void put(final String plan, final String card, final List<String> products) {
            if (products.isEmpty()) return;
            byPlan.computeIfAbsent(plan, p -> new TreeMap<>())
                .put(card, List.copyOf(products));
        }

        /** Every plan, card and choice, for saving. */
        public Map<String, Map<String, List<String>>> all() {
            return byPlan;
        }
    }
}
