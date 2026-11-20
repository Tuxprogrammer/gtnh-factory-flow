package com.gtnhplanner.data.provider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import com.gtnhplanner.Compat;
import com.gtnhplanner.api.RecipePropertyAPI;
import com.gtnhplanner.core.model.read.GtRecipeRecord;
import com.gtnhplanner.data.MachineProfile;
import com.gtnhplanner.data.MachineProfileRegistry;
import com.gtnhplanner.data.RecipeHandlerAccess;
import com.gtnhplanner.data.SettingDef;
import com.gtnhplanner.data.Settings;
import com.gtnhplanner.data.effect.Effects;
import com.gtnhplanner.data.effect.steps.GTOverclockStep;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.data.properties.PropertyProvider;
import com.gtnhplanner.data.properties.RecipeProperty;
import com.gtnhplanner.read.gt.GtRecipeReader;

import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.FurnaceRecipeHandler;
import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.TemplateRecipeHandler;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.recipe.RecipeMaps;
import gregtech.api.recipe.maps.FuelBackend;
import gregtech.api.recipe.maps.LargeBoilerFuelBackend;
import gregtech.api.util.GTRecipe;
import gregtech.nei.GTNEIDefaultHandler;
import gregtech.nei.GTNEIDefaultHandler.CachedDefaultRecipe;
import gregtech.nei.formatter.HeatingCoilSpecialValueFormatter;

public class GTProvider implements PropertyProvider {

    // Vanilla furnace: base cook time of 200 ticks (10 seconds)
    private static final int FURNACE_COOK_TICKS = 200;

    /** GT5u stores a chance as 1..10000, where 10000 is 100%. */
    private static final float GT_CHANCE_SCALE = 10_000f;

    public static final RecipeProperty<Integer> SPECIAL_VALUE = RecipeProperty.<Integer>builder("gt.special_value", 0)
        .build();
    public static final RecipeProperty<Integer> GLASS_TIER = RecipeProperty
        .<Integer>builder("gt.bartworks.glass_tier", 3)
        .build();
    public static final RecipeProperty<Integer> SIEVERT = RecipeProperty.<Integer>builder("gt.bartworks.sievert", 0)
        .build();
    public static final RecipeProperty<Boolean> SIEVERT_EXACT = RecipeProperty
        .<Boolean>builder("gt.bartworks.sievert_exact", false)
        .build();
    public static final RecipeProperty<Integer> MASS = RecipeProperty.<Integer>builder("gt.bartworks.mass", 0)
        .build();

    public static final RecipeProperty<Long> TOTAL_EU = GTKeys.TOTAL_EU;
    public static final RecipeProperty<Long> EU_PER_TICK = GTKeys.EU_PER_TICK;

    public static final RecipeProperty<Integer> COIL_HEAT = GTKeys.COIL_HEAT;
    public static final RecipeProperty<Long> FUSION_THRESHOLD = RecipeProperty.<Long>builder("gt.fusion_threshold", 0L)
        .build();

    /** The recipe as the shared reader read it (read/gt): WebCards builds the card's dataset record from it. */
    public static final RecipeProperty<GtRecipeRecord> RECORD = RecipeProperty
        .<GtRecipeRecord>builder("gt.record", null)
        .build();

    public static final RecipeProperty<RecipeMap<?>> RECIPE_MAP = RecipeProperty
        .<RecipeMap<?>>builder("gt.recipe_map", null)
        .build();

    @Override
    public void register() {
        RecipePropertyAPI.registerExtractor(FurnaceRecipeHandler.class, this);
        if (Compat.EFR.isLoaded) {
            EFRProvider.registerHandlers(this);
        }

        RecipePropertyAPI.registerExtractor(GTNEIDefaultHandler.class, this);

        MachineProfileRegistry.register(PROFILE);
        new GTSteamProvider().register();
        // The modelled machines (machines/): their multipliers and the radio hatch's burn, after every refresh.
        Node.afterRefresh = node -> {
            com.gtnhplanner.machines.game.WebEffect.forget(node);
            com.gtnhplanner.machines.game.MachineModels.afterRefresh(node);
        };
        // Every other GregTech card runs on the website's machine maths (machines/web).
        com.gtnhplanner.data.MachineConfig.nodeEffect = com.gtnhplanner.machines.game.WebEffect::effect;
    }

