package com.gtnhplanner.read.crafting;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.ShapedRecipes;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.oredict.OreDictionary;
import net.minecraftforge.oredict.ShapedOreRecipe;
import net.minecraftforge.oredict.ShapelessOreRecipe;

import com.gtnhplanner.core.model.read.CraftingRecord;
import com.gtnhplanner.core.model.read.CraftingRecord.Slot;
import com.gtnhplanner.core.model.read.GtRecipeRecord;
import com.gtnhplanner.read.OreLists;
import com.gtnhplanner.read.gt.GtRecipeReader;

/**
 * The one reader from a crafting-grid recipe (an {@code IRecipe}) to {@link CraftingRecord}, for both hosts: the
 * export run (which compiles this package into its own jar) reads every CraftingManager recipe with it, and the mod
 * reads a crafting card's recipe with it. Recipe classes that keep their ingredients where Forge's do not are read by
 * {@link CraftingIngredients}.
 *
 * <p>
 * Written for Java 8's library, like the GregTech reader beside it. Not thread-safe; one reader per thread.
 */
public final class CraftingReader {

    /** Ore dictionary names by the sorted content of their item lists, built on first use. */
    private Map<String, List<String>> oreNamesBySignature;
    /** CraftingManager's recipes by output item, built on first use (the mod's card lookup). */
    private Map<Item, List<IRecipe>> recipesByOutput;

    /** One recipe as the game holds it; null when the recipe makes nothing. */
    public CraftingRecord read(final Object raw) {
        if (!(raw instanceof IRecipe)) return null;
        final ItemStack output = ((IRecipe) raw).getRecipeOutput();
        if (output == null) return null;
        final CraftingRecord r = new CraftingRecord();
        r.className = raw.getClass()
            .getName();
        final CraftingIngredients.Read read = CraftingIngredients.read(raw);
        r.type = read != null ? read.type : type(raw);
        r.width = read != null ? read.width : size(raw, true);
        r.height = read != null ? read.height : size(raw, false);
        if (read != null) {
            for (int index = 0; index < read.slots.size(); index++) addInput(r.inputs, read.slots.get(index), index);
        } else {
            addInputs(r.inputs, raw);
        }
        r.output = GtRecipeReader.itemStack(output);
        return r;
    }

    /** Every CraftingManager recipe that makes something, in the game's order. */
    public List<CraftingRecord> readAll() {
        final List<CraftingRecord> out = new ArrayList<CraftingRecord>();
        for (final Object raw : recipes()) {
            final CraftingRecord r = read(raw);
            if (r != null) out.add(r);
        }
        return out;
    }

    /**
     * The recipe a crafting page shows: one that makes {@code output} and whose cells take what the page shows in
     * them ({@code cells}: for each shown cell its grid column and row, or -1 and its order for a shapeless page, and
     * the items it cycles through). Null when no recipe fits.
     */
    public CraftingRecord find(final ItemStack output, final List<ShownCell> cells) {
        if (output == null || output.getItem() == null) return null;
        for (final IRecipe recipe : byOutput(output.getItem())) {
            final ItemStack made = recipe.getRecipeOutput();
            if (made == null || !ItemStack.areItemStacksEqual(made, output)) continue;
            final CraftingRecord r = read(recipe);
            if (r != null && fits(r, cells)) return r;
        }
        return null;
    }

    /** One cell a crafting page shows: where it is, and the items it shows there. */
    public static final class ShownCell {

        /** The grid column and row of a shaped page's cell; -1 on a shapeless page. */
        public final int column, row;
        /** The cell's place among a shapeless page's cells. */
        public final int order;
        public final List<ItemStack> items;

        public ShownCell(final int column, final int row, final int order, final List<ItemStack> items) {
            this.column = column;
            this.row = row;
            this.order = order;
            this.items = items;
        }
    }

    // ---------------------------------------------------------------- the cells

    private void addInputs(final List<Slot> inputs, final Object recipe) {
        if (recipe instanceof ShapedRecipes) {
            final ShapedRecipes shaped = (ShapedRecipes) recipe;
            for (int index = 0; index < shaped.recipeItems.length; index++)
                addInput(inputs, shaped.recipeItems[index], index);
            return;
        }
        if (recipe instanceof ShapelessRecipes) {
            int index = 0;
            for (final Object stack : ((ShapelessRecipes) recipe).recipeItems) addInput(inputs, stack, index++);
            return;
        }
        if (recipe instanceof ShapedOreRecipe) {
            final Object[] raw = ((ShapedOreRecipe) recipe).getInput();
            for (int index = 0; index < raw.length; index++) addInput(inputs, raw[index], index);
            return;
        }
        if (recipe instanceof ShapelessOreRecipe) {
            int index = 0;
            for (final Object input : ((ShapelessOreRecipe) recipe).getInput()) addInput(inputs, input, index++);
            return;
        }
        int index = 0;
        for (final Object input : iterable(firstObject(recipe, "getInput", "recipeItems", "input")))
            addInput(inputs, input, index++);
    }

