package com.gtnhplanner.nei;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import javax.annotation.Nullable;

import codechicken.nei.recipe.GuiCraftingRecipe;
import codechicken.nei.recipe.GuiRecipeTab;
import codechicken.nei.recipe.ICraftingHandler;
import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.Recipe;
import codechicken.nei.recipe.RecipeHandlerRef;
import codechicken.nei.recipe.TemplateRecipeHandler;

/**
 * A card's recipe found again from its saved id. NEI finds most by searching for the recipe's result; a recipe page
 * that has no result in that sense (CropsNH's Crops page) registers a lookup of its own here, tried when NEI's finds
 * nothing.
 */
public final class RecipeRefs {

    private static final List<Function<Recipe.RecipeId, RecipeHandlerRef>> FALLBACKS = new CopyOnWriteArrayList<>();

    /**
     * Recipes with no result found on their page, by id: the board asks for each card on every rebuild. A miss is not
     * kept, so a recipe looked for before NEI has its pages is found once it has.
     */
    private static final Map<Recipe.RecipeId, RecipeHandlerRef> NO_RESULT = new ConcurrentHashMap<>();

    private RecipeRefs() {}

    public static void addFallback(final Function<Recipe.RecipeId, RecipeHandlerRef> lookup) {
        FALLBACKS.add(lookup);
    }

    @Nullable
    public static RecipeHandlerRef of(@Nullable final Recipe.RecipeId id) {
        if (id == null) return null;
        final RecipeHandlerRef ref = RecipeHandlerRef.of(id);
        if (ref != null) return ref;
        for (final Function<Recipe.RecipeId, RecipeHandlerRef> f : FALLBACKS) {
            try {
                final RecipeHandlerRef found = f.apply(id);
                if (found != null) return found;
            } catch (final RuntimeException | LinkageError ignored) {}
        }
        try {
            return withoutResult(id);
        } catch (final RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /**
     * NEI finds a recipe again by its result only, and a recipe whose output NEI does not count as one (an empty bucket
     * or cell, as GregTech's bender makes from a plate) has an id with no result. It is looked for on its own handler's
     * whole page by its ingredients instead, as NEI matches them.
     */
    @Nullable
    private static RecipeHandlerRef withoutResult(final Recipe.RecipeId id) {
        if (id.getResult() != null || id.getHandlerName() == null
            || id.getIngredients()
                .isEmpty())
            return null;
        return NO_RESULT.computeIfAbsent(id, k -> {
            final List<IRecipeHandler> pages = new ArrayList<>();
            final List<ICraftingHandler> handlers = new ArrayList<>(GuiCraftingRecipe.craftinghandlers);
            handlers.addAll(GuiCraftingRecipe.serialCraftingHandlers);
            for (final ICraftingHandler h : handlers) {
                if (h instanceof final TemplateRecipeHandler t && k.getHandlerName()
                    .equals(
                        GuiRecipeTab.getHandlerInfo(h)
                            .getHandlerName()))
                    pages.add(t.getAllRecipeHandler());
            }
            return onItsPage(k, pages);
        });
    }

    /** The first recipe on these pages (each a handler's whole page) with the id's ingredients; null when none has. */
    @Nullable
    static RecipeHandlerRef onItsPage(final Recipe.RecipeId id, final List<? extends IRecipeHandler> pages) {
        for (final IRecipeHandler page : pages) {
            for (int i = 0; i < page.numRecipes(); i++) {
                if (id.equalsIngredients(page.getIngredientStacks(i))) return RecipeHandlerRef.of(page, i);
            }
        }
        return null;
    }
}
