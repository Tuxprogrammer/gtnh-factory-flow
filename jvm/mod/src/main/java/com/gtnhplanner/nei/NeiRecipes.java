package com.gtnhplanner.nei;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.api.RecipePropertyAPI;
import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.core.model.RecipeRef;
import com.gtnhplanner.core.model.RecipeSource;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.properties.PropertyProvider;
import com.gtnhplanner.data.properties.RecipeProperty;
import com.gtnhplanner.data.provider.DefaultProvider;
import com.gtnhplanner.data.provider.GTKeys;
import com.gtnhplanner.machines.game.WebCards;
import com.gtnhplanner.machines.web.HandlerData;
import com.gtnhplanner.machines.web.Web;
import com.gtnhplanner.power.game.PowerPorts;

import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.Recipe;
import codechicken.nei.recipe.RecipeHandlerRef;

/**
 * The mod's {@link RecipeSource}: a card's recipe is an NEI page, read by the per-mod property readers
 * ({@link PropertyProvider}, registered by handler class in {@link RecipePropertyAPI}). A card's {@link RecipeRef}
 * holds
 * NEI's recipe id as the JSON text the save writes and its index on the page; {@link Node#hostRecipe} holds a
 * {@link Hold}: the live NEI id, the page the card was made from while there is one (a page NEI cannot find again by
 * id), and the reader. A card saved with a recipe NEI cannot find on load has no reader and reads nothing ever after,
 * as before: it keeps what it had.
 */
public final class NeiRecipes implements RecipeSource {

    public static final NeiRecipes INSTANCE = new NeiRecipes();

    private NeiRecipes() {}

    /** What the mod holds of a card's recipe. Never saved; one per card. */
    public static final class Hold {

        @Nullable
        final Recipe.RecipeId id;
        /**
         * The page the card was made from, for its reads (a page NEI cannot find again by id); null on a loaded card.
         */
        @Nullable
        RecipeHandlerRef madeFrom;
        /** The reader of the recipe; null when NEI could not find it, and then the card reads nothing. */
        @Nullable
        PropertyProvider extractor;

        Hold(@Nullable final Recipe.RecipeId id) {
            this.id = id;
        }
    }

    /** A new card on NEI's recipe {@code recipeIndex} of {@code handler}, read at once. */
    public static Node node(final IRecipeHandler handler, final int recipeIndex, final int x, final int y) {
        final Uuid uuid = Uuid.randomUUID();
        final Recipe.RecipeId id = Recipe.RecipeId.of(handler, recipeIndex);
        final Hold hold = hold(id, handler, recipeIndex);
        final String pid = hold.extractor.getProfileId(handler, recipeIndex);
        return new Node(
            uuid,
            x,
            y,
            handler.getRecipeName()
                .trim(),
            ref(id, recipeIndex),
            hold,
            pid);
    }

    /**
     * The card on another recipe (a crusher's mob, a crop): its id, place, machine, settings and count kept, its ports
     * read afresh. Following its wires to where their resources now are is the caller's ({@code BoardSession}).
     */
    public static void remake(final Node node, final IRecipeHandler handler, final int recipeIndex) {
        final Recipe.RecipeId id = Recipe.RecipeId.of(handler, recipeIndex);
        node.recipe = ref(id, recipeIndex);
        node.hostRecipe = hold(id, handler, recipeIndex);
        node.inputPicks.clear();
        node.refresh();
    }

    /**
     * A loaded card's recipe from its save: NEI's id as the save wrote it (null when it wrote none) and the page index.
     * A recipe card ({@code reads}) is looked up in NEI now; one NEI cannot find reads nothing.
     */
    public static void load(final Node node, @Nullable final JsonObject saved, final int index, final boolean reads) {
        final Recipe.RecipeId id = saved == null ? null : Recipe.RecipeId.of(saved);
        node.recipe = RecipeRef.of(
            id == null ? null
                : id.toJsonObject()
                    .toString(),
            index);
        final Hold hold = new Hold(id);
        node.hostRecipe = hold;
        if (!reads) return;
        final RecipeHandlerRef ref = RecipeRefs.of(id);
        if (ref == null) return;
        final List<PropertyProvider> available = RecipePropertyAPI.getExtractors(ref.handler.getClass());
        hold.extractor = available.isEmpty() ? DefaultProvider.INSTANCE : available.getFirst();
    }

    /** NEI's id of the card's recipe, as the save writes it; null when the card has none. */
    @Nullable
    public static JsonObject json(final Node node) {
        final Recipe.RecipeId id = id(node);
        if (id != null) return id.toJsonObject();
        final String text = node.recipe.id();
        return text == null ? null
            : new JsonParser().parse(text)
                .getAsJsonObject();
    }

