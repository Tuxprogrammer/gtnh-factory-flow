package com.gtnhplanner.read.furnace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.FurnaceRecipes;

import com.gtnhplanner.core.model.read.GtRecipeRecord;
import com.gtnhplanner.core.model.read.SmeltingRecord;
import com.gtnhplanner.read.gt.GtRecipeReader;

/**
 * The one reader from a vanilla furnace recipe to {@link SmeltingRecord}, for both hosts: the export run (which
 * compiles this package into its own jar) reads every furnace recipe with it, and the mod reads a furnace card's recipe
 * with it. Written for Java 8's library, like the GregTech reader beside it.
 */
public final class SmeltingReader {

    private SmeltingReader() {}

    /** One recipe: {@code input} (the furnace list's key) smelts into {@code output}; null when either is empty. */
    public static SmeltingRecord read(final ItemStack input, final ItemStack output) {
        final GtRecipeRecord.Stack in = GtRecipeReader.itemStack(input);
        final GtRecipeRecord.Stack out = GtRecipeReader.itemStack(output);
        if (in == null || out == null) return null;
        final SmeltingRecord r = new SmeltingRecord();
        r.input = in;
        r.output = out;
        return r;
    }

    /**
     * Every furnace recipe, sorted by the stacks' content. FurnaceRecipes keeps them in a HashMap keyed by ItemStack,
     * which hashes by identity, so its own order is new every run.
     */
    public static List<SmeltingRecord> readAll() {
        final List<SmeltingRecord> out = new ArrayList<SmeltingRecord>();
        for (final Map.Entry<?, ?> entry : smeltingList().entrySet()) {
            if (!(entry.getKey() instanceof ItemStack) || !(entry.getValue() instanceof ItemStack)) continue;
            final SmeltingRecord r = read((ItemStack) entry.getKey(), (ItemStack) entry.getValue());
            if (r != null) out.add(r);
        }
        Collections.sort(out, BY_CONTENT);
        return out;
    }

    /**
     * The recipe the furnace runs for {@code shown} (a stack NEI shows as the ingredient), found the way the furnace
     * finds it (FurnaceRecipes.getSmeltingResult: the same item, and the key's damage or any damage); null when none.
     */
    public static SmeltingRecord forInput(final ItemStack shown) {
        if (shown == null || shown.getItem() == null) return null;
        for (final Map.Entry<?, ?> entry : smeltingList().entrySet()) {
            if (!(entry.getKey() instanceof ItemStack) || !(entry.getValue() instanceof ItemStack)) continue;
            final ItemStack key = (ItemStack) entry.getKey();
            if (key.getItem() != shown.getItem()) continue;
            if (key.getItemDamage() != 32767 && key.getItemDamage() != shown.getItemDamage()) continue;
            return read(key, (ItemStack) entry.getValue());
        }
        return null;
    }

    private static Map<?, ?> smeltingList() {
        return FurnaceRecipes.smelting()
            .getSmeltingList();
    }

    /** A slot's content as text: registry name, damage, size, NBT. */
    public static String contentKey(final GtRecipeRecord.Stack s) {
        return s.registryId + "@" + s.meta + "x" + s.amount + (s.nbt == null ? "" : s.nbt);
    }

    private static final Comparator<SmeltingRecord> BY_CONTENT = new Comparator<SmeltingRecord>() {

        @Override
        public int compare(final SmeltingRecord left, final SmeltingRecord right) {
            final int byInput = contentKey(left.input).compareTo(contentKey(right.input));
            return byInput != 0 ? byInput : contentKey(left.output).compareTo(contentKey(right.output));
        }
    };
}