    private void addInput(final List<Slot> inputs, final Object input, final int slotIndex) {
        final Slot slot = slot(input);
        if (slot != null) {
            slot.slotIndex = slotIndex;
            inputs.add(slot);
        } else if (isEmptyChoice(input)) {
            // A cell whose ore list holds no item (a "gemFlawlessFluix" no item is): Forge never matches an empty
            // list, so the recipe cannot be crafted and the dataset drops it. Other cells this reader cannot read
            // (AE2 ingredients, integers, empty stacks) belong to recipes that do craft, so they stay left out.
            final Slot missing = new Slot();
            missing.kind = Slot.MISSING;
            missing.slotIndex = slotIndex;
            inputs.add(missing);
        }
    }

    private Slot slot(final Object input) {
        if (input == null) return null;
        if (input instanceof ItemStack) {
            final GtRecipeRecord.Stack stack = GtRecipeReader.itemStack((ItemStack) input);
            return stack == null ? null : stackSlot(Slot.ITEM, stack);
        }
        if (input instanceof FluidStack) {
            final GtRecipeRecord.Stack stack = GtRecipeReader.fluidStack((FluidStack) input);
            return stack == null ? null : stackSlot(Slot.FLUID, stack);
        }
        if (input instanceof String) return oreSlot(Collections.singletonList((String) input));
        if (input instanceof List) return listSlot((List<?>) input);
        if (input.getClass()
            .isArray()) {
            // An array of exactly one readable thing is that thing; anything else is no cell this reader knows.
            Slot only = null;
            int count = 0;
            for (final Object entry : iterable(input)) {
                if (entry == null) continue;
                count++;
                only = slot(entry);
            }
            return count == 1 ? only : null;
        }
        return null;
    }

    private static Slot stackSlot(final String kind, final GtRecipeRecord.Stack stack) {
        final Slot slot = new Slot();
        slot.kind = kind;
        slot.stack = stack;
        return slot;
    }

    /** An ore dictionary cell, with what its names hold in the game's order. */
    private static Slot oreSlot(final List<String> names) {
        final Slot slot = new Slot();
        slot.kind = Slot.ORE_DICTIONARY;
        slot.oreNames = new ArrayList<String>(names);
        slot.alternatives = new ArrayList<GtRecipeRecord.Stack>();
        for (final String name : names) {
            for (final ItemStack ore : OreLists.of(name)) {
                final GtRecipeRecord.Stack stack = GtRecipeReader.itemStack(ore);
                if (stack != null) slot.alternatives.add(stack);
            }
        }
        return slot;
    }

    /**
     * A list cell: Forge hands a ShapedOreRecipe the ore dictionary's list, not its name, so a list that is exactly
     * some names' lists is those names (sorted by name: their ids follow registration, which is not the same every run,
     * and the first names the cell); else the items themselves, one of which fills the cell.
     */
    private Slot listSlot(final List<?> list) {
        final List<String> names = oreNamesFor(list);
        if (names != null && !names.isEmpty()) return oreSlot(names);
        final List<GtRecipeRecord.Stack> alternatives = new ArrayList<GtRecipeRecord.Stack>();
        final List<String> keys = new ArrayList<String>();
        for (final Object raw : list) {
            if (!(raw instanceof ItemStack)) continue;
            final GtRecipeRecord.Stack stack = GtRecipeReader.itemStack((ItemStack) raw);
            if (stack == null) continue;
            alternatives.add(stack);
            keys.add(stackKey((ItemStack) raw));
        }
        if (alternatives.isEmpty()) return null;
        if (alternatives.size() == 1) return stackSlot(Slot.ITEM, alternatives.get(0));
        final Slot slot = new Slot();
        slot.kind = Slot.CHOICE;
        slot.choiceId = "choice:" + sha1(keys.toString()).substring(0, 16);
        slot.alternatives = alternatives;
        return slot;
    }

    private List<String> oreNamesFor(final List<?> list) {
        if (list.isEmpty()) return Collections.emptyList();
        if (oreNamesBySignature == null) {
            oreNamesBySignature = new HashMap<String, List<String>>();
            for (final String name : OreLists.names()) {
                final String signature = signature(OreDictionary.getOres(name));
                if (signature.length() == 0) continue;
                List<String> names = oreNamesBySignature.get(signature);
                if (names == null) {
                    names = new ArrayList<String>();
                    oreNamesBySignature.put(signature, names);
                }
                names.add(name);
            }
        }
        return oreNamesBySignature.get(signature(list));
    }

    private static String signature(final List<?> list) {
        final List<String> keys = new ArrayList<String>();
        for (final Object raw : list) {
            if (!(raw instanceof ItemStack)) continue;
            final String key = stackKey((ItemStack) raw);
            if (key.length() > 0) keys.add(key);
        }
        Collections.sort(keys);
        return keys.isEmpty() ? "" : keys.toString();
    }

    private static boolean isEmptyChoice(final Object input) {
        if (input instanceof Collection) return ((Collection<?>) input).isEmpty();
        return input != null && input.getClass()
            .isArray() && Array.getLength(input) == 0;
    }

    // ---------------------------------------------------------------- shape

