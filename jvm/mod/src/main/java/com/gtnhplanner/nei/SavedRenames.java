package com.gtnhplanner.nei;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.read.postea.PosteaRenames;

/**
 * A plan of this world's save read under the game's item renames (Postea's simple replacements, the table the website
 * and account plans are read with: {@code importer/PlanRenames}). A save names each card's recipe by NEI's id, items
 * and all, so a recipe saved before the pack renamed one of its items (IC2's plantball, now a GregTech meta item) is
 * one the game no longer has under that id.
 *
 * <p>
 * The rule is the website's: a card whose saved recipe the game finds as it is keeps it, and the items it names are
 * kept everywhere in the plan. A card the game cannot find as saved is looked for again with its renamed items, and
 * reads the renamed recipe only when the game has that one; found neither way, it keeps what it saved, as a card the
 * game has no recipe for. The plan's other mentions of a renamed item (a drawer's resource, an input's pick, a
 * leftover's split) follow, unless a card found as saved still names it. Card, wire and drawer ids never change, so
 * wires, drawers and world links stay on the same cards. The save is not touched: a plan is read this way on every
 * load, and is written with the new ids the next time it is saved after an edit.
 */
public final class SavedRenames {

    /** The game's renames, old resource id to new ({@link PosteaRenames#table()}); tests stand one in. */
    static Supplier<Map<String, String>> table = com.gtnhplanner.importer.game.GameRenames::table;
    /** Whether the game has the recipe a saved NEI id names; tests stand one in. */
    static Predicate<JsonObject> found = json -> RecipeRefs.of(NeiRecipes.ids.apply(json)) != null;
    /** The item registry's own spelling of a lowercased item name (the table is lowercased; the registry is not). */
    static Function<String, String> registryName = SavedRenames::itemName;

    private SavedRenames() {}

    /** One plan's read: the renames, and what its cards decided. */
    public static final class Load {

        private final Map<String, String> table;
        /** Item ids (the table's spelling) that a card found as saved names: kept everywhere in the plan. */
        private final Set<String> kept = new HashSet<>();

        private Load(final Map<String, String> table) {
            this.table = table;
        }

        /**
         * The NEI id a card is read with: its saved one, or the saved one with the renamed items moved when the game
         * finds only that. {@code saved} itself when nothing moves (no renames, nothing renamed in it, found as it is,
         * or found neither way).
         */
        @Nullable
        public JsonObject recipe(@Nullable final JsonObject saved) {
            if (saved == null || table.isEmpty()) return saved;
            final Set<String> named = new HashSet<>();
            for (final JsonObject item : items(saved)) {
                final String id = itemId(item);
                if (id != null && !PosteaRenames.rename(table, id)
                    .equals(id)) named.add(id);
            }
            if (named.isEmpty()) return saved;
            if (found.test(saved)) {
                kept.addAll(named);
                return saved;
            }
            final JsonObject moved = new JsonParser().parse(saved.toString())
                .getAsJsonObject();
            for (final JsonObject item : items(moved)) {
                final String id = itemId(item);
                if (id == null || !named.contains(id)) continue;
                final String to = PosteaRenames.rename(table, id);
                // Any meta (a wildcard) stays as the save has it.
                if (to.endsWith("@*")) {
                    item.addProperty("strId", spelled(to.substring(0, to.length() - 2)));
                    continue;
                }
                final int at = to.lastIndexOf('@');
                final boolean meta = at > 0 && to.substring(at + 1)
                    .matches("\\d{1,9}");
                item.addProperty("strId", spelled(meta ? to.substring(0, at) : to));
                item.addProperty("Damage", meta ? Integer.parseInt(to.substring(at + 1)) : 0);
            }
            return found.test(moved) ? moved : saved;
        }

        /**
         * A resource key ({@code item:<registry name>:<meta>}) under the renames, unless a card found as saved still
         * names the item; the key itself otherwise.
         */
        public String key(@Nullable final String key) {
            return key(key, true);
        }

