package com.gtnhplanner.core.machines;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.ExactMath;
import com.gtnhplanner.machines.web.Tiers;
import com.gtnhplanner.machines.web.Web;

/**
 * The Algae Farm (GT5U MTEAlgaePond, "Algae Pond" map, the pack's craftable controller), as the website models it
 * (src/lib/machines/algae-pond.ts).
 *
 * <p>
 * Its one energy hatch picks the recipe: the pond runs the recipe whose special value is the hatch's tier, or one tier
 * higher when an input bus holds enough Heavy Compost (compostForTier(hatch tier) a run). It draws V[hatch tier] x
 * 9/10 EU/t, never overclocks and runs one parallel. The recipes are registered at 0 EU/t with no inputs, which is what
 * the data carries; the draw and the compost come from here. GT++'s MTEAlgaePondBaseLegacy (casing tier, no power) is
 * the old controller and is not modelled.
 */
public final class AlgaePond {

    private AlgaePond() {}

    public static final String MACHINE_TYPE = "Algae Pond";
    public static final String COMPOST = "algaeCompost";
    public static final String HEAVY_COMPOST_ID = "miscutils:item.basicagrichemitem@8";
    /** The highest energy hatch tier (MAX). Tier 15 algae is reachable only with compost on it. */
    private static final int TOP_HATCH_TIER = Tiers.NAMES.length - 1;

    public static boolean isRecipe(@Nullable final Web.Recipe recipe) {
        return recipe != null && MACHINE_TYPE.equals(recipe.machineType);
    }

    /** algaePondRecipeTier: the recipe's ALGAE_POND_TIER (its special value), a whole number from 0. */
    @Nullable
    public static Integer recipeTier(final Web.Recipe recipe) {
        Double value = recipe.specialValue;
        if (value == null && recipe.metadata != null && recipe.metadata.get("specialValue") instanceof final Double special)
            value = special;
        return value != null && value >= 0 && value == Math.floor(value) && value <= Integer.MAX_VALUE
            ? Integer.valueOf(value.intValue())
            : null;
    }

    /** MTEAlgaePond.getRecipeEUt: V[tier] x 9 / 10 in long maths. */
    public static double euPerTick(final int hatchTier) {
        return Math.floor(Tiers.MAX_EUT[hatchTier] * 9 / 10);
    }

    /** MTEAlgaePond.compostForTier: Heavy Compost a run on a hatch of this tier. */
    public static int compostPerRun(final int hatchTier) {
        return hatchTier > 1 ? (int) Math.min(64, ExactMath.pow(2, hatchTier - 1)) : 1;
    }

    /** Compost is the default only where no hatch reaches the recipe's own tier (tier 15). */
    private static boolean compostByDefault(final int tier) {
        return tier > TOP_HATCH_TIER;
    }

    /** algaePondUsesCompost. */
    public static boolean usesCompost(final Web.Recipe recipe, @Nullable final Map<String, String> settings) {
        final Integer tier = recipeTier(recipe);
        if (tier == null || tier < 1) return false;
        final String key = settings != null ? settings.get(COMPOST) : null;
        return key == null ? compostByDefault(tier) : key.equals("compost");
    }

    /** algaePondHatchTier: the energy hatch tier that grows this recipe; above the top tier means no hatch can. */
    @Nullable
    public static Integer hatchTier(final Web.Recipe recipe, @Nullable final Map<String, String> settings) {
        final Integer tier = recipeTier(recipe);
        if (tier == null) return null;
        return usesCompost(recipe, settings) ? tier - 1 : tier;
    }

    /**
     * enrichAlgaePondRecipe: the data lists every pond recipe at ULV and 0 EU/t. The recipe instead carries the draw
     * and the minimum tier of the hatch that grows it by default, so its card shows the power it needs.
     */
    public static Web.Recipe enrich(final Web.Recipe recipe) {
        final Integer hatch = hatchTier(recipe, null);
        if (hatch == null || hatch > TOP_HATCH_TIER) return recipe;
        final Web.Recipe out = recipe.copy();
        out.minimumTier = Tiers.NAMES[hatch];
        out.eut = euPerTick(hatch);
        return out;
    }

