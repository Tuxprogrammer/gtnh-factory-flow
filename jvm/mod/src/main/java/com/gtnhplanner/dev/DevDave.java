package com.gtnhplanner.dev;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhplanner.client.dave.DaveLink;

/**
 * A stand-in for DaveBridge in the dev game, which has no bridge mod: {@code call 'dave?mode=answer'} makes Ask Dave
 * appear and answer every question with a thinking event, a line, then a final reply carrying {@code proposal} (an id
 * the mock library serves) and the recipes {@code recipes} (site ids, comma separated); {@code mode=error} answers with
 * an error; {@code mode=off} takes it away; {@code up=0} keeps it but has the server stop taking asks. Each ask's
 * context and attachment are written as they were sent to {@code dave-last/context.json} and {@code attachment.json}
 * in the game directory, so a check can post the attachment where the server's assistant would. Only reachable through
 * the harness, which runs only in a dev game.
 */
public final class DevDave implements DaveLink {

    @Nullable
    private static volatile DevDave current;

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        final Thread t = new Thread(r, "GTNH Planner dev Dave");
        t.setDaemon(true);
        return t;
    });
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
    private volatile boolean error, up = true;
    private volatile String proposal = "", recipes = "";
    private volatile int asked;
    @Nullable
    private volatile Consumer<String> listener;
    @Nullable
    private volatile String lastText, lastContext;
    private volatile int lastAttachment = -1;

    private DevDave() {}

    /** The stand-in, when the harness set one up; else null and the real bridge is looked for. */
    @Nullable
    public static DaveLink link() {
        return current;
    }

    /** {@code /dave}: sets the stand-in up, and reports it with the last ask it took. */
    static Object route(final Map<String, String> q) {
        if ("off".equals(q.get("mode"))) current = null;
        else if (q.containsKey("mode")) {
            if (current == null) current = new DevDave();
            current.error = "error".equals(q.get("mode"));
        }
        final DevDave d = current;
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("on", d != null);
        if (d == null) return out;
        if (q.containsKey("up")) d.up = !"0".equals(q.get("up"));
        if (q.containsKey("proposal")) d.proposal = q.get("proposal");
        if (q.containsKey("recipes")) d.recipes = q.get("recipes");
        out.put("mode", d.error ? "error" : "answer");
        out.put("up", d.up);
        out.put("asked", d.asked);
        out.put("lastText", d.lastText);
        out.put("lastContext", d.lastContext);
        out.put("lastAttachmentChars", d.lastAttachment);
        return out;
    }

    @Override
    public boolean available() {
        return up;
    }

    @Override
    public String ask(final String text, final String contextJson, final String attachmentJson) {
        if (!up) throw new IllegalStateException("not available");
        final String id = "rdev" + ++asked;
        lastText = text;
        lastContext = contextJson;
        lastAttachment = attachmentJson == null ? -1 : attachmentJson.length();
        save("context.json", contextJson);
        save("attachment.json", attachmentJson);
        later(300, event("thinking", id));
        if (error) {
            final JsonObject e = event("error", id);
            e.addProperty("final", true);
            e.addProperty("message", "Dave is busy right now. Try again in a minute.");
            later(1200, e);
            return id;
        }
        final JsonObject first = event("reply", id);
        first.addProperty("final", false);
        first.addProperty("text", "Looking at your plan.");
        later(1500, first);
        final JsonObject last = event("reply", id);
        last.addProperty("final", true);
        last.addProperty("text", "Two more cards finish the line.\nAdd to plan puts them in.");
        final JsonObject data = new JsonObject();
        if (!proposal.isEmpty()) data.addProperty("proposalId", proposal);
        data.addProperty("note", "Made for this plan only.");
        final JsonArray list = new JsonArray();
        for (final String r : List.of(recipes.split(","))) {
            if (r.isEmpty()) continue;
            final JsonObject o = new JsonObject();
            o.addProperty("recipeId", r);
            o.addProperty("label", "Recipe " + r);
            o.addProperty("tier", "LV");
            list.add(o);
        }
        data.add("recipes", list);
        last.add("data", data);
        later(3000, last);
        return id;
    }

    /** Writes {@code text} (an absent one as an empty file) to {@code dave-last/<name>} in the game directory. */
    private static void save(final String name, @Nullable final String text) {
        final File dir = new File(Minecraft.getMinecraft().mcDataDir, "dave-last");
        try {
            Files.createDirectories(dir.toPath());
            Files.write(new File(dir, name).toPath(), (text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
        } catch (final IOException e) {
            throw new IllegalStateException("dev Dave could not write " + name, e);
        }
    }

    private static JsonObject event(final String type, final String id) {
        final JsonObject e = new JsonObject();
        e.addProperty("type", type);
        e.addProperty("surface", SURFACE);
        e.addProperty("requestId", id);
        return e;
    }

    /** Sends {@code e} on the client thread after {@code ms}, unless its question was stopped. */
    private void later(final long ms, final JsonObject e) {
        final String id = e.get("requestId")
            .getAsString();
        timer.schedule(
            () -> Minecraft.getMinecraft()
                .func_152344_a(() -> {
                    final Consumer<String> l = listener;
                    if (l != null && !cancelled.contains(id)) l.accept(e.toString());
                }),
            ms,
            TimeUnit.MILLISECONDS);
    }

    @Override
    public void cancel(final String requestId) {
        cancelled.add(requestId);
    }

    @Override
    public void listen(final Consumer<String> events) {
        listener = events;
    }

    @Override
    public void unlisten(final Consumer<String> events) {
        if (listener == events) listener = null;
    }
}
