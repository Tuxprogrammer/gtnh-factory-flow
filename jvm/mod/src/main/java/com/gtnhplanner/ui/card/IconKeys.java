package com.gtnhplanner.ui.card;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

/**
 * The mod's icon keys for the core's card records (core-view's {@code CardModel}, {@code SettingControls.Control}):
 * {@code item:<registry name>:<meta>} as the board keys resources ({@link CardDefaults#itemKey}), with {@code #} and
 * the NBT's hash for a stack whose NBT is part of its look (a tool's material), and {@code fluid:<name>}. The stack a
 * key was made from is kept, so drawing a key draws that very stack: nothing is looked up again or drawn differently.
 * Client thread only, as everything that draws.
 */
public final class IconKeys {

    private static final Map<String, ItemStack> STACKS = new HashMap<>();

    private IconKeys() {}

    /** A stack's key, remembering the stack for {@link #stack}; null for none. */
    @Nullable
    public static String of(@Nullable final ItemStack stack) {
        if (stack == null || stack.getItem() == null) return null;
        final String key = stack.hasTagCompound() ? CardDefaults.itemKey(stack) + "#"
            + stack.getTagCompound()
                .toString()
                .hashCode()
            : CardDefaults.itemKey(stack);
        STACKS.put(key, stack);
        return key;
    }

    /** A fluid's key; null for none. */
    @Nullable
    public static String of(@Nullable final FluidStack fluid) {
        return fluid == null || fluid.getFluid() == null ? null
            : "fluid:" + fluid.getFluid()
                .getName();
    }

    /** The stack a key was made from (by {@link #of(ItemStack)}), else the item it names; null for none. */
    @Nullable
    public static ItemStack stack(@Nullable final String key) {
        if (key == null) return null;
        final ItemStack kept = STACKS.get(key);
        return kept != null ? kept : com.gtnhplanner.ui.Resources.item(key);
    }
}
