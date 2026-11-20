package com.gtnhplanner.core.model;

import java.util.Objects;

import javax.annotation.Nullable;

/**
 * Which recipe a card runs, as data the plan saves: the host's own identity for it and its place on the page it was read
 * from. The mod's id is NEI's recipe id written as JSON text (the save's {@code recipeId} object), its index the save's
 * {@code handlerRecipeIndex}; the website's id is the dataset recipe id the plan's {@code recipes} list is keyed by,
 * index 0. What the recipe is (its ports, numbers and machines) the card gets from {@link RecipeSource}, never from the
 * reference itself: the two hosts' ids name the same recipe only by content.
 */
public final class RecipeRef {

    /** No recipe: a card the host built by hand (a power card, a test card) or one saved without a recipe id. */
    public static final RecipeRef NONE = new RecipeRef(null, 0);

    @Nullable
    private final String id;
    private final int index;

    private RecipeRef(@Nullable final String id, final int index) {
        this.id = id;
        this.index = index;
    }

    /** A reference to the recipe the host knows by {@code id}, at {@code index} on its page. */
    public static RecipeRef of(@Nullable final String id, final int index) {
        return id == null && index == 0 ? NONE : new RecipeRef(id, index);
    }

    /** The host's identity of the recipe as the plan saves it; null when the card has none. */
    @Nullable
    public String id() {
        return id;
    }

    /** The recipe's place on the page it was read from (the mod's handler recipe index); 0 where pages do not exist. */
    public int index() {
        return index;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof RecipeRef)) return false;
        final RecipeRef r = (RecipeRef) o;
        return index == r.index && Objects.equals(id, r.id);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hashCode(id) + index;
    }

    @Override
    public String toString() {
        return id == null ? "recipe:none#" + index : "recipe:" + id + "#" + index;
    }
}