    /** NEI's live id of the card's recipe; null on a card with none (a power card, a hand-built one). */
    @Nullable
    public static Recipe.RecipeId id(@Nullable final Node node) {
        return node != null && node.hostRecipe instanceof final Hold h ? h.id : null;
    }

    /** The card's recipe page as NEI finds it again by id now; null when it cannot. */
    @Nullable
    public static RecipeHandlerRef page(final Node node) {
        return RecipeRefs.of(id(node));
    }

    private static RecipeRef ref(final Recipe.RecipeId id, final int index) {
        return RecipeRef.of(
            id.toJsonObject()
                .toString(),
            index);
    }

    private static Hold hold(final Recipe.RecipeId id, final IRecipeHandler handler, final int recipeIndex) {
        final Hold hold = new Hold(id);
        hold.madeFrom = RecipeHandlerRef.of(handler, recipeIndex);
        final List<PropertyProvider> available = RecipePropertyAPI.getExtractors(handler.getClass());
        hold.extractor = available.isEmpty() ? DefaultProvider.INSTANCE : best(available, handler, recipeIndex);
        return hold;
    }

    private static PropertyProvider best(final List<PropertyProvider> available, final IRecipeHandler handler,
        final int recipeIndex) {
        for (final PropertyProvider p : available) {
            if (p.canCraft(handler, recipeIndex)) return p;
        }
        return available.getFirst();
    }

    @Override
    public boolean readRecipe(final Node card) {
        if (!(card.hostRecipe instanceof final Hold hold) || hold.extractor == null) return false;
        // The page it was made from, while there is one; else found again by its id. Not found: left as it is.
        final RecipeHandlerRef ref = hold.madeFrom != null ? hold.madeFrom : RecipeRefs.of(hold.id);
        if (ref == null) return false;
        final IRecipeHandler handler = ref.handler;
        final int recipeIndex = ref.recipeIndex;
        card.inputs.clear();
        card.outputs.clear();
        card.properties.clear();

        final Map<RecipeProperty<?>, Object> props = hold.extractor.extract(card, handler, recipeIndex);
        if (props != null && !props.isEmpty()) {
            card.properties.putAll(props);
        }

        SlotAlternatives.read(card.inputs, handler, recipeIndex);
        return true;
    }

    @Override
    public void readPower(final Node card) {
        PowerPorts.build(card);
    }

    /**
     * The card's recipe as the dataset writes one: a GregTech or crafting card as the website's machine maths read it
     * ({@link WebCards#recipe}), any other NEI page from its ports and its reader's numbers. Built afresh on every call.
     */
    @Nullable
    @Override
    public Web.Recipe record(final Node card) {
        if (card.isPower()) return null;
        // A card NEI could not find was never read: it has nothing to say about its recipe.
        if (!(card.hostRecipe instanceof final Hold hold) || hold.extractor == null) return null;
        final Web.Recipe gt = WebCards.recipe(card);
        if (gt != null) return gt;
        final JsonObject id = json(card);
        if (id == null) return null;
        final Web.Recipe r = new Web.Recipe();
        // From the id's JSON: RecipeId.getHandlerName is missing from the older NEI some supported packs ship.
        final String handler = id.has("handlerName") ? id.get("handlerName")
            .getAsString() : "";
        r.id = "nei:" + handler + "#" + card.recipe.index();
        r.name = card.machineName;
        // The dataset has no kind for an NEI page outside its exported maps.
        r.kind = "unknown";
        r.category = handler;
        r.machineType = card.machineName;
        r.durationTicks = number(card.properties.get(RecipeProperty.DURATION_TICKS));
        r.eut = number(card.properties.get(GTKeys.EU_PER_TICK));
        r.minimumTier = HandlerData.recipeMinimumTier(r.eut);
        r.inputs = WebCards.resources(card.inputs);
        r.outputs = WebCards.resources(card.outputs);
        r.source = new Web.Source();
        r.source.recipeMap = card.machineName;
        // The reader's other numbers (a mana cost, an RF total), by property key.
        final Map<String, Object> facts = new LinkedHashMap<>();
        final List<RecipeProperty<?>> keys = new ArrayList<>(card.properties.keySet());
        keys.sort((a, b) -> a.getKey()
            .compareTo(b.getKey()));
        for (final RecipeProperty<?> p : keys) {
            final Object v = card.properties.get(p);
            if (p == RecipeProperty.DURATION_TICKS) continue;
            if (v instanceof final Number n) facts.put(p.getKey(), n.doubleValue());
            else if (v instanceof String || v instanceof Boolean) facts.put(p.getKey(), v);
        }
        r.metadata = new LinkedHashMap<>();
        r.metadata.put("neiHandler", handler);
        if (!facts.isEmpty()) r.metadata.put("properties", facts);
        return r;
    }

    private static double number(@Nullable final Object value) {
        return value instanceof final Number n ? n.doubleValue() : 0;
    }
}
