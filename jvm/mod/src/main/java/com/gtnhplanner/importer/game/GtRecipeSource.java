package com.gtnhplanner.importer.game;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import com.gtnhplanner.core.model.read.GtRecipeRecord;
import com.gtnhplanner.data.RecipeHandlerAccess;
import com.gtnhplanner.data.provider.GTProvider;
import com.gtnhplanner.importer.FfIds;
import com.gtnhplanner.importer.FfPlan.FfRecipe;
import com.gtnhplanner.importer.RecipeIndex.GameRecipe;
import com.gtnhplanner.importer.RecipeIndex.GameStack;
import com.gtnhplanner.importer.RecipeIndex.Lookup;
import com.gtnhplanner.machines.game.WebCards;
import com.gtnhplanner.machines.web.HandlerData;

import codechicken.nei.recipe.GuiCraftingRecipe;
import codechicken.nei.recipe.ICraftingHandler;
import codechicken.nei.recipe.TemplateRecipeHandler;
import gregtech.api.util.GTRecipe;
import gregtech.nei.GTNEIDefaultHandler;
import gregtech.nei.GTNEIDefaultHandler.CachedDefaultRecipe;

/**
 * GregTech recipes by recipe map, as NEI lists them. GT class references, isolated: only call behind
 * {@code Compat.GREGTECH.isLoaded}.
 *
 * <p>
 * GregTech registers one NEI handler per recipe category; asking one for its map's unlocalized name
 * ({@code getRecipeHandler("gt.recipe.largechemicalreactor")}) loads every recipe of that category, the way NEI's
 * own "all recipes" view does. A node built from such a handler and index is the node NEI's + would have made.
 */
final class GtRecipeSource {

    private GtRecipeSource() {}

    /** Every recipe of the recipe's map (all its categories), read once per map into {@code cache}. */
    static Lookup find(final FfRecipe recipe, final Map<String, List<GameRecipe>> cache) {
        final List<String> maps = mapsFor(recipe);
        if (maps.isEmpty()) {
            final String named = recipe.gameMapId() != null ? recipe.gameMapId() : recipe.machineType();
            return Lookup.none("no GregTech recipe map " + named + " in game");
        }
        final List<GameRecipe> all = new ArrayList<>();
        for (final String map : maps) all.addAll(cache.computeIfAbsent(map, GtRecipeSource::read));
        return Lookup.of(all);
    }

    /**
     * The in-game maps an FF recipe names: by unlocalized name (the website's, whose GT++ maps are gtpp.recipe.*, as
     * well as the game's) or FF's slug of it; failing both (an old plan), by the NEI page's name, which is FF's machine
     * type. A recipe the game posted before it named its map (0.2.0-alpha.5 and earlier) names the machine block
     * instead: the maps that block works, by NEI's catalysts.
     */
    private static List<String> mapsFor(final FfRecipe recipe) {
        final String id = recipe.gameMapId(), slug = recipe.gameMapSlug();
        final Set<String> exact = new LinkedHashSet<>(), named = new LinkedHashSet<>();
        final boolean byMachine = id == null && slug == null && recipe.fromGame();
        for (final ICraftingHandler h : handlers()) {
            if (!(h instanceof final GTNEIDefaultHandler g) || g.getRecipeMap() == null) continue;
            final String map = g.getRecipeMap().unlocalizedName;
            if (sameMap(map, id, slug)) exact.add(map);
            else if (h.getRecipeName()
                .trim()
                .equalsIgnoreCase(recipe.machineType())
                || byMachine && NeiRecipeIndex.runsOn(h, recipe.machineType())) named.add(map);
        }
        return new ArrayList<>(exact.isEmpty() ? named : exact);
    }

    /** Whether the game's map is the one a plan names, by its id or the website's id for it, or by FF's slug. */
    private static boolean sameMap(final String map, @Nullable final String id, @Nullable final String slug) {
        if (id == null && slug == null) return false;
        if (map.equals(id) || slug != null && FfIds.slug(map)
            .equals(slug)) return true;
        final HandlerData.MapEntry site = WebCards.entry(map);
        return site != null && !site.id.equals(map)
            && (site.id.equals(id) || slug != null && FfIds.slug(site.id)
                .equals(slug));
    }

    private static List<ICraftingHandler> handlers() {
        final List<ICraftingHandler> all = new ArrayList<>(GuiCraftingRecipe.craftinghandlers);
        all.addAll(GuiCraftingRecipe.serialCraftingHandlers);
        return all;
    }

    private static List<GameRecipe> read(final String map) {
        final List<GameRecipe> out = new ArrayList<>();
        for (final ICraftingHandler proto : handlers()) {
            if (!(proto instanceof final GTNEIDefaultHandler g) || g.getRecipeMap() == null
                || !map.equals(g.getRecipeMap().unlocalizedName)) continue;
            if (!(g.getRecipeHandler(map) instanceof final GTNEIDefaultHandler loaded)) continue;
            final List<TemplateRecipeHandler.CachedRecipe> cached = RecipeHandlerAccess.getArecipes(loaded);
            for (int i = 0; i < cached.size(); i++) {
                if (cached.get(i) instanceof final CachedDefaultRecipe c && c.mRecipe != null)
                    out.add(of(loaded, i, c.mRecipe));
            }
        }
        return out;
    }

    /**
     * A GregTech recipe as plain data, from the shared reader's record (the export run's, so the time is the one the
     * machine runs and the website's dataset has): every slot with its ids (the item, then its ore names), a kept slot
     * (a circuit, a mold, the Rock Breaker's lava placeholder) as a stack of 1 not used up.
     */
    private static GameRecipe of(final GTNEIDefaultHandler handler, final int index, final GTRecipe r) {
        final GtRecipeRecord read = GTProvider.reader()
            .read(handler.getRecipeMap(), r);
        final List<GameStack> inputs = new ArrayList<>(), outputs = new ArrayList<>();
        for (final GtRecipeRecord.Stack s : read.itemInputs) {
            if (!(s.host instanceof final ItemStack stack)) continue;
            final boolean consumed = !Boolean.FALSE.equals(s.consumed)
                && !com.gtnhplanner.data.provider.FreeInputs.isFreeId(s.id);
            inputs.add(new GameStack("item", GameIds.itemIds(stack), consumed ? s.amount : 1, 1, consumed));
        }
        for (final GtRecipeRecord.Stack s : read.fluidInputs)
            inputs.add(new GameStack("fluid", List.of(s.id), s.amount, 1, true));
        for (final GtRecipeRecord.Stack s : read.itemOutputs) if (s.host instanceof final ItemStack stack)
            outputs.add(new GameStack("item", GameIds.itemIds(stack), s.amount, chance(s), true));
        for (final GtRecipeRecord.Stack s : read.fluidOutputs)
            outputs.add(new GameStack("fluid", List.of(s.id), s.amount, chance(s), true));
        // Newer GregTech keeps a coil recipe's heat in metadata, older in the special value; FF's dataset has it as
        // the special value either way.
        final int special = read.specialValue != 0 ? read.specialValue : read.coilHeat;
        return new GameRecipe(
            handler,
            index,
            handler.getRecipeName()
                .trim(),
            inputs,
            outputs,
            read.durationTicks,
            Math.max(0L, read.eut),
            special);
    }

    private static double chance(final GtRecipeRecord.Stack s) {
        return s.chance != null ? s.chance : 1;
    }
}
