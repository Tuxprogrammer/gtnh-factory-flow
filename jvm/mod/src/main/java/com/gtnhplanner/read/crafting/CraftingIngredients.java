package com.gtnhplanner.read.crafting;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * The ingredients of crafting recipes whose class keeps them where the generic reader
 * does not look, so the export would otherwise give them no inputs and the planner
 * would make their output from nothing.
 *
 * Each reader returns the slots the way {@link CraftingReader}'s generic reading sees a
 * vanilla recipe: an ItemStack, a list of alternative stacks, an ore dictionary name, null
 * for an empty grid cell, or {@link #MISSING} for a slot the game can never fill. Classes
 * not named here return null and keep the generic reader. Mods are read by reflection
 * because neither the mod nor the export run is built against them.
 */
final class CraftingIngredients {

    /** A slot nothing can fill: a "missing" cell, and the dataset drops the recipe. */
    static final List<Object> MISSING = Collections.emptyList();

    static final class Read {

        final String type;
        final int width;
        final int height;
        final List<Object> slots = new ArrayList<Object>();

        Read(String type, int width, int height) {
            this.type = type;
            this.width = width;
            this.height = height;
        }
    }

    private CraftingIngredients() {}

    static Read read(Object recipe) {
        try {
            String name = recipe.getClass()
                .getName();
            if ("appeng.recipes.game.ShapedRecipe".equals(name)) return appliedEnergistics(recipe, true);
            if ("appeng.recipes.game.ShapelessRecipe".equals(name)) return appliedEnergistics(recipe, false);
            if ("mrtjp.projectred.core.libmc.recipe.ShapedBuilderRecipe".equals(name)) return projectRedShaped(recipe);
            if ("mrtjp.projectred.core.libmc.recipe.ShapelessBuilderRecipe".equals(name))
                return projectRedShapeless(recipe);
            // Pours the inventory of a dyed or undyed box into a recoloured one: the box
            // (any colour, the plan has no NBT variants) plus the dye, or the box alone.
            if ("com.dreammaster.scripts.ScriptEFR$ShulkerNBTRecipe".equals(name)) {
                return shapeless(stack(field(recipe, "reqItem"), 0), field(recipe, "dye"));
            }
            // Clears a module's settings: the same item in, the same item out.
            if ("logisticspipes.recipes.ShapelessResetRecipe".equals(name)) {
                return shapeless(stack(field(recipe, "item"), ((Number) field(recipe, "meta")).intValue()));
            }
            // A chain and an iron armour piece of the same slot make the knight's piece.
            if ("mods.battlegear2.heraldry.KnightArmourRecipie".equals(name)) {
                return shapeless(stack(field(recipe, "chainArmourId"), 0), stack(field(recipe, "ironArmourId"), 0));
            }
            // A used gas mask or oxygen tank and one refill give a fresh one.
            if ("com.gregguns.armor.slots.EquipmentRefillRecipe".equals(name)) {
                return shapeless(stack(field(recipe, "mask"), 0), stack(field(recipe, "filter"), 0));
            }
        } catch (Throwable ignored) {
            // A class that does not have the shape read above keeps the generic reader.
        }
        return null;
    }

    // AE2 keeps an IIngredient per cell. A recipe whose ingredient resolves to nothing is
    // disabled by AE2 itself (bake() sets "disable") and never matches.
    private static Read appliedEnergistics(Object recipe, boolean shaped) throws Exception {
        Object input = invoke(recipe, "getInput");
        List<Object> cells = input instanceof Object[] ? Arrays.asList((Object[]) input)
            : new ArrayList<Object>((List<?>) input);
        Read read = shaped
            ? new Read(
                "shaped",
                ((Number) field(recipe, "width")).intValue(),
                ((Number) field(recipe, "height")).intValue())
            : new Read("shapeless", 0, 0);
        boolean enabled = !Boolean.FALSE.equals(invoke(recipe, "isEnabled"));
        for (Object cell : cells) {
            read.slots.add(enabled ? aeIngredient(cell) : MISSING);
        }
        return read;
    }

    private static Object aeIngredient(Object ingredient) throws Exception {
        if (ingredient == null || Boolean.TRUE.equals(invoke(ingredient, "isAir"))) {
            return null;
        }
        ItemStack[] set;
        try {
            set = (ItemStack[]) invoke(ingredient, "getItemStackSet");
        } catch (InvocationTargetException missing) {
            return MISSING;
        }
        if (set == null || set.length == 0) return MISSING;
        // An "oredictionary:chestWood" cell keeps its ore name, not the one item it holds today.
        if ("oreDictionary".equalsIgnoreCase(String.valueOf(invoke(ingredient, "getNameSpace")))) {
            return invoke(ingredient, "getItemName");
        }
        if (set.length == 1) return one(set[0]);
        List<Object> alternatives = new ArrayList<Object>();
        for (ItemStack stack : set) {
            alternatives.add(one(stack));
        }
        return alternatives;
    }

    // ProjectRed's builder keeps a Map[Int, Input] of grid cells (size x size) and each
    // Input lists the stacks it accepts, as its own NEI handler shows them.
    private static Read projectRedShaped(Object recipe) throws Exception {
        Object builder = invoke(recipe, "builder");
        int size = ((Number) invoke(builder, "size")).intValue();
        Object inputMap = invoke(builder, "inputMap");
        Method contains = inputMap.getClass()
            .getMethod("contains", Object.class);
        Method apply = inputMap.getClass()
            .getMethod("apply", Object.class);
        contains.setAccessible(true);
        apply.setAccessible(true);
        Read read = new Read("shaped", size, size);
        for (int index = 0; index < size * size; index++) {
            Integer key = Integer.valueOf(index);
            read.slots.add(
                Boolean.TRUE.equals(contains.invoke(inputMap, key)) ? projectRedInput(apply.invoke(inputMap, key))
                    : null);
        }
        return read;
    }

    private static Read projectRedShapeless(Object recipe) throws Exception {
        Read read = new Read("shapeless", 0, 0);
        for (Object input : scalaList(invoke(invoke(recipe, "builder"), "inResult"))) {
            read.slots.add(projectRedInput(input));
        }
        return read;
    }

    private static Object projectRedInput(Object input) throws Exception {
        List<Object> stacks = new ArrayList<Object>();
        for (Object stack : scalaList(invoke(input, "matchingInputs"))) {
            if (stack instanceof ItemStack) stacks.add(one((ItemStack) stack));
        }
        if (stacks.isEmpty()) return MISSING;
        return stacks.size() == 1 ? stacks.get(0) : stacks;
    }

    private static List<Object> scalaList(Object seq) throws Exception {
        List<Object> out = new ArrayList<Object>();
        Object iterator = invoke(seq, "iterator");
        Method hasNext = iterator.getClass()
            .getMethod("hasNext");
        Method next = iterator.getClass()
            .getMethod("next");
        hasNext.setAccessible(true);
        next.setAccessible(true);
        while (Boolean.TRUE.equals(hasNext.invoke(iterator))) {
            out.add(next.invoke(iterator));
        }
        return out;
    }

    private static Read shapeless(Object... slots) {
        Read read = new Read("shapeless", 0, 0);
        for (Object slot : slots) {
            if (slot != null) read.slots.add(slot);
        }
        return read;
    }

    // A crafting slot takes one item whatever the source's stack size says.
    private static ItemStack one(ItemStack stack) {
        ItemStack copy = stack.copy();
        copy.stackSize = 1;
        return copy;
    }

    private static ItemStack stack(Object item, int meta) {
        if (!(item instanceof Item)) throw new IllegalStateException("not an item: " + item);
        return new ItemStack((Item) item, 1, meta);
    }

    private static Object field(Object target, String name) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // look in the superclass
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object invoke(Object target, String name) throws Exception {
        Method method = target.getClass()
            .getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }
}