    /** algaePondPowerMultiplier: the table's EU multiplier, the chosen hatch's draw over the draw the recipe carries. */
    public static double powerMultiplier(@Nullable final Web.Recipe recipe,
        @Nullable final Map<String, String> settings) {
        if (recipe == null) return 1;
        final Integer base = hatchTier(recipe, null);
        final Integer hatch = hatchTier(recipe, settings);
        if (base == null || hatch == null || base > TOP_HATCH_TIER || hatch > TOP_HATCH_TIER || hatch < 0) return 1;
        return euPerTick(hatch) / euPerTick(base);
    }

    /** getAlgaePondGateReason: why this card's hatch does not grow its recipe, or null when it does. */
    @Nullable
    public static String gateReason(@Nullable final Web.Recipe recipe, @Nullable final Map<String, String> settings,
        final int hatchTier) {
        if (recipe == null) return null;
        final Integer hatch = hatchTier(recipe, settings);
        if (hatch == null) return null;
        if (hatch > TOP_HATCH_TIER) return "No energy hatch reaches tier " + hatch
            + ". This recipe grows only with compost on a "
            + Tiers.NAMES[TOP_HATCH_TIER]
            + " hatch.";
        if (hatchTier == hatch) return null;
        return usesCompost(recipe, settings)
            ? "Needs one " + Tiers.NAMES[hatch]
                + " energy hatch. With compost the pond grows the recipe one tier above its hatch."
            : "Needs one " + Tiers.NAMES[hatch] + " energy hatch. The pond grows the recipe of its hatch's tier.";
    }

    /** getAlgaePondControls: the compost knob, on every recipe a lower hatch can reach with compost (tier 1 and up). */
    public static List<Web.Control> controls(final Web.Recipe recipe) {
        final Integer tier = recipeTier(recipe);
        if (tier == null || tier < 1) return List.of();
        final int hatch = tier - 1;
        final int perRun = compostPerRun(hatch);
        final Web.Control control = new Web.Control();
        control.id = COMPOST;
        control.label = "Compost";
        control.minimumKey = "none";
        control.defaultKey = compostByDefault(tier) ? "compost" : "none";
        control.tiers = new ArrayList<>();
        control.tiers.add(
            option(
                "none",
                "No compost",
                "Grows this tier on one " + Tiers.NAMES[Math.min(tier, TOP_HATCH_TIER)] + " energy hatch.",
                "factoryflow:machine_config/" + COMPOST + "_none"));
        control.tiers.add(
            option(
                "compost",
                "Compost " + perRun + "x",
                perRun + " Heavy Compost a run grows this tier on one " + Tiers.NAMES[hatch] + " energy hatch.",
                HEAVY_COMPOST_ID));
        return List.of(control);
    }

    private static Web.TierOption option(final String key, final String label, final String detail, final String id) {
        final Web.TierOption option = new Web.TierOption();
        option.key = key;
        option.label = label;
        final Web.Resource resource = new Web.Resource();
        resource.kind = "item";
        resource.id = id;
        resource.amount = 1;
        resource.displayName = label;
        resource.tooltip = List.of(detail);
        resource.consumed = false;
        option.resource = resource;
        return option;
    }

    /** applyAlgaePondInputs: the Heavy Compost a composted run uses up, added as an input. */
    public static Web.Recipe withInputs(final Web.Recipe recipe, @Nullable final Map<String, String> settings) {
        if (!usesCompost(recipe, settings)) return recipe;
        if (recipe.inputs != null) for (final Web.Resource input : recipe.inputs)
            if (HEAVY_COMPOST_ID.equals(input.id)) return recipe;
        final Integer hatch = hatchTier(recipe, settings);
        final Web.Resource compost = new Web.Resource();
        compost.kind = "item";
        compost.id = HEAVY_COMPOST_ID;
        compost.amount = compostPerRun(hatch);
        compost.displayName = "Heavy Compost";
        final Web.Recipe out = recipe.copy();
        out.inputs = new ArrayList<>(recipe.inputs != null ? recipe.inputs : List.of());
        out.inputs.add(compost);
        return out;
    }
}
