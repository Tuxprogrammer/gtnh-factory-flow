package com.gtnhplanner.dev;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.google.gson.JsonElement;
import com.gtnhplanner.nei.PlanBookmarks;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.Notice;

import codechicken.nei.bookmark.BookmarkGrid;
import codechicken.nei.bookmark.BookmarkGroup;
import codechicken.nei.bookmark.BookmarkItem;
import codechicken.nei.recipe.Recipe;
import codechicken.nei.recipe.chain.RecipeChainMath;

/**
 * {@code /bookmarks}: NEI's bookmark page open now, as NEI holds it: each group (its id, whether it is a crafting
 * chain, its view), its recipes (handler and first result) and items, every item in order, and for a chain the recipes
 * NEI's own chain maths takes as its outputs. {@code press=recipes|chain} runs the Share menu's row on the open plan
 * first and reports the line it flashed; {@code seed=<item name>} first bookmarks that item as a player would and saves
 * the file, to check the rows leave a player's own bookmarks alone. Client thread.
 */
final class DevBookmarks {

    private DevBookmarks() {}

    static Object report(final BoardSession session, final String press, final String seed) {
        if (seed != null) {
            final Object item = Item.itemRegistry.getObject(seed);
            if (!(item instanceof final Item i)) return Map.of("error", "no item " + seed);
            PlanBookmarks.panel()
                .addItem(new ItemStack(i));
            PlanBookmarks.panel()
                .save();
        }
        if ("recipes".equals(press)) PlanBookmarks.recipes(session);
        else if ("chain".equals(press)) PlanBookmarks.chain(session);
        final BookmarkGrid grid = PlanBookmarks.panel()
            .getGrid();
        final Map<Integer, Map<String, Object>> groups = new LinkedHashMap<>();
        final List<String> order = new ArrayList<>();
        for (int i = 0; i < grid.size(); i++) {
            final BookmarkItem item = grid.getBookmarkItem(i);
            if (item == null) continue;
            final ItemStack stack = item.getItemStack();
            order.add(
                "g" + item.groupId
                    + " "
                    + item.type
                    + " "
                    + (stack == null ? "?" : stack.getDisplayName())
                    + (item.recipeId == null ? "" : " @" + handler(item.recipeId)));
            final Map<String, Object> g = groups.computeIfAbsent(item.groupId, id -> group(grid, id));
            g.merge("items", 1, (a, b) -> (Integer) a + (Integer) b);
            if (item.recipeId == null || item.type != BookmarkItem.BookmarkItemType.RESULT) continue;
            @SuppressWarnings("unchecked")
            final List<String> recipes = (List<String>) g.get("recipes");
            final String r = name(item.recipeId);
            if (!recipes.contains(r)) recipes.add(r);
        }
        for (final Map.Entry<Integer, Map<String, Object>> e : groups.entrySet()) {
            if (!Boolean.TRUE.equals(e.getValue()
                .get("crafting"))) continue;
            final RecipeChainMath math = grid.createRecipeChainMath(e.getKey());
            final List<String> outputs = new ArrayList<>();
            if (math != null) for (final Recipe.RecipeId id : math.outputRecipes.keySet()) outputs.add(name(id));
            e.getValue()
                .put("chainOutputs", outputs);
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        final List<Notice> notices = session.notices();
        out.put("message", press == null || notices.isEmpty() ? null : notices.get(0)
            .text());
        out.put("total", grid.size());
        out.put("groups", new ArrayList<>(groups.values()));
        out.put("order", order);
        return out;
    }

    private static Map<String, Object> group(final BookmarkGrid grid, final int id) {
        final BookmarkGroup group = grid.getGroup(id);
        final Map<String, Object> g = new LinkedHashMap<>();
        g.put("id", id);
        g.put("crafting", group != null && group.crafting != null);
        g.put("view", group == null ? null : String.valueOf(group.viewMode));
        g.put("items", 0);
        g.put("recipes", new ArrayList<String>());
        return g;
    }

    /** The handler's name as NEI saves it; read from its JSON, as some pack builds' RecipeId has no getter for it. */
    private static String handler(final Recipe.RecipeId id) {
        final JsonElement name = id.toJsonObject()
            .get("handlerName");
        return name == null ? "?" : name.getAsString();
    }

    private static String name(final Recipe.RecipeId id) {
        final ItemStack result = id.getResult();
        return handler(id) + " -> " + (result == null ? "?" : result.getDisplayName());
    }
}
