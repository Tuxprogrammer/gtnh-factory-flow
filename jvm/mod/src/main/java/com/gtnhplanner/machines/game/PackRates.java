package com.gtnhplanner.machines.game;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import javax.annotation.Nullable;

import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.core.machines.GameRates;

import cpw.mods.fml.common.Loader;

/**
 * The core's pack rates ({@link GameRates}) as this game runs them: GTNHRates keeps its factors in public static float
 * fields of {@code com.github.sladki.gtnhrates.ModConfig$Rates} ({@code beesYield}, {@code ic2CropsYield},
 * {@code gtRecipesEnergyDiscount}), which its config screen changes while the game runs, so they are read on every
 * call. GTNHRates is an optional dependency: without it, or if its fields are not there, every factor is 1. The
 * pack's recipe maps already hold the divided times (the rate mod rewrites them at load), so only what the rate mod
 * changes outside the maps reads these: the GT furnaces' fixed smelt, Forestry bee housings and the Crop Manager.
 */
public final class PackRates {

    private PackRates() {}

    private static final String MOD_ID = "gtnhrates", RATES = "com.github.sladki.gtnhrates.ModConfig$Rates";

    /** The three fields, or null when the rate mod is not installed or does not have them. */
    @Nullable
    private static Field[] fields;

    /** Points the core at the rate mod's live config. Safe without the rate mod (and in tests): every factor is 1. */
    public static void install() {
        fields = find();
        GameRates.install(PackRates::read);
    }

    @Nullable
    private static Field[] find() {
        try {
            if (!Loader.isModLoaded(MOD_ID)) return null;
            final Class<?> rates = Class.forName(RATES, false, PackRates.class.getClassLoader());
            final Field[] out = { rates.getField("beesYield"), rates.getField("ic2CropsYield"),
                rates.getField("gtRecipesEnergyDiscount") };
            for (final Field f : out) if (!Modifier.isStatic(f.getModifiers())) return null;
            GtnhPlanner.LOG.info("GTNHRates found: machine math reads its bee, crop and recipe rates");
            return out;
        } catch (final ReflectiveOperationException | LinkageError | RuntimeException e) {
            GtnhPlanner.LOG.warn("GTNHRates is loaded but its rates could not be read; using 1 for each", e);
            return null;
        }
    }

    private static GameRates read() {
        final Field[] f = fields;
        if (f == null) return GameRates.NONE;
        try {
            return new GameRates(f[0].getFloat(null), f[1].getFloat(null), f[2].getFloat(null));
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return GameRates.NONE;
        }
    }
}
