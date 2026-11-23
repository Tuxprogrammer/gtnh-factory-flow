package com.gtnhplanner.cloud.map;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhplanner.cloud.sync.LiveJson;

/**
 * A plan read takes a later copy's objects for the things that say the same. The player's own batches come back from
 * the room in new objects (the echo lands on the confirmed plan, and batches still waiting go on top of others' again),
 * and a read that follows knows a thing as its own only by the very object ({@link CloudPlans#read(JsonObject,
 * CloudGame, CloudPlans.Read)}): without this, a card the player just moved would be read again as if someone else had
 * changed it.
 */
final class PlanShare {

    /** The things a read follows by object, as {@link PlanRead} does. */
    private static final List<String> THINGS = List
        .of("nodes", "storages", "edges", "annotations", "pockets", "recipes");

    private PlanShare() {}

    /**
     * {@code plan} with each thing that {@code later} holds under the same id with the same JSON taken from
     * {@code later}; {@code plan} itself when none is. Neither is changed.
     */
    static JsonObject same(final JsonObject plan, final JsonObject later) {
        JsonObject out = null;
        for (final String c : THINGS) {
            final JsonElement mine = plan.get(c), theirs = later.get(c);
            if (mine == null || !mine.isJsonArray() || theirs == null || !theirs.isJsonArray()) continue;
            final Map<String, JsonElement> byId = new HashMap<>();
            for (final JsonElement e : theirs.getAsJsonArray()) {
                final String id = LiveJson.idOf(e);
                if (id != null) byId.putIfAbsent(id, e);
            }
            JsonArray next = null;
            int i = 0;
            for (final JsonElement e : mine.getAsJsonArray()) {
                final JsonElement t = byId.get(LiveJson.idOf(e));
                final boolean take = t != null && t != e && LiveJson.equal(t, e);
                if (take && next == null) {
                    next = new JsonArray();
                    int j = 0;
                    for (final JsonElement before : mine.getAsJsonArray()) {
                        if (j++ == i) break;
                        next.add(before);
                    }
                }
                if (next != null) next.add(take ? t : e);
                i++;
            }
            if (next == null) continue;
            if (out == null) out = LiveJson.shallow(plan);
            out.add(c, next);
        }
        return out == null ? plan : out;
    }
}
