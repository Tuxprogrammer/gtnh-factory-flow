package com.gtnhplanner.pattern;

import java.io.File;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.api.PlanAPI;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;

/**
 * The products each card's pattern includes, remembered for the life of the plan: a file of its own beside the plans
 * ({@code pattern-outputs.json}, per world as the plans are), keyed by plan and card. It is not in the plan, so plan
 * saves, plan codes, the website's import and export and account sync are exactly as they were.
 */
final class PatternMemory {

    private static final String FILE = "pattern-outputs.json";
    private static final Gson GSON = new Gson();

    private static PatternOutputs.Choices choices = new PatternOutputs.Choices();
    @Nullable
    private static File loaded;

    private PatternMemory() {}

    /** The card's remembered products in {@code plan}; null when the player never chose them. */
    @Nullable
    static List<String> get(final Graph plan, final Node card) {
        load();
        return choices.get(ids(plan), String.valueOf(card.id));
    }

    /** Remembers the card's products in {@code plan} and saves at once. */
    static void put(final Graph plan, final Node card, final List<String> products) {
        load();
        choices.put(plan.planId(), String.valueOf(card.id), products);
        save();
    }

    /** The plan's ids the file may hold it under: the one it has now, and one it had before it went to the account. */
    private static List<String> ids(final Graph plan) {
        final List<String> ids = new ArrayList<>();
        ids.add(plan.planId());
        for (final String k : choices.all()
            .keySet()) if (!ids.contains(k) && plan.isPlan(k)) ids.add(k);
        return ids;
    }

    @Nullable
    private static File file() {
        try {
            return new File(
                PlanAPI.getSaveFile()
                    .getParentFile(),
                FILE);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /** Reads the file of the world now open, once per world. A file that cannot be read starts empty. */
    private static void load() {
        final File f = file();
        if (f == null || f.equals(loaded)) return;
        loaded = f;
        choices = new PatternOutputs.Choices();
        if (!f.isFile()) return;
        try (Reader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
            final JsonElement root = new JsonParser().parse(r);
            if (!root.isJsonObject()) return;
            for (final Map.Entry<String, JsonElement> plan : root.getAsJsonObject()
                .entrySet()) {
                if (!plan.getValue()
                    .isJsonObject()) continue;
                for (final Map.Entry<String, JsonElement> card : plan.getValue()
                    .getAsJsonObject()
                    .entrySet()) {
                    if (!card.getValue()
                        .isJsonArray()) continue;
                    final List<String> products = new ArrayList<>();
                    for (final JsonElement e : card.getValue()
                        .getAsJsonArray()) if (e.isJsonPrimitive()) products.add(e.getAsString());
                    choices.put(plan.getKey(), card.getKey(), products);
                }
            }
        } catch (final Exception e) {
            GtnhPlanner.LOG.warn("Could not read the pattern products in {}", f, e);
        }
    }

    private static void save() {
        final File f = loaded;
        if (f == null) return;
        final JsonObject root = new JsonObject();
        for (final Map.Entry<String, Map<String, List<String>>> plan : choices.all()
            .entrySet()) {
            final JsonObject cards = new JsonObject();
            for (final Map.Entry<String, List<String>> card : plan.getValue()
                .entrySet()) {
                final JsonArray products = new JsonArray();
                for (final String p : card.getValue()) products.add(new com.google.gson.JsonPrimitive(p));
                cards.add(card.getKey(), products);
            }
            root.add(plan.getKey(), cards);
        }
        try {
            final File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("no folder " + dir);
            final File tmp = new File(dir, FILE + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
            Files.move(
                tmp.toPath(),
                f.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (final Exception e) {
            GtnhPlanner.LOG.warn("Could not save the pattern products to {}", f, e);
        }
    }
}