    /**
     * Every GregTech recipe: the tier and amps (the card's own chips), the machine count, whether the chosen machine is
     * a multiblock, the website's coil and energy hatch type (machines/game/WebCards; each machine's own settings are
     * kept as {@code machine:<id>}, which the serializer keeps too), and the modelled machines' settings. The numbers
     * come from the website's machine maths (WebEffect, through MachineConfig.nodeEffect); this effect only runs for
     * the recipes those do not cover: GT's plain overclocks, then the Tree Growth Simulator's and Bacterial Vat's
     * models.
     */
    private static final MachineProfile PROFILE = withModelledMachines(
        MachineProfile.builder("gregtech:unified", "GT Unified")).setting(Settings.VOLTAGE.def())
            .setting(Settings.AMP.def())
            .setting(Settings.MACHINES.def())
            .setting(Settings.GT_MULTIBLOCK.def())
            .setting(SettingDef.enumDef(com.gtnhplanner.machines.game.WebCards.COIL, "", List.of(), (v, c) -> null))
            .setting(
                SettingDef.enumDef(com.gtnhplanner.machines.game.WebCards.HATCH_TYPE, "", List.of(), (v, c) -> null))
            .effect(
                Effects.durationFromHandler()
                    .andThen(GTOverclockStep.create())
                    .andThen(new com.gtnhplanner.machines.game.MachineModelStep()))
            .build();

    /** The modelled machines' settings, kept under the website's keys (MachineModels). */
    private static MachineProfile.Builder withModelledMachines(final MachineProfile.Builder builder) {
        for (final SettingDef<String> def : com.gtnhplanner.machines.game.MachineModels.SETTINGS) builder.setting(def);
        return builder;
    }

    @Override
    public boolean canCraft(final IRecipeHandler handler, final int recipeIndex) {
        if (handler instanceof FurnaceRecipeHandler) return true;
        return handler instanceof GTNEIDefaultHandler;
    }

    @Override
    @Nullable
    public String getProfileId(final IRecipeHandler handler, final int recipeIndex) {
        if (handler instanceof FurnaceRecipeHandler) return PROFILE.id();
        if (handler instanceof GTNEIDefaultHandler) return PROFILE.id();
        return null;
    }

    @Override
    @Nonnull
    public Map<RecipeProperty<?>, Object> extract(final Node node, final IRecipeHandler handler,
        final int recipeIndex) {
        return extractGTRecipe(node, handler, recipeIndex);
    }

    public static Map<RecipeProperty<?>, Object> extractGTRecipe(final Node node, final IRecipeHandler handler,
        final int recipeIndex) {
        final Map<RecipeProperty<?>, Object> props = new HashMap<>();
        GTRecipe r;
        RecipeMap<?> gthMap = null;
        RecipeMap<?> readMap;

        if (handler instanceof final FurnaceRecipeHandler fh) {
            final List<TemplateRecipeHandler.CachedRecipe> fRecipes = RecipeHandlerAccess.getArecipes(fh);
            props.put(RecipePropertyAPI.DURATION_TICKS, FURNACE_COOK_TICKS);

            if (recipeIndex < 0 || recipeIndex >= fRecipes.size()) {
                return props;
            }
            final TemplateRecipeHandler.CachedRecipe cr = fRecipes.get(recipeIndex);
            if (cr == null) {
                return props;
            }
            final List<PositionedStack> ingredients = cr.getIngredients();
            if (ingredients == null || ingredients.isEmpty() || ingredients.getFirst().item == null) {
                return props;
            }

            final String overlay = fh.getOverlayIdentifier();
            final RecipeMap<?> recipeMap;
            if (EFRProvider.SMOKER_OVERLAY.equals(overlay)) {
                recipeMap = RecipeMaps.efrSmokingRecipes;
            } else if (EFRProvider.BLAST_FURNACE_OVERLAY.equals(overlay)) {
                recipeMap = RecipeMaps.efrBlastingRecipes;
            } else {
                recipeMap = RecipeMaps.furnaceRecipes;
            }

            r = recipeMap.findRecipeQuery()
                .items(ingredients.getFirst().item)
                .find();
            if (r == null) {
                return props;
            }
            readMap = recipeMap;

        } else if (handler instanceof final GTNEIDefaultHandler gth) {
            final List<TemplateRecipeHandler.CachedRecipe> recipes = RecipeHandlerAccess.getArecipes(gth);
            if (recipeIndex < 0 || recipeIndex >= recipes.size()) return props;

            final CachedDefaultRecipe cached = (CachedDefaultRecipe) recipes.get(recipeIndex);
            r = cached.mRecipe;
            if (r == null) return props;

            gthMap = gth.getRecipeMap();
            readMap = gthMap;
            if (readMap == null) return props;

        } else {
            return props;
        }

        // One read of the recipe, the export run's (read/gt): the time the machine runs, its numbers and its slots.
        final GtRecipeRecord record = reader().read(readMap, r);
        props.put(RECORD, record);
        props.put(RecipePropertyAPI.DURATION_TICKS, record.durationTicks);
        props.put(EU_PER_TICK, record.eut);
        props.put(TOTAL_EU, record.eut * record.durationTicks);

        if (gthMap != null && (gthMap.getBackend() instanceof FuelBackend
            || gthMap.getBackend() instanceof LargeBoilerFuelBackend)) {
            props.put(EU_PER_TICK, 0L);
            props.put(TOTAL_EU, 0L);
        }

        if (record.glassTier != 3) props.put(GLASS_TIER, record.glassTier);
        if (record.coilHeat != 0) props.put(COIL_HEAT, record.coilHeat);

        if (gthMap != null && !props.containsKey(COIL_HEAT)
            && gthMap.getFrontend().getNEIProperties().neiSpecialInfoFormatter instanceof HeatingCoilSpecialValueFormatter
            && record.specialValue > 0) {
            props.put(COIL_HEAT, record.specialValue);
        }

        if (gthMap != null) {
            props.put(RECIPE_MAP, gthMap);
        }

        if (record.fusionStartupEu != null && record.fusionStartupEu != 0L)
            props.put(FUSION_THRESHOLD, record.fusionStartupEu);

        if (record.sievert > 0 || record.sievertExact) {
            props.put(SIEVERT, record.sievert);
            if (record.sievertExact) props.put(SIEVERT_EXACT, true);
        }

        if (record.mass != 0) props.put(MASS, record.mass);

        if (record.specialValue != 0) {
            props.put(SPECIAL_VALUE, record.specialValue);
        }

        // Reset everything to not ignore burnables
        node.inputs.clear();
        node.outputs.clear();
        ports(node, record);

        // The Tree Growth Simulator: NEI's outputs by mode (a mode the tree lacks is null) and the sapling's genes.
        if (com.gtnhplanner.machines.game.MachineModels.isTgs(props))
            com.gtnhplanner.machines.game.MachineModels.readTgs(node, r, props);

        return props;
    }

