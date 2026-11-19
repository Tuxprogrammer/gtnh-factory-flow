package com.gtnhplanner.nei;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

import com.gtnhplanner.data.flowchart.Port;

import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.IRecipeHandler;

/**
 * A recipe's slot lists as NEI shows them: what each item input's slot takes in its place. They are the recipe's own
 * list, and the only thing a slot takes besides its own item ({@code core.model.Wiring}).
 */
public final class SlotAlternatives {

    private SlotAlternatives() {}

    /**
     * Each item input's alternatives: every item NEI shows in the slot the input's item is in (an ore dictionary
     * slot's members, GregTech's alternatives), the port's own first; no other is a wildcard.
     */
    public static void read(final List<Port<?>> inputs, final IRecipeHandler handler, final int recipeIndex) {
        final List<PositionedStack> slots;
        try {
            slots = handler.getIngredientStacks(recipeIndex);
        } catch (final RuntimeException e) {
            return;
        }
        if (slots == null) return;
        for (final Port<?> port : inputs) {
            if (!(port.getValue() instanceof final ItemStack own) || own.getItem() == null) continue;
            for (final PositionedStack slot : slots) {
                if (slot == null || slot.items == null || slot.items.length < 2 || !holds(slot, own)) continue;
                final List<ItemStack> items = new ArrayList<>();
                items.add(own.copy());
                for (final ItemStack s : slot.items) {
                    if (s == null || s.getItem() == null || s.getItemDamage() == OreDictionary.WILDCARD_VALUE) continue;
                    boolean seen = false;
                    for (final ItemStack had : items) seen |= had.getItem() == s.getItem()
                        && had.getItemDamage() == s.getItemDamage();
                    if (!seen) items.add(s.copy());
                }
                if (items.size() > 1) set(port, items);
                break;
            }
        }
    }

    /**
     * Whether a slot shows the item among its permutations. A wildcard on either side holds every meta: GregTech's
     * recipe keeps an ore slot's wildcard log (the Industrial Coke Oven's) while NEI lists each log.
     */
    private static boolean holds(final PositionedStack slot, final ItemStack item) {
        final int wildcard = OreDictionary.WILDCARD_VALUE;
        for (final ItemStack s : slot.items) {
            if (s == null || s.getItem() != item.getItem()) continue;
            if (s.getItemDamage() == item.getItemDamage() || s.getItemDamage() == wildcard
                || item.getItemDamage() == wildcard) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static void set(final Port<?> port, final List<ItemStack> items) {
        ((Port<ItemStack>) port).setAlternatives(items);
    }
}
