package com.gtnhplanner.nei;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Summary;
import com.gtnhplanner.data.flowchart.balancer.Severity;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.sound.Sfx;

import codechicken.nei.BookmarkPanel;
import codechicken.nei.ItemPanels;
import codechicken.nei.NEIClientConfig;
import codechicken.nei.bookmark.BookmarkGrid;
import codechicken.nei.bookmark.BookmarkGroup;
import codechicken.nei.bookmark.BookmarkItem;
import codechicken.nei.recipe.Recipe;
import codechicken.nei.recipe.RecipeHandlerRef;

/**
 * The Share menu's NEI bookmark rows: the open plan's recipes into the player's NEI bookmarks through NEI's own
 * bookmark panel, the calls its recipe pages make, on the bookmark page open in NEI. Bookmarks already there are never
 * cleared, moved or changed. The bookmark file is written at once, as NEI itself writes it only when the world closes.
 */
public final class PlanBookmarks {

    /** What a bookmark action did: the line to show the player, and whether NEI refused it. */
    public record Outcome(boolean failed, String message) {}

    private PlanBookmarks() {}

    /** The Share menu's "Bookmark recipes": {@link #addRecipes} on the open plan, its line flashed on the board. */
    public static void recipes(final BoardSession session) {
        show(session, addRecipes(session.graph()));
    }

    /** The Share menu's "Bookmark as chain": {@link #addChain} on the open plan, its line flashed on the board. */
    public static void chain(final BoardSession session) {
        show(session, addChain(session.graph()));
    }

    /**
     * Each recipe as a bookmark of its own at the end of the page, as a recipe page's bookmark key adds one (NEI's
     * GuiOverlayButton.saveRecipeInBookmark: the whole recipe, counted only when NEI's "Bookmark recipe with count" is
     * on). A recipe already bookmarked that way is skipped.
     */
    public static Outcome addRecipes(final Graph plan) {
        final BookmarkPlan.Pick<Recipe> pick = pick(plan);
        int added = 0, already = 0;
        try {
            final BookmarkPanel panel = panel();
            final int multiplier = withCount() ? 1 : 0;
            for (final Recipe r : pick.recipes()) {
                if (panel.addRecipe(r, multiplier, BookmarkGrid.DEFAULT_GROUP_ID)) added++;
                else already++;
            }
            if (added > 0) panel.save();
        } catch (final RuntimeException | LinkageError e) {
            return failed(e);
        }
        return new Outcome(false, BookmarkPlan.added(added, already, pick.skipped()));
    }

    /**
     * The plan's recipes as one new bookmark group in crafting chain mode, one run each, as a recipe page's favourite
     * star bookmarks a recipe tree (NEI's GuiFavoriteButton.saveRecipeInBookmark): NEI's chain links each ingredient to
     * the recipe in the group that makes it, takes what nothing in the group uses as the chain's outputs, works out
     * the runs, and opens its crafting tree on the group. Nothing is added when a chain on the page already holds every
     * recipe.
     */
    public static Outcome addChain(final Graph plan) {
        final BookmarkPlan.Pick<Recipe> pick = pick(plan);
        boolean already = false;
        try {
            final BookmarkPanel panel = panel();
            final List<Recipe.RecipeId> ids = new ArrayList<>();
            for (final Recipe r : pick.recipes()) ids.add(r.getRecipeId());
            already = BookmarkPlan.chainThere(ids, chains(panel.getGrid()));
            if (!already && !pick.recipes()
                .isEmpty()) {
                panel.addGroup(new ArrayList<>(pick.recipes()), BookmarkPanel.BookmarkViewMode.TODO_LIST, true);
                panel.save();
            }
        } catch (final RuntimeException | LinkageError e) {
            return failed(e);
        }
        return new Outcome(
            false,
            BookmarkPlan.chained(
                pick.recipes()
                    .size(),
                already,
                pick.skipped()));
    }

