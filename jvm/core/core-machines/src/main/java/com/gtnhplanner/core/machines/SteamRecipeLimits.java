package com.gtnhplanner.core.machines;

import java.util.Set;

import com.gtnhplanner.machines.web.Web;

/**
 * Which recipes a steam machine can run at all (the website's src/lib/machines/steam-recipe-limits.ts). Recipes the
 * machine refuses are not offered for it.
 */
public final class SteamRecipeLimits {

    private SteamRecipeLimits() {}

    /**
     * The most EU/t a recipe may ask of a steam machine: V[1], LV. A steam singleblock
     * (MTEBasicMachineBronze.checkRecipe) looks recipes up with {@code .voltage(TierEU.LV)}, and a steam multiblock
     * (MTESteamMultiBlockBase) sets its available voltage to V[getTierRecipes()], which is 1 for all of them, and
     * refuses a recipe above it in validateRecipe. Bronze and steel builds share the cap.
     */
    public static final double STEAM_MAX_RECIPE_EUT = 32;

    /**
     * The steam multiblocks whose structure has no input hatch, so a recipe with a fluid input never reaches them:
     * their hatch elements are the steam hatch (locked to steam), steam input and output buses, and for the Steam
     * Separator (MTESteamCentrifuge) an output hatch. The Steam Squasher, Steam Presser, Steam Purifier and Steam
     * Blender take an input hatch.
     */
    private static final Set<String> WITHOUT_INPUT_HATCH = Set
        .of("Steam Grinder", "Steam Separator", "Steam Fuser", "Steam Hearth");

    /**
     * steamMachineRunsRecipe: whether a steam machine can run this recipe; the caller has already decided the handler
     * is a steam machine. A steam singleblock finds recipes from its items alone (MTEBasicMachineBronze.checkRecipe
     * passes no fluids), so it never runs one with a fluid input.
     */
    public static boolean runs(final Web.Handler handler, final Web.Recipe recipe) {
        if (recipe.eut > STEAM_MAX_RECIPE_EUT) return false;
        boolean fluidInput = false;
        if (recipe.inputs != null) for (final Web.Resource input : recipe.inputs) fluidInput |= "fluid".equals(input.kind);
        if (!fluidInput) return true;
        return "multiblock".equals(handler.kind) && !WITHOUT_INPUT_HATCH.contains(handler.machineType);
    }
}
