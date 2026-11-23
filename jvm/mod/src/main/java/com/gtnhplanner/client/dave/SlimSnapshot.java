package com.gtnhplanner.client.dave;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.library.PlanExport;

/**
 * The open plan as the assistant's slim snapshot (docs/agent-api.md, "The slim snapshot"; the website's
 * {@code src/lib/agent-api/slim.ts}), the attachment of an Ask Dave question. Built from the plan the export writes
 * ({@link PlanExport#project}), so cards, drawers, wires and boards are named as a post names them, with two rules a
 * proposal made from it needs to come back onto this board: the plan's id is {@link Graph#planId()}, and every card is
 * named by its board id (a display-only card too, not by the website id it shows). Game cards carry the export's own
 * recipe ids; the site keeps a card whose recipe its dataset lacks as it is. Pure: no game state.
 */
public final class SlimSnapshot {

    /** The largest attachment the bridge takes (its protocol, section 2.7): 256 KiB of JSON text. */
    public static final int MAX_BYTES = 256 * 1024;

    private static final Set<String> KINDS = Set.of("item", "fluid", "aspect", "power");
    private static final java.util.regex.Pattern ID = java.util.regex.Pattern
        .compile("^[A-Za-z0-9_][A-Za-z0-9_.:-]{0,99}$");

    private SlimSnapshot() {}

    /** The snapshot of {@code g}, read through {@code world} as the export reads it. */
    public static JsonObject of(final Graph g, final PlanExport.World world) {
        final JsonObject p = PlanExport.project(g, g.getName(), world);
        // A display-only card is written under the website id it shows; the snapshot names it by its board id.
        final Map<String, Node> shown = new HashMap<>();
        for (final Node n : g.getNodes()) if (n.shown != null) shown.put(
            n.shown.planId(),
            n);
        final Map<String, String> rename = new HashMap<>();
        for (final Map.Entry<String, Node> e : shown.entrySet()) rename.put(e.getKey(), e.getValue().id.toString());

        final JsonObject out = new JsonObject();
        out.addProperty("version", 1);
        final JsonObject plan = new JsonObject();
        plan.addProperty("id", g.planId());
        plan.addProperty("name", cut(g.getName(), 200));
        out.add("plan", plan);

        final JsonArray boards = new JsonArray();
        for (final JsonObject b : objects(p, "pockets")) {
            final JsonObject o = new JsonObject();
            o.addProperty("id", str(b, "id"));
            o.addProperty("name", cut(str(b, "name"), 200));
            if (str(b, "parentPocketId") != null) o.addProperty("parent", str(b, "parentPocketId"));
            o.add("position", position(b));
            boards.add(o);
        }
        if (boards.size() > 0) out.add("boards", boards);

        final JsonArray cards = new JsonArray(), targets = new JsonArray();
        final Set<String> things = new java.util.HashSet<>();
        for (final JsonObject n : objects(p, "nodes")) {
            final String planId = str(n, "id");
            final Node display = shown.get(planId);
            if (display == null && (planId == null || !onBoard(g, planId))) continue;
            final String id = display != null ? display.id.toString() : planId;
            final JsonObject c = new JsonObject();
            c.addProperty("id", id);
            c.addProperty("recipeId", str(n, "recipeId"));
            if (str(n, "machineHandlerId") != null) c.addProperty("machine", str(n, "machineHandlerId"));
            c.addProperty("tier", n.has("overclockTier") ? text(n.get("overclockTier")) : "NONE");
            final JsonObject dials = new JsonObject();
            if (n.get("machineConfigTiers") instanceof final JsonObject tiers)
                for (final Map.Entry<String, JsonElement> e : tiers.entrySet()) {
                    final String v = e.getValue()
                        .isJsonPrimitive() ? text(e.getValue()) : null;
                    if (v != null && !v.isEmpty() && v.length() <= 64 && e.getKey()
                        .length() <= 64) dials.addProperty(e.getKey(), v);
                }
            if (str(n, "coilTier") != null) dials.addProperty("heatingCoil", str(n, "coilTier"));
            if (!dials.entrySet()
                .isEmpty()) c.add("dials", dials);
            if (n.get("solvePin") instanceof final JsonPrimitive pin && pin.isNumber()) {
                c.add("count", pin);
                if (n.get("solvePinExact") instanceof final JsonPrimitive exact && exact.isBoolean()
                    && exact.getAsBoolean()) c.addProperty("exact", true);
            }
            final JsonArray sections = new JsonArray();
            for (final JsonObject x : objects(n, "extraRecipes"))
                if (str(x, "recipeId") != null) sections.add(new JsonPrimitive(str(x, "recipeId")));
            if (sections.size() > 0) c.add("sections", sections);
            c.add("position", position(n));
            if (str(n, "pocketId") != null) c.addProperty("board", str(n, "pocketId"));
            cards.add(c);
            things.add(id);
            // Each card's pins in code-unit order, as the website writes them.
            if (n.get("portRates") instanceof final JsonObject rates) {
                final TreeMap<String, Double> sorted = new TreeMap<>();
                for (final Map.Entry<String, JsonElement> e : rates.entrySet())
                    if (e.getValue() instanceof final JsonPrimitive r && r.isNumber()) {
                        final double rate = r.getAsDouble();
                        if (Double.isFinite(rate) && rate > 0) sorted.put(e.getKey(), rate);
                    }
                for (final Map.Entry<String, Double> e : sorted.entrySet()) {
                    final JsonObject t = new JsonObject();
                    t.addProperty("card", id);
                    t.addProperty("port", e.getKey());
                    t.addProperty("rate", e.getValue());
                    targets.add(t);
                }
            }
        }
        out.add("cards", cards);

        final Set<String> ownDrawers = new java.util.HashSet<>();
        for (final com.gtnhplanner.data.flowchart.Drawer d : g.getDrawers()) ownDrawers.add(
            d.getId()
                .toString());
        final JsonArray drawers = new JsonArray();
        for (final JsonObject s : objects(p, "storages")) {
            final String id = str(s, "id"), kind = str(s, "kind"), resource = str(s, "resourceId");
            if (id == null || !ownDrawers.contains(id) || !KINDS.contains(kind) || resource == null || resource.isEmpty()) continue;
            final JsonObject d = new JsonObject();
            d.addProperty("id", id);
            d.add("resource", resource(resource, kind));
            final String mode = str(s, "drainMode");
            if ("byproduct".equals(mode) || "trash".equals(mode)) d.addProperty("mode", mode);
            d.add("position", position(s));
            if (str(s, "pocketId") != null) d.addProperty("board", str(s, "pocketId"));
            drawers.add(d);
            things.add(id);
        }
        out.add("drawers", drawers);

        final JsonArray wires = new JsonArray();
        final Set<String> wireIds = new java.util.HashSet<>();
        for (final JsonObject e : objects(p, "edges")) {
            final String id = str(e, "id"), from = rename.getOrDefault(str(e, "source"), str(e, "source")),
                to = rename.getOrDefault(str(e, "target"), str(e, "target")), kind = str(e, "resourceKind"),
                resource = str(e, "resourceId");
            // Only wires whose ends are both in the snapshot, of a kind the site names, once each.
            if (id == null || !ID.matcher(id)
                .matches() || !things.contains(from) || !things.contains(to) || !KINDS.contains(kind)
                || resource == null || resource.isEmpty() || !wireIds.add(id)) continue;
            final JsonObject w = new JsonObject();
            w.addProperty("id", id);
            w.addProperty("from", from);
            w.addProperty("to", to);
            w.add("resource", resource(resource, kind));
            if (str(e, "sourceHandle") != null) w.addProperty("fromPort", str(e, "sourceHandle"));
            if (str(e, "targetHandle") != null) w.addProperty("toPort", str(e, "targetHandle"));
            wires.add(w);
        }
        out.add("wires", wires);
        out.add("targets", targets);
        return out;
    }

