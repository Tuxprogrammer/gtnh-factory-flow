package com.gtnhplanner.data.provider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.gtnhplanner.api.RecipePropertyAPI;
import com.gtnhplanner.core.model.read.SmeltingRecord;
import com.gtnhplanner.data.MachineProfile;
import com.gtnhplanner.data.MachineProfileRegistry;
import com.gtnhplanner.data.Settings;
import com.gtnhplanner.data.effect.Effects;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.properties.PropertyProvider;
import com.gtnhplanner.data.properties.RecipeProperty;
import com.gtnhplanner.read.furnace.SmeltingReader;

import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.FurnaceRecipeHandler;
import codechicken.nei.recipe.IRecipeHandler;

public class VanillaProvider implements PropertyProvider {

    // Vanilla furnace: base cook time of 200 ticks (10 seconds)
    // Matches net.minecraft.tileentity.TileEntityFurnace.furnaceCookTime
    private static final int FURNACE_COOK_TICKS = 200;

    @Override
    public void register() {
        RecipePropertyAPI.registerExtractor(FurnaceRecipeHandler.class, this);
        MachineProfileRegistry.register(
            MachineProfile.builder("minecraft", "Default")
                .setting(Settings.MACHINES.def())
                .setting(Settings.TICK_MODIFIER.def())
                .effect(
                    Effects.durationFromHandler()
                        .applyParallelism())
                .build());
    }

    @Override
    @Nullable
    public String getProfileId(final IRecipeHandler handler, final int recipeIndex) {
        return switch (handler) {
            case FurnaceRecipeHandler _ -> MachineProfileRegistry.defaultId();
            default -> null;
        };
    }

    @Override
    public boolean canCraft(final IRecipeHandler handler, final int recipeIndex) {
        return handler instanceof FurnaceRecipeHandler fh && "crafting.furnace".equals(fh.getOverlayIdentifier());
    }

    @Override
    @Nonnull
    public Map<RecipeProperty<?>, Object> extract(final Node node, final IRecipeHandler handler,
        final int recipeIndex) {
        final Map<RecipeProperty<?>, Object> props = new HashMap<>(
            PropertyProvider.super.extract(node, handler, recipeIndex));
        if (!(handler instanceof FurnaceRecipeHandler)) return props;
        props.put(RecipePropertyAPI.DURATION_TICKS, FURNACE_COOK_TICKS);
        final SmeltingRecord record = record(handler, recipeIndex);
        if (record != null) props.put(SMELTING_RECORD, record);
        return props;
    }

    /** The furnace recipe as the shared reader reads it (read/furnace): WebCards builds the card's dataset record. */
    public static final RecipeProperty<SmeltingRecord> SMELTING_RECORD = RecipeProperty
        .<SmeltingRecord>builder("vanilla.smelting_record", null)
        .build();

    /** The game's furnace recipe for the page's ingredient, found as the furnace finds it; null when none. */
    @Nullable
    private static SmeltingRecord record(final IRecipeHandler handler, final int recipeIndex) {
        final List<PositionedStack> ingredients = handler.getIngredientStacks(recipeIndex);
        if (ingredients == null || ingredients.isEmpty() || ingredients.getFirst() == null) return null;
        return SmeltingReader.forInput(ingredients.getFirst().item);
    }
}
