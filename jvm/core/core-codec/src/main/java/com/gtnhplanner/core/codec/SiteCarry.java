package com.gtnhplanner.core.codec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * What a plan from the other side held that this side's board has no place for, kept so the plan goes back out with
 * it (parity rule 2, the union: a plan saved by either side opens on the other with nothing lost). Pure work on plan
 * trees ({@link PlanJson}), so either host can use it; the mod keeps the result on its board ({@code Graph.site}).
 *
 * <p>
 * {@link #carry} takes the plan as read and what the board made of it, and keeps:
 * <ul>
 * <li>{@code plan}: the plan's fields the board does not write (its {@code view}, notes, checklist, fuel profiles, and
 * whatever a newer version added); not the old Build and Pool fields the import converted.</li>
 * <li>{@code nodes}, {@code storages}, {@code recipes}: cards and drawers the board could not hold (a switched-off card,
 * a recipe the game lacks, an unwired drawer) as they came, with the recipes those cards run.</li>
 * <li>{@code edges}: wires the board could not hold (between two drawers, a drawer wired the wrong way round, a loose
 * cell wire with no tank for it, a wire to a card left out), their ends renamed to what the board calls them.</li>
 * <li>{@code cards}, {@code drawers}: per board card and drawer, the fields of its card or drawer in the plan that the
 * board does not model (a colour tag, a crop farm's count, a byproduct drawer's label, a buffer's drain setting).</li>
 * </ul>
 * {@link #writeBack} puts them into the plan the board writes: a field the board wrote stays the board's, the rest
 * comes back; a wire comes back only while both its ends are still in the plan.
 */
public final class SiteCarry {

    private SiteCarry() {}

    /**
     * What the board made of a plan's things: board ids by plan id (a shared machine's card by its first recipe's), the
     * wires it could not hold, the plan's things it turned into something else (a buffer joined into wires), which
     * neither go back out nor take their wires with them, and the cards it only shows ({@link DisplayCards}): those
     * are in {@code cards} under their own plan id, kept whole as they came, and the board holds their wires.
     */
    public record Placed(Map<String, String> cards, Map<String, String> storages, Set<String> droppedEdges,
        Set<String> converted, Set<String> shown) {

        /** A board that shows no card of the plan's it cannot run. */
        public Placed(final Map<String, String> cards, final Map<String, String> storages,
            final Set<String> droppedEdges, final Set<String> converted) {
            this(cards, storages, droppedEdges, converted, java.util.Collections.emptySet());
        }
    }

    /** The plan's own fields the board writes, and the old ones the import turned into board things. */
    private static final Set<String> PLAN_OWNED = Set.of(
        "schemaVersion",
        "id",
        "name",
        "solveMode",
        "planMode",
        "poolMode",
        "recipes",
        "nodes",
        "storages",
        "annotations",
        "pockets",
        "edges",
        "metadata",
        "primaryView",
        "tasks",
        "taskItems",
        "taskComments",
        "gtnhplanner",
        // Build and Pool: read and converted on import (the board's own drawers and pins say them now).
        "productionGroups",
        "poolResourceRules",
        "poolCellRatios",
        "targetRate",
        "setupRules",
        "assumeBoundaries");

    /**
     * The plan's maps the board writes its own part of and keeps the plan's rest of: setting pins by machine (the
     * board pins its own machines; a website machine it has no card of keeps its pins) and leftover splits by
     * resource (the board's cards are named its way; the plan's splits for the rest come back).
     */
    private static final Set<String> MERGED = Set.of("settingPins", "spareShares");

    /** A card's fields the board writes (or turned into board things on import). */
    private static final Set<String> NODE_OWNED = Set.of(
        "id",
        "recipeId",
        "machineCount",
        "parallel",
        "overclockTier",
        "enabled",
        "customRate",
        "machineConfigTiers",
        "hatchVoltageTier",
        "hatchAmps",
        "powerInputMode",
        "powerEuT",
        "energyHatches",
        "energyHatchType",
        "coilTier",
        "machineHandlerId",
        "hatchSupplies",
        "recipeInputOverrides",
        "extraRecipes",
        "solvePin",
        "solvePinExact",
        "portRates",
        "targetOutput",
        "pocketId",
        "position",
        "productionGroupId",
        "gtnhplanner");

    /** A drawer's fields the board writes (or converted on import). */
    private static final Set<String> STORAGE_OWNED = Set.of(
        "id",
        "kind",
        "resourceId",
        "displayName",
        "drainMode",
        "targetPerSecond",
        "targetMode",
        "pocketId",
        "position",
        "capacity",
        "bufferMode",
        "ratioExportPercent",
        "poolSide",
        "poolTargetMode",
        "productionGroupId",
        "gtnhplanner");

    // region Keeping

    /** What the board keeps of a plan it was made from; empty when it holds everything. */
    public static Map<String, Object> carry(final Map<String, Object> plan, final Placed placed) {
        final Map<String, Object> site = new LinkedHashMap<>();
        final Map<String, Object> own = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> e : plan.entrySet())
            if (!PLAN_OWNED.contains(e.getKey()) && e.getValue() != null) own.put(e.getKey(), e.getValue());
        if (!own.isEmpty()) site.put("plan", own);

        final List<Object> nodes = new ArrayList<>(), recipes = new ArrayList<>();
        final Set<String> carriedIds = new HashSet<>(), recipeIds = new HashSet<>();
        final Map<String, Object> cards = new LinkedHashMap<>();
        for (final Map<String, Object> n : objects(plan.get("nodes"))) {
            final String id = str(n.get("id"));
            if (id == null) continue;
            if (placed.shown()
                .contains(id)) {
                // A card the board only shows: kept whole, with its recipes; its wires are the board's.
                nodes.add(n);
                recipeIds.add(str(n.get("recipeId")));
                for (final Map<String, Object> x : objects(n.get("extraRecipes"))) recipeIds.add(str(x.get("recipeId")));
                continue;
            }
            final String board = placed.cards()
                .get(id);
            if (board == null && !placed.storages()
                .containsKey(id)) {
                nodes.add(n);
                carriedIds.add(id);
                recipeIds.add(str(n.get("recipeId")));
                for (final Map<String, Object> x : objects(n.get("extraRecipes"))) recipeIds.add(str(x.get("recipeId")));
                continue;
            }
            if (board == null) continue;
            final Map<String, Object> extra = extras(n, NODE_OWNED);
            if (!extra.isEmpty()) cards.put(board, extra);
        }
        for (final Map<String, Object> r : objects(plan.get("recipes")))
            if (recipeIds.contains(str(r.get("id")))) recipes.add(r);

        final List<Object> storages = new ArrayList<>();
        final Map<String, Object> drawers = new LinkedHashMap<>();
        for (final Map<String, Object> s : objects(plan.get("storages"))) {
            final String id = str(s.get("id"));
            if (id == null) continue;
            if (placed.converted()
                .contains(id)) continue;
            final String board = placed.storages()
                .get(id);
            if (board == null) {
                storages.add(s);
                carriedIds.add(id);
                continue;
            }
            final Map<String, Object> extra = extras(s, STORAGE_OWNED);
            // What it does once nothing draws from it: the board writes it only for a drain, so a buffer's or a source's
            // setting (the website keeps one for when it becomes a drain) comes back as it came.
            if (s.get("drainMode") != null) extra.put("drainMode", s.get("drainMode"));
            if (!extra.isEmpty()) drawers.put(board, extra);
        }

        final List<Object> edges = new ArrayList<>();
        for (final Map<String, Object> e : objects(plan.get("edges"))) {
            final String id = str(e.get("id")), source = str(e.get("source")), target = str(e.get("target"));
            if (id == null || source == null || target == null) continue;
            final boolean toCarried = carriedIds.contains(source) || carriedIds.contains(target);
            if (!toCarried && !placed.droppedEdges()
                .contains(id)) continue;
            final Map<String, Object> kept = new LinkedHashMap<>(e);
            kept.put("source", boardEnd(placed, source));
            kept.put("target", boardEnd(placed, target));
            edges.add(kept);
        }

        if (!nodes.isEmpty()) site.put("nodes", nodes);
        if (!recipes.isEmpty()) site.put("recipes", recipes);
        if (!storages.isEmpty()) site.put("storages", storages);
        if (!edges.isEmpty()) site.put("edges", edges);
        if (!cards.isEmpty()) site.put("cards", cards);
        if (!drawers.isEmpty()) site.put("drawers", drawers);
        return site;
    }

    private static String boardEnd(final Placed placed, final String planId) {
        final String card = placed.cards()
            .get(planId);
        if (card != null) return card;
        final String drawer = placed.storages()
            .get(planId);
        return drawer != null ? drawer : planId;
    }

    private static Map<String, Object> extras(final Map<String, Object> thing, final Set<String> owned) {
        final Map<String, Object> out = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> e : thing.entrySet())
            if (!owned.contains(e.getKey()) && e.getValue() != null) out.put(e.getKey(), e.getValue());
        return out;
    }

    // endregion

    // region Writing back

    /** Puts what {@link #carry} kept into a plan the board wrote. */
    public static void writeBack(final Map<String, Object> plan, final Map<String, Object> site) {
        if (site.isEmpty()) return;
        for (final Map.Entry<String, Object> e : object(site.get("plan")).entrySet()) {
            final String key = e.getKey();
            if ("view".equals(key) && e.getValue() instanceof Map) {
                // The plan's view as it came, with what the board sets in it (its rate unit) on top.
                final Map<String, Object> view = new LinkedHashMap<>(object(e.getValue()));
                view.putAll(object(plan.get("view")));
                plan.put("view", view);
            } else if (MERGED.contains(key) && e.getValue() instanceof Map && plan.get(key) instanceof Map) {
                // Setting pins and leftover splits: the board's, then the plan's for what the board has none of.
                final Map<String, Object> own = object(plan.get(key));
                for (final Map.Entry<String, Object> x : object(e.getValue()).entrySet()) own.putIfAbsent(x.getKey(), x.getValue());
            } else if (!plan.containsKey(key) || isEmptyList(plan.get(key))) plan.put(key, e.getValue());
        }
        overlay(objects(plan.get("nodes")), object(site.get("cards")), false);
        overlay(objects(plan.get("storages")), object(site.get("drawers")), true);

        append(plan, "recipes", objects(site.get("recipes")));
        append(plan, "nodes", objects(site.get("nodes")));
        append(plan, "storages", objects(site.get("storages")));
        final Set<String> ends = new HashSet<>();
        for (final String c : new String[] { "nodes", "storages" })
            for (final Map<String, Object> t : objects(plan.get(c))) ends.add(str(t.get("id")));
        final List<Map<String, Object>> edges = new ArrayList<>();
        for (final Map<String, Object> e : objects(site.get("edges")))
            if (ends.contains(str(e.get("source"))) && ends.contains(str(e.get("target")))) edges.add(e);
        append(plan, "edges", edges);
    }

    private static void overlay(final List<Map<String, Object>> things, final Map<String, Object> extras,
        final boolean storages) {
        if (extras.isEmpty()) return;
        for (final Map<String, Object> t : things) {
            final Map<String, Object> extra = object(extras.get(str(t.get("id"))));
            for (final Map.Entry<String, Object> e : extra.entrySet()) {
                if (storages && "drainMode".equals(e.getKey())) {
                    // A drawer the board writes no drain setting for (a buffer, a source) gets the plan's back; a
                    // byproduct drawer is a product with no rule to the board: the label comes back while it is one.
                    final Object own = t.get("drainMode");
                    if (own == null || "product".equals(own) && "byproduct".equals(e.getValue())
                        && !t.containsKey("targetMode")) t.put("drainMode", e.getValue());
                    continue;
                }
                if (!t.containsKey(e.getKey())) t.put(e.getKey(), e.getValue());
            }
        }
    }

    /** Adds things to a collection of the plan, leaving out one whose id it already has. */
    private static void append(final Map<String, Object> plan, final String c, final List<Map<String, Object>> add) {
        if (add.isEmpty()) return;
        final List<Object> list = plan.get(c) instanceof List ? list(plan.get(c)) : new ArrayList<>();
        final Set<String> ids = new HashSet<>();
        for (final Object o : list) if (o instanceof Map) ids.add(str(((Map<?, ?>) o).get("id")));
        for (final Map<String, Object> t : add) if (ids.add(str(t.get("id")))) list.add(t);
        plan.put(c, list);
    }

    // endregion

    private static boolean isEmptyList(@Nullable final Object v) {
        return v instanceof List && ((List<?>) v).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(final Object v) {
        return (List<Object>) v;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(@Nullable final Object v) {
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(@Nullable final Object v) {
        final List<Map<String, Object>> out = new ArrayList<>();
        if (v instanceof List) for (final Object o : (List<?>) v) if (o instanceof Map) out.add((Map<String, Object>) o);
        return out;
    }

    @Nullable
    private static String str(@Nullable final Object v) {
        return v instanceof String ? (String) v : null;
    }
}