        private String key(@Nullable final String key, final boolean keep) {
            if (key == null || table.isEmpty() || !key.startsWith("item:")) return key;
            final int colon = key.lastIndexOf(':');
            if (colon <= "item:".length()) return key;
            final String metaText = key.substring(colon + 1);
            if (!metaText.matches("\\d{1,9}")) return key;
            final int meta = Integer.parseInt(metaText);
            final String id = resourceId(key.substring("item:".length(), colon), meta);
            if (keep && kept.contains(id)) return key;
            final String to = PosteaRenames.rename(table, id);
            if (to.equals(id) || to.endsWith("@*")) return key;
            final int at = to.lastIndexOf('@');
            final boolean hasMeta = at > 0 && to.substring(at + 1)
                .matches("\\d{1,9}");
            return "item:" + spelled(hasMeta ? to.substring(0, at) : to) + ":" + (hasMeta ? to.substring(at + 1) : "0");
        }

        /** A card's input picks (input index to resource key) under the renames: the card's recipe moved. */
        public void picks(final Map<Integer, String> picks) {
            if (table.isEmpty()) return;
            for (final Map.Entry<Integer, String> p : picks.entrySet()) p.setValue(key(p.getValue(), false));
        }

        /**
         * The plan's mentions of an item outside its cards' recipes, once every card is read: each drawer's resource
         * and each leftover split's key.
         */
        public void finish(final Graph graph) {
            if (table.isEmpty()) return;
            for (final Drawer d : graph.getDrawers()) {
                final String to = key(d.getResourceKey());
                if (!to.equals(d.getResourceKey())) d.setResourceKey(to);
            }
            if (graph.spareShares.isEmpty()) return;
            final Map<String, Map<String, Double>> moved = new LinkedHashMap<>();
            for (final Map.Entry<String, Map<String, Double>> e : graph.spareShares.entrySet())
                moved.merge(key(e.getKey()), e.getValue(), (a, b) -> a);
            if (moved.keySet()
                .equals(graph.spareShares.keySet())) return;
            graph.spareShares.clear();
            graph.spareShares.putAll(new TreeMap<>(moved));
        }
    }

    /** A read under the game's renames now. */
    public static Load begin() {
        Map<String, String> t;
        try {
            t = table.get();
        } catch (final RuntimeException | LinkageError e) {
            // No game to ask (a headless tool): read as saved.
            t = Map.of();
        }
        return new Load(t == null ? Map.of() : t);
    }

    /** The saved id's item entries: its ingredients and its result. */
    private static Iterable<JsonObject> items(final JsonObject id) {
        final java.util.List<JsonObject> out = new java.util.ArrayList<>();
        if (id.has("ingredients") && id.get("ingredients")
            .isJsonArray()) {
            final JsonArray in = id.getAsJsonArray("ingredients");
            for (final JsonElement e : in) if (e.isJsonObject()) out.add(e.getAsJsonObject());
        }
        if (id.has("result") && id.get("result")
            .isJsonObject()) out.add(id.getAsJsonObject("result"));
        return out;
    }

    /** An item entry's resource id (the table's spelling: lowercased, {@code @meta} unless 0); null for a fluid. */
    @Nullable
    private static String itemId(final JsonObject item) {
        if (!item.has("strId") || !item.get("strId")
            .isJsonPrimitive()) return null;
        int meta = 0;
        if (item.has("Damage") && item.get("Damage")
            .isJsonPrimitive()) {
            final JsonPrimitive d = item.getAsJsonPrimitive("Damage");
            if (d.isNumber()) meta = d.getAsInt();
            else {
                // NBT's text form carries its type as a suffix (0s): the digits are the meta.
                final String digits = d.getAsString()
                    .replaceAll("[^0-9-]", "");
                if (digits.isEmpty()) return null;
                try {
                    meta = Integer.parseInt(digits);
                } catch (final NumberFormatException e) {
                    return null;
                }
            }
        }
        return resourceId(
            item.get("strId")
                .getAsString(),
            meta);
    }

    private static String resourceId(final String name, final int meta) {
        final String lower = name.trim()
            .toLowerCase(Locale.ROOT);
        return meta == 0 ? lower : lower + "@" + meta;
    }

    /** The registry's spelling of a lowercased name, or the name itself when the registry has none. */
    private static String spelled(final String lower) {
        final String name = registryName.apply(lower);
        return name == null ? lower : name;
    }

    @Nullable
    private static String itemName(final String lower) {
        try {
            if (net.minecraft.item.Item.itemRegistry.getObject(lower) instanceof net.minecraft.item.Item) return lower;
            for (final Object key : net.minecraft.item.Item.itemRegistry.getKeys()) if (String.valueOf(key)
                .equalsIgnoreCase(lower)) return String.valueOf(key);
        } catch (final RuntimeException | LinkageError ignored) {}
        return null;
    }
}
