package com.gtnhplanner.read;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

/**
 * An ore dictionary name's items the way the readers list them: the game's own order (the order the items were
 * registered under the name), except for the names GregTech registers from a HashMap. GlassTier's
 * {@code registerGlassOreDicts} walks a HashMap keyed by block and damage, so {@code blockGlassHV} ...
 * {@code blockGlassUXV} come out in a new order every run; those are sorted by registry name and damage. Written for
 * Java 8's library: the export run compiles it too.
 */
public final class OreLists {

    private OreLists() {}

    /** The names GlassTier registers: "blockGlass" and a voltage tier's name (GTValues.VN). */
    private static final String[] GLASS_TIERS = { "ULV", "LV", "MV", "HV", "EV", "IV", "LuV", "ZPM", "UV", "UHV", "UEV",
        "UIV", "UMV", "UXV", "MAX" };

    /** {@code name}'s items, in the order the readers write them. */
    public static List<ItemStack> of(final String name) {
        final List<ItemStack> ores = new ArrayList<ItemStack>(OreDictionary.getOres(name));
        if (hashOrdered(name)) Collections.sort(ores, BY_REGISTRY_NAME);
        return ores;
    }

    /**
     * The ore dictionary's names in one fixed order: by name. Their ids follow registration, which is not fixed. For
     * listing the dictionary only, never for naming a cell: that takes the game's order ({@link OreCellNames}).
     */
    public static List<String> names() {
        final List<String> names = new ArrayList<String>();
        for (final String name : OreDictionary.getOreNames()) names.add(name);
        Collections.sort(names);
        return names;
    }

    private static boolean hashOrdered(final String name) {
        if (!name.startsWith("blockGlass")) return false;
        final String tier = name.substring("blockGlass".length());
        for (final String t : GLASS_TIERS) if (t.equals(tier)) return true;
        return false;
    }

    private static final Comparator<ItemStack> BY_REGISTRY_NAME = new Comparator<ItemStack>() {

        @Override
        public int compare(final ItemStack left, final ItemStack right) {
            final int byName = name(left).compareTo(name(right));
            return byName != 0 ? byName : Integer.compare(left.getItemDamage(), right.getItemDamage());
        }
    };

    private static String name(final ItemStack stack) {
        final Item item = stack == null ? null : stack.getItem();
        return item == null ? "" : String.valueOf(Item.itemRegistry.getNameForObject(item));
    }
}
