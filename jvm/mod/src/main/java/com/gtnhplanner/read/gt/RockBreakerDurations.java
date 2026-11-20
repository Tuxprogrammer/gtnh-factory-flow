package com.gtnhplanner.read.gt;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.item.ItemStack;

import gregtech.api.util.GTRecipe;
import gregtech.api.util.GTUtility;

/**
 * The time a Rock Breaker really runs a recipe.
 *
 * The recipes in the "gt.recipe.rockbreaker" map are display copies. The machine
 * ({@code MTERockBreaker}) keeps its own list of {@code RockBreakerRecipe} and runs
 * {@code getDuration()} of the matching one. GTNHRates divides the time in
 * {@code RockBreakerRecipe.Builder.duration}, so the machine's time is the stock time
 * divided once. The display copy is built from that already divided time and then
 * divided again by the rate mod's pass over every recipe map, so a stock 128 tick
 * Obsidian recipe shows 1 tick and runs 2.
 *
 * A display copy is matched to its real recipe by output item and programmed circuit
 * setting, plus the consumed input when the real recipe has one.
 */
final class RockBreakerDurations {

    static final String MAP_ID = "gt.recipe.rockbreaker";

    private static final class Real {

        ItemStack output;
        ItemStack input;
        int circuit;
        int duration;
    }

    private final List<Real> reals = new ArrayList<Real>();

    static RockBreakerDurations load() {
        RockBreakerDurations loaded = new RockBreakerDurations();
        try {
            Class<?> machine = Class.forName("gregtech.common.tileentities.machines.basic.MTERockBreaker");
            Field field = machine.getDeclaredField("ROCK_BREAKER_RECIPES");
            field.setAccessible(true);
            Map<?, ?> byCircuit = (Map<?, ?>) field.get(null);
            for (Object set : byCircuit.values()) {
                for (Object recipe : (Set<?>) set) {
                    Real real = new Real();
                    real.output = (ItemStack) read(recipe, "outputItem");
                    real.input = (ItemStack) read(recipe, "inputItem");
                    real.circuit = ((Number) read(recipe, "circuit")).intValue();
                    real.duration = ((Number) read(recipe, "duration")).intValue();
                    if (real.output != null) {
                        loaded.reals.add(real);
                    }
                }
            }
        } catch (Throwable ignored) {
            loaded.reals.clear();
        }
        return loaded;
    }

    /** The machine's own time for this display recipe, or -1 when no real recipe matches. */
    int durationFor(GTRecipe display) {
        if (display.mOutputs == null || display.mOutputs.length == 0 || display.mOutputs[0] == null) {
            return -1;
        }
        ItemStack output = display.mOutputs[0];
        int circuit = circuitOf(display);
        for (Real real : reals) {
            if (real.circuit != circuit || !sameItem(real.output, output)) {
                continue;
            }
            if (real.input != null && !hasInput(display, real.input)) {
                continue;
            }
            return real.duration;
        }
        return -1;
    }

    private static int circuitOf(GTRecipe display) {
        Object circuitItem = GTUtility.getIntegratedCircuit(0)
            .getItem();
        if (display.mInputs != null) {
            for (ItemStack stack : display.mInputs) {
                if (stack != null && stack.getItem() == circuitItem) {
                    return stack.getItemDamage();
                }
            }
        }
        return -1;
    }

    private static boolean hasInput(GTRecipe display, ItemStack input) {
        if (display.mInputs == null) {
            return false;
        }
        for (ItemStack stack : display.mInputs) {
            if (stack != null && sameItem(stack, input)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameItem(ItemStack left, ItemStack right) {
        return left.getItem() == right.getItem() && left.getItemDamage() == right.getItemDamage();
    }

    private static Object read(Object target, String name) throws Exception {
        Field field = target.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