    /**
     * {@code /gtnhplanner bookmark <id>}: what the plan takes in from outside and what it gives out, as its solve's
     * totals have them, each as an item bookmark at the end of the page (a fluid as the display item NEI shows for
     * it), as NEI's bookmark key adds one. One already bookmarked is counted and left as it is.
     */
    public static Outcome addShoppingList(final Graph plan) {
        final ShoppingList.Pick<ItemStack> pick;
        int inputs = 0, outputs = 0, already = 0;
        try {
            final Summary totals = new Summary();
            totals.setMode(Summary.Mode.THROUGHPUT);
            totals.recompute(plan);
            pick = ShoppingList.pick(
                stacks(totals.lines(Summary.Section.INPUTS)),
                stacks(totals.lines(Summary.Section.OUTPUTS)),
                com.gtnhplanner.ui.Resources::keyOf);
            final BookmarkPanel panel = panel();
            for (final ItemStack s : pick.inputs()) {
                if (panel.addItem(s)) inputs++;
                else already++;
            }
            for (final ItemStack s : pick.outputs()) {
                if (panel.addItem(s)) outputs++;
                else already++;
            }
            if (inputs + outputs > 0) panel.save();
        } catch (final RuntimeException | LinkageError e) {
            return failed(e);
        }
        return new Outcome(false, ShoppingList.added(plan.getName(), inputs, outputs, already, pick.skipped()));
    }

    /** Each line's stack as NEI shows it; null for a line the game has no item for (power, a card shown only). */
    private static List<ItemStack> stacks(final List<Summary.Line<?>> lines) {
        final List<ItemStack> stacks = new ArrayList<>();
        for (final Summary.Line<?> line : lines) {
            if (!(line instanceof final Summary.Line.Measure<?> m)
                || !(m.label() instanceof final com.gtnhplanner.data.properties.ResourceProperty<?> type)) continue;
            @SuppressWarnings("unchecked")
            final Object stack = ((com.gtnhplanner.data.properties.ResourceProperty<Object>) type)
                .displayStack(m.resource());
            stacks.add(stack instanceof final ItemStack s ? s.copy() : null);
        }
        return stacks;
    }

    private static BookmarkPlan.Pick<Recipe> pick(final Graph plan) {
        return BookmarkPlan.pick(plan.nodes.values(), PlanBookmarks::recipeOf, Recipe::getRecipeId);
    }

    /** The card's recipe as NEI's recipe page holds it; null when NEI cannot find it. */
    @Nullable
    private static Recipe recipeOf(final Node node) {
        try {
            final RecipeHandlerRef ref = NeiRecipes.page(node);
            return ref == null ? null : Recipe.of(ref);
        } catch (final RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** The recipes in each crafting chain on the page open in NEI's bookmarks. */
    static List<Set<Recipe.RecipeId>> chains(final BookmarkGrid grid) {
        final Map<Integer, Set<Recipe.RecipeId>> chains = new LinkedHashMap<>();
        for (int i = 0; i < grid.size(); i++) {
            final BookmarkItem item = grid.getBookmarkItem(i);
            if (item == null || item.recipeId == null || item.type == BookmarkItem.BookmarkItemType.ITEM) continue;
            final BookmarkGroup group = grid.getGroup(item.groupId);
            if (group == null || group.crafting == null) continue;
            chains.computeIfAbsent(item.groupId, k -> new HashSet<>())
                .add(item.recipeId);
        }
        return new ArrayList<>(chains.values());
    }

    /**
     * NEI's bookmark panel on the page its file holds now. The panel picks up the page NEI loaded for this world only
     * when NEI's overlay next draws; until then it holds a page the file never sees, so bookmarks added to it would be
     * neither saved nor shown.
     */
    public static BookmarkPanel panel() {
        final BookmarkPanel panel = ItemPanels.bookmarkPanel;
        panel.update();
        return panel;
    }

    private static boolean withCount() {
        try {
            return NEIClientConfig.getBooleanSetting("inventory.bookmarks.bookmarkRecipeWithCount");
        } catch (final RuntimeException e) {
            return false;
        }
    }

    private static Outcome failed(final Throwable e) {
        GtnhPlanner.LOG.warn("Could not add NEI bookmarks", e);
        return new Outcome(true, "Couldn't add the bookmarks");
    }

    private static void show(final BoardSession session, final Outcome outcome) {
        session.flash(outcome.failed() ? Severity.WARN : Severity.INFO, outcome.message());
        if (outcome.failed()) Sfx.DENY.play();
    }
}
