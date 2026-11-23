package com.gtnhplanner.importer;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.gtnhplanner.importer.FfPlan.FfRecipe;
import com.gtnhplanner.importer.FfPlan.FfSlot;
import com.gtnhplanner.read.postea.PosteaRenames;

/**
 * A plan saved before the game renamed some items, read with the new ids: the website's
 * {@code withRenamedProjectResources} (src/lib/datasets/resource-renames.ts) on the plan's JSON, the game's recipes
 * standing in for the website's dataset.
 *
 * <p>
 * The renames are the game's own (Postea's simple item replacements, {@link RecipeIndex#renames()}). A card's recipe
 * that names a renamed item and that the game matches as it is stays as it is, and every item it names is kept
 * everywhere: its wires must keep meeting it (2.9.1 still has recipes on IC2's plantball). Every other card's recipe
 * that names a renamed item is the plan's own copy, which the game would convert: its body is renamed, and so is every
 * other mention outside the recipes (wire resources and handles, drawers, picks, port-rate and leftover keys, the
 * plan's
 * icon), each object that moved taking the new item's name and picture from a recipe that names it. The game's own data
 * ({@code gtnhplanner}) is never touched. Unchanged parts keep their identity, so a live read still knows them.
 */
public final class PlanRenames {

    /** Handles and port keys carry {@code item:<encodeURIComponent(id)>}, ended by ':', '|', U+001F or the end. */
    private static final Pattern ENCODED = Pattern.compile("(^|[:|\\u001f])item:([^:|\\u001f]+)(?=$|[:|\\u001f])");
    private static final String[] FACE_KEYS = { "displayName", "iconPath", "dominantColor", "iconAtlas" };

    private final Map<String, String> table;
    /** Item ids a card the game matched as it is still names. */
    private final Set<String> used = new HashSet<>();
    /** The first slot naming each item id in the plan's recipes: its face. */
    private final Map<String, JsonObject> faces = new HashMap<>();
    /** The memo's things from the last apply, and this apply's. */
    private final Map<JsonElement, JsonElement> was, now = new IdentityHashMap<>();

    private PlanRenames(final Map<String, String> table, final Map<JsonElement, JsonElement> was) {
        this.table = table;
        this.was = was;
    }

    /**
     * What a plan's renames came to, kept across the reads of one live plan so the migration runs once per load: a
     * thing of the plan (a card, wire, drawer, recipe, plan field) that is the very object it was last time is renamed
     * to the very object it was renamed to then, and a recipe's verdict (as it is, or the plan's own copy) is not
     * worked out again. A live read then knows the renamed things as the previous plan's own and does not read them
     * afresh. What a rename hangs on besides the thing itself (the table, the ids kept, the faces) is checked on every
     * apply, and the things are forgotten when it moves. Only the last apply's things are kept. One plan and one game
     * per memo.
     */
    public static final class Memo {

        private Map<String, String> table = Map.of();
        private Set<String> used = Set.of();
        private Map<String, JsonObject> faces = Map.of();
        private Map<JsonElement, JsonElement> things = new IdentityHashMap<>();
        private Map<JsonObject, Verdict> verdicts = new IdentityHashMap<>();
    }

    /** A recipe on the board that names a renamed item: the plan's own copy, or the ids the game's own still names. */
    private record Verdict(boolean own, List<String> used) {

        static final Verdict NONE = new Verdict(false, List.of());
    }

    /**
     * {@code plan} with the renamed items moved to their new ids, or {@code plan} itself when no card needs it (no
     * renames, or every card that names one matches as it is).
     */
    public static JsonObject apply(final JsonObject plan, final RecipeIndex index) {
        return apply(plan, index, new Memo());
    }