    private static String type(final Object recipe) {
        if (recipe instanceof ShapedRecipes || recipe instanceof ShapedOreRecipe) return "shaped";
        if (recipe instanceof ShapelessRecipes || recipe instanceof ShapelessOreRecipe) return "shapeless";
        return recipe.getClass()
            .getName();
    }

    /**
     * A shaped recipe's inputs are packed width x height, so the size is what puts a cell back in its grid position.
     * ShapedRecipes has public fields (reflection by the development name finds nothing in a production client);
     * ShapedOreRecipe and every subclass keep them private as {@code width} and {@code height}.
     */
    private static int size(final Object recipe, final boolean width) {
        if (recipe instanceof ShapedRecipes)
            return width ? ((ShapedRecipes) recipe).recipeWidth : ((ShapedRecipes) recipe).recipeHeight;
        if (recipe instanceof ShapedOreRecipe) {
            final Object value = field(recipe, width ? "width" : "height");
            return value instanceof Number ? ((Number) value).intValue() : 0;
        }
        return 0;
    }

    // ---------------------------------------------------------------- the mod's lookup

    private static List<?> recipes() {
        return CraftingManager.getInstance()
            .getRecipeList();
    }

    private List<IRecipe> byOutput(final Item item) {
        if (recipesByOutput == null) {
            recipesByOutput = new IdentityHashMap<Item, List<IRecipe>>();
            for (final Object raw : recipes()) {
                if (!(raw instanceof IRecipe)) continue;
                final ItemStack made = ((IRecipe) raw).getRecipeOutput();
                if (made == null || made.getItem() == null) continue;
                List<IRecipe> list = recipesByOutput.get(made.getItem());
                if (list == null) {
                    list = new ArrayList<IRecipe>();
                    recipesByOutput.put(made.getItem(), list);
                }
                list.add((IRecipe) raw);
            }
        }
        final List<IRecipe> found = recipesByOutput.get(item);
        return found == null ? Collections.<IRecipe>emptyList() : found;
    }

    /** Whether every cell the page shows is a cell of the record that takes one of the items shown there. */
    private static boolean fits(final CraftingRecord r, final List<ShownCell> cells) {
        if (cells.size() != r.inputs.size()) return false;
        final boolean shaped = "shaped".equals(r.type) && r.width > 0;
        for (final ShownCell cell : cells) {
            Slot slot = null;
            if (shaped && cell.column >= 0) {
                final int index = cell.row * r.width + cell.column;
                for (final Slot s : r.inputs) if (s.slotIndex == index) slot = s;
            } else if (cell.order >= 0 && cell.order < r.inputs.size()) {
                slot = r.inputs.get(cell.order);
            }
            if (slot == null || !takesAny(slot, cell.items)) return false;
        }
        return true;
    }

    private static boolean takesAny(final Slot slot, final List<ItemStack> items) {
        for (final ItemStack item : items) {
            if (item == null || item.getItem() == null) continue;
            final String name = String.valueOf(Item.itemRegistry.getNameForObject(item.getItem()));
            if (slot.stack != null && name.equals(slot.stack.registryId)) return true;
            if (slot.alternatives != null)
                for (final GtRecipeRecord.Stack s : slot.alternatives) if (name.equals(s.registryId)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- helpers

    /** A stack's registry name, damage and size: the key the export run has always hashed a choice by. */
    public static String stackKey(final ItemStack stack) {
        if (stack == null || stack.getItem() == null) return "";
        final String registryId = String.valueOf(Item.itemRegistry.getNameForObject(stack.getItem()));
        return registryId + "@" + stack.getItemDamage() + "x" + stack.stackSize;
    }

    private static String sha1(final String value) {
        try {
            final byte[] data = MessageDigest.getInstance("SHA-1")
                .digest(value.getBytes("UTF-8"));
            final StringBuilder out = new StringBuilder();
            for (final byte b : data) {
                out.append(Character.forDigit((b >> 4) & 0xf, 16));
                out.append(Character.forDigit(b & 0xf, 16));
            }
            return out.toString();
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Iterable<?> iterable(final Object value) {
        if (value == null) return Collections.emptyList();
        if (value instanceof Iterable) return (Iterable<?>) value;
        if (value instanceof Map) return ((Map<?, ?>) value).entrySet();
        if (value.getClass()
            .isArray()) {
            final List<Object> out = new ArrayList<Object>();
            final int length = Array.getLength(value);
            for (int i = 0; i < length; i++) out.add(Array.get(value, i));
            return out;
        }
        return Collections.singletonList(value);
    }

    /** The first of these zero-argument public methods, else fields, that gives something. */
    private static Object firstObject(final Object target, final String... names) {
        for (final String name : names) {
            try {
                final Method method = target.getClass()
                    .getMethod(name);
                final Object value = method.invoke(target);
                if (value != null) return value;
            } catch (final Throwable ignored) {}
            final Object value = field(target, name);
            if (value != null) return value;
        }
        return null;
    }

    private static Object field(final Object target, final String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                final Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (final Throwable ignored) {}
        }
        return null;
    }
}
