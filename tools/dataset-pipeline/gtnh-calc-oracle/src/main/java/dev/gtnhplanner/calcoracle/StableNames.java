package dev.gtnhplanner.calcoracle;

import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

/**
 * Items whose name the game rolls at random on the client, as a joke, and rolls again a second later: two exports
 * would never agree on them. The export pins each to one of its own names.
 * <ul>
 * <li>Extra Utilities' QED (ItemBlockQED.getUnlocalizedName: one of 17 names from XURandom, re-rolled after a second):
 * pinned to {@code tile.extrautils:qed.rand.0}.</li>
 * <li>GT New Horizons' Quantum Bread (QuantumBread.getItemStackDisplayName: "Quantum Bread" or "Quantum Sliced Bread"
 * from a time-seeded Random, re-rolled after 250 ms): pinned to {@code item.QuantumBread_1}, "Quantum Bread", the
 * item's own name.</li>
 * <li>GT New Horizons' oven gloves (OvenGlove.getItemStackDisplayName: Pizza, Cake, Lava or BBQ Glove, re-rolled after
 * a second): pinned to the first, {@code item.OvenGlove_<hand>_0}, "Pizza Glove".</li>
 * </ul>
 * These are every item in the pack's source that rolls its name this way.
 */
public final class StableNames {

    private static final String QED_CLASS = "com.rwtema.extrautils.item.ItemBlockQED";
    private static final String QUANTUM_BREAD_CLASS = "com.dreammaster.item.food.QuantumBread";
    private static final String OVEN_GLOVE_CLASS = "com.dreammaster.item.baubles.OvenGlove";

    private StableNames() {
    }

    /** The name the export writes for {@code stack}: {@code read}, unless the game rolls this item's name. */
    public static String of(ItemStack stack, String read) {
        String key = pinnedKey(stack);
        if (key == null) {
            return read;
        }
        String pinned = StatCollector.translateToLocal(key);
        return pinned == null || pinned.length() == 0 || pinned.equals(key) ? read : pinned;
    }

    private static String pinnedKey(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return null;
        }
        String type = stack.getItem().getClass().getName();
        if (QED_CLASS.equals(type) && stack.getItemDamage() == 0) {
            return "tile.extrautils:qed.rand.0.name";
        }
        if (QUANTUM_BREAD_CLASS.equals(type)) {
            return "item.QuantumBread_1.name";
        }
        if (OVEN_GLOVE_CLASS.equals(type)) {
            return "item.OvenGlove_" + stack.getItemDamage() + "_0.name";
        }
        return null;
    }
}