    /** {@link #apply(JsonObject, RecipeIndex)}, reusing what {@code memo} kept from the same plan's last apply. */
    public static JsonObject apply(final JsonObject plan, final RecipeIndex index, final Memo memo) {
        final Map<String, String> table = index.renames();
        if (table.isEmpty() || !plan.has("recipes")
            || !plan.get("recipes")
                .isJsonArray())
            return plan;
        if (!table.equals(memo.table)) {
            memo.table = table;
            memo.verdicts = new IdentityHashMap<>();
            memo.things = new IdentityHashMap<>();
        }
        final Set<String> onBoard = new HashSet<>();
        for (final JsonObject node : objects(plan, "nodes")) {
            final String id = str(node, "recipeId");
            if (id != null) onBoard.add(id);
            for (final JsonObject extra : objects(node, "extraRecipes")) {
                final String extraId = str(extra, "recipeId");
                if (extraId != null) onBoard.add(extraId);
            }
        }
        final PlanRenames r = new PlanRenames(table, memo.things);
        final Set<String> own = new HashSet<>();
        final Map<JsonObject, Verdict> verdicts = new IdentityHashMap<>();
        for (final JsonObject json : objects(plan, "recipes")) {
            final String id = str(json, "id");
            if (id == null || !onBoard.contains(id)) continue;
            Verdict v = memo.verdicts.get(json);
            if (v == null) v = r.verdict(json, index);
            verdicts.put(json, v);
            if (v.own()) own.add(id);
            else r.used.addAll(v.used());
        }
        memo.verdicts = verdicts;
        if (own.isEmpty()) {
            memo.things = new IdentityHashMap<>();
            return plan;
        }
        for (final JsonObject json : objects(plan, "recipes"))
            for (final String side : new String[] { "inputs", "outputs" })
                for (final JsonObject slot : objects(json, side)) {
                    final String slotId = str(slot, "id");
                    if (slotId != null && "item".equals(str(slot, "kind")) && str(slot, "displayName") != null)
                        r.faces.putIfAbsent(slotId, slot);
                }
        // A thing's rename hangs on the ids kept and the faces too: when they moved, nothing is reused.
        if (!r.used.equals(memo.used) || !r.faces.equals(memo.faces)) r.was.clear();
        memo.used = r.used;
        memo.faces = r.faces;
        memo.things = r.now;
        final JsonObject out = new JsonObject();
        boolean changed = false;
        for (final Map.Entry<String, JsonElement> e : plan.entrySet()) {
            JsonElement next = e.getValue();
            if ("recipes".equals(e.getKey())) {
                final JsonArray recipes = e.getValue()
                    .getAsJsonArray();
                JsonArray copy = null;
                for (int i = 0; i < recipes.size(); i++) {
                    final JsonElement recipe = recipes.get(i);
                    final JsonElement renamed = recipe.isJsonObject()
                        && own.contains(str(recipe.getAsJsonObject(), "id")) ? r.thing(recipe) : recipe;
                    if (renamed != recipe && copy == null) {
                        copy = new JsonArray();
                        for (int j = 0; j < i; j++) copy.add(recipes.get(j));
                    }
                    if (copy != null) copy.add(renamed);
                }
                if (copy != null) next = copy;
            } else if (!"gtnhplanner".equals(e.getKey())) next = r.top(e.getValue());
            final String key = r.renameString(e.getKey());
            changed |= next != e.getValue() || !key.equals(e.getKey());
            out.add(key, next);
        }
        return changed ? out : plan;
    }

    /** A recipe on the board: whether it names a renamed item, and if so whether the game matches it as it is. */
    private Verdict verdict(final JsonObject json, final RecipeIndex index) {
        final FfRecipe recipe = FfPlanParser.recipe(json);
        if (recipe == null || !namesRenamed(recipe)) return Verdict.NONE;
        if (!matchesAsItIs(recipe, index)) return new Verdict(true, List.of());
        final java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        for (final FfSlot s : slots(recipe)) if ("item".equals(s.kind())) {
            ids.add(s.id());
            ids.add(FfIds.normalize(s.id()));
        }
        return new Verdict(false, ids);
    }

    /** Whether the game finds the recipe as the plan has it, as the importer would ({@code FfCards.section}). */
    private static boolean matchesAsItIs(final FfRecipe recipe, final RecipeIndex index) {
        final RecipeIndex.Lookup lookup = index.find(recipe);
        if (recipe.isCrop() || recipe.isBee() || recipe.isEec() || lookup.exact()) return !lookup.candidates()
            .isEmpty();
        return RecipeMatcher.match(recipe, Map.of(), lookup.candidates())
            .accepted();
    }

    private boolean namesRenamed(final FfRecipe recipe) {
        for (final FfSlot s : slots(recipe)) {
            final String id = FfIds.normalize(s.id());
            if ("item".equals(s.kind()) && !PosteaRenames.rename(table, id)
                .equals(id)) return true;
        }
        return false;
    }

    private static List<FfSlot> slots(final FfRecipe recipe) {
        final java.util.ArrayList<FfSlot> all = new java.util.ArrayList<>(recipe.inputs());
        all.addAll(recipe.outputs());
        return all;
    }

    // region The walk (the website's, string by string)