    /** The snapshot as the text sent, or null when it is over {@link #MAX_BYTES}. */
    @Nullable
    public static String text(final JsonObject snapshot) {
        final String s = snapshot.toString();
        return s.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES ? null : s;
    }

    private static boolean onBoard(final Graph g, final String id) {
        for (final Node n : g.getNodes()) if (n.shown == null && n.id.toString()
            .equals(id)) return true;
        return false;
    }

    private static JsonObject resource(final String id, final String kind) {
        final JsonObject r = new JsonObject();
        r.addProperty("id", id);
        r.addProperty("kind", kind);
        return r;
    }

    private static JsonObject position(final JsonObject o) {
        final JsonObject at = o.get("position") instanceof final JsonObject pos ? pos : new JsonObject();
        final JsonObject out = new JsonObject();
        out.add("x", at.get("x") instanceof final JsonPrimitive x && x.isNumber() ? x : new JsonPrimitive(0));
        out.add("y", at.get("y") instanceof final JsonPrimitive y && y.isNumber() ? y : new JsonPrimitive(0));
        return out;
    }

    private static List<JsonObject> objects(final JsonObject o, final String field) {
        final List<JsonObject> out = new ArrayList<>();
        if (o.get(field) instanceof final JsonArray a) for (final JsonElement e : a) if (e instanceof final JsonObject x)
            out.add(x);
        return out;
    }

    @Nullable
    private static String str(final JsonObject o, final String field) {
        return o.get(field) instanceof final JsonPrimitive v && v.isString() ? v.getAsString() : null;
    }

    private static String text(final JsonElement e) {
        return e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    private static String cut(@Nullable final String s, final int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