    /** The shared reader, made on first use (after the game has loaded the Rock Breaker's and Scanner's lists). */
    private static GtRecipeReader reader;

    public static synchronized GtRecipeReader reader() {
        if (reader == null) reader = new GtRecipeReader();
        return reader;
    }

    /**
     * The card's ports from the record, items before fluids: every slot the recipe uses up (a kept one, a programmed
     * circuit or a mold, is no port), with its chance. Copies, never GT's own stacks: Node.deduplicate merges ports by
     * writing the summed amount into the first one, which on an aliased stack rewrites the recipe itself (and grows it
     * again on every refresh). The Sparge Tower's byproducts, registered at 0 L, stay on the card at 0.
     */
    private static void ports(final Node node, final GtRecipeRecord record) {
        for (final GtRecipeRecord.Stack s : record.itemInputs) {
            if (Boolean.FALSE.equals(s.consumed) || !(s.host instanceof final ItemStack stack)) continue;
            final ItemStack in = stack.copy();
            // The Rock Breaker's lava placeholder (FreeInputs): kept on the card, an amount of 0 so nothing asks for it.
            if (FreeInputs.isFreeId(s.id)) in.stackSize = 0;
            node.inputs.add(new Port<>(RecipePropertyAPI.ITEM, in, chance(s)));
        }
        for (final GtRecipeRecord.Stack s : record.itemOutputs)
            if (s.host instanceof final ItemStack stack)
                node.outputs.add(new Port<>(RecipePropertyAPI.ITEM, stack.copy(), chance(s)));
        for (final GtRecipeRecord.Stack s : record.fluidInputs)
            if (!Boolean.FALSE.equals(s.consumed) && s.host instanceof final FluidStack fluid)
                node.inputs.add(new Port<>(RecipePropertyAPI.FLUID, fluid.copy(), chance(s)));
        for (final GtRecipeRecord.Stack s : record.fluidOutputs) if (s.host instanceof final FluidStack fluid)
            node.outputs.add(new Port<>(RecipePropertyAPI.FLUID, fluid.copy(), chance(s)));
        if (record.spargeFluidOutputs != null) for (final GtRecipeRecord.Stack s : record.spargeFluidOutputs)
            if (s.amount <= 0 && s.host instanceof final FluidStack fluid)
                node.outputs.add(new Port<>(RecipePropertyAPI.FLUID, fluid.copy(), 1.f));
    }

    /** A port's chance: an input's use-up chance, an output's make chance (GT's ten-thousandths), else 1. */
    private static float chance(final GtRecipeRecord.Stack s) {
        if (s.inputChance != null) return s.inputChance / GT_CHANCE_SCALE;
        return s.chance != null ? s.chance.floatValue() : 1.f;
    }
}