    /** A plan field's value: an array thing by thing, anything else as one thing. */
    private JsonElement top(final JsonElement value) {
        if (!value.isJsonArray()) return value.isJsonObject() ? thing(value) : walk(value);
        final JsonArray array = value.getAsJsonArray();
        JsonArray copy = null;
        for (int i = 0; i < array.size(); i++) {
            final JsonElement item = array.get(i);
            final JsonElement next = item.isJsonObject() || item.isJsonArray() ? thing(item) : walk(item);
            if (next != array.get(i) && copy == null) {
                copy = new JsonArray();
                for (int j = 0; j < i; j++) copy.add(array.get(j));
            }
            if (copy != null) copy.add(next);
        }
        return copy == null ? value : copy;
    }

    /** One thing of the plan, renamed as it was last time when it is the very object it was then. */
    private JsonElement thing(final JsonElement thing) {
        JsonElement to = was.get(thing);
        if (to == null) to = walk(thing);
        now.put(thing, to);
        return to;
    }

    @Nullable
    private String renameOf(final String id) {
        if (used.contains(id)) return null;
        final String to = PosteaRenames.rename(table, id);
        return to.equals(id) ? null : to;
    }

    private String renameString(final String text) {
        final String whole = renameOf(text);
        if (whole != null) return whole;
        if (text.startsWith("item:")) {
            final String keyed = renameOf(text.substring(5));
            if (keyed != null) return "item:" + keyed;
        }
        if (!text.contains("item:")) return text;
        final Matcher m = ENCODED.matcher(text);
        final StringBuilder out = new StringBuilder();
        int last = 0;
        boolean changed = false;
        while (m.find()) {
            final String to = renameOf(FfHandle.decode(m.group(2)));
            if (to == null) continue;
            out.append(text, last, m.start())
                .append(m.group(1))
                .append("item:")
                .append(encode(to));
            last = m.end();
            changed = true;
        }
        if (!changed) return text;
        return out.append(text, last, text.length())
            .toString();
    }

    private JsonElement walk(final JsonElement node) {
        if (node.isJsonPrimitive()) {
            final JsonPrimitive p = node.getAsJsonPrimitive();
            if (!p.isString()) return node;
            final String to = renameString(p.getAsString());
            return to.equals(p.getAsString()) ? node : new JsonPrimitive(to);
        }
        if (node.isJsonArray()) {
            final JsonArray array = node.getAsJsonArray();
            JsonArray copy = null;
            for (int i = 0; i < array.size(); i++) {
                final JsonElement next = walk(array.get(i));
                if (next != array.get(i) && copy == null) {
                    copy = new JsonArray();
                    for (int j = 0; j < i; j++) copy.add(array.get(j));
                }
                if (copy != null) copy.add(next);
            }
            return copy == null ? node : copy;
        }
        if (!node.isJsonObject()) return node;
        final JsonObject record = node.getAsJsonObject();
        final JsonObject out = new JsonObject();
        boolean changed = false;
        for (final Map.Entry<String, JsonElement> e : record.entrySet()) {
            if ("gtnhplanner".equals(e.getKey())) {
                out.add(e.getKey(), e.getValue());
                continue;
            }
            final String key = renameString(e.getKey());
            final JsonElement next = walk(e.getValue());
            changed |= next != e.getValue() || !key.equals(e.getKey());
            out.add(key, next);
        }
        if (!changed) return node;
        // A resource that moved wears the new item's face.
        for (final String idKey : new String[] { "id", "resourceId" }) {
            final String before = str(record, idKey), after = str(out, idKey);
            if (before == null || after == null || before.equals(after)) continue;
            final JsonObject face = faces.get(after);
            for (final String faceKey : FACE_KEYS) {
                if (!out.has(faceKey)) continue;
                final JsonElement value = face == null ? null : face.get(faceKey);
                if (value == null || value.isJsonNull()) out.remove(faceKey);
                else out.add(faceKey, value);
            }
            final String name = face == null ? null : str(face, "displayName");
            if (name != null && out.has("label") && str(out, "label") != null) out.addProperty("label", name);
        }
        return out;
    }

    // endregion

    /** {@code encodeURIComponent}, as the website writes handles. */
    private static String encode(final String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name())
                .replace("+", "%20")
                .replace("%21", "!")
                .replace("%27", "'")
                .replace("%28", "(")
                .replace("%29", ")")
                .replace("%7E", "~");
        } catch (final UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<JsonObject> objects(final JsonObject o, final String key) {
        final java.util.ArrayList<JsonObject> out = new java.util.ArrayList<>();
        final JsonElement e = o.get(key);
        if (e == null || !e.isJsonArray()) return out;
        for (final JsonElement x : e.getAsJsonArray()) if (x.isJsonObject()) out.add(x.getAsJsonObject());
        return out;
    }

    @Nullable
    private static String str(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive()
            && e.getAsJsonPrimitive()
                .isString() ? e.getAsString() : null;
    }
}
