package com.gtnhplanner.client.dave;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * The Ask Dave conversation: the player's questions and Dave's answers, kept for the game session (never saved, never
 * in the plan). Pure: events come in as the bridge's JSON (its protocol, section 2.7) and the clock is passed in. One
 * question runs at a time. Events for a request this chat did not send, one it stopped, or one already finished are
 * dropped; replies before the final one add their lines; only the final reply's data is acted on, the latest data seen
 * winning when the final carries none.
 */
public final class DaveChat {

    /** Where a question is. */
    public enum State {

        /** Sent, nothing back yet. */
        ASKING,
        /** Dave is working on it (a thinking event or a reply that is not the last). */
        THINKING,
        /** The final reply came. */
        ANSWERED,
        /** The bridge or Dave refused or failed it. */
        ERROR,
        /** The player stopped it. */
        STOPPED;

        public boolean running() {
            return this == ASKING || this == THINKING;
        }
    }

    /** A recipe Dave suggests: added as a new card by the player. */
    public record Recipe(String recipeId, String label, @Nullable String machine, @Nullable String tier) {}

    /** One question and what came back. */
    public static final class Turn {

        public final String question;
        @Nullable
        public final String requestId;
        /** A line about the question itself (the plan was not sent with it), a lang key; null for none. */
        @Nullable
        public final String noteKey;
        private State state;
        private final List<String> lines = new ArrayList<>();
        @Nullable
        private String error;
        @Nullable
        private JsonObject data;
        private long lastEvent;

        private Turn(final String question, @Nullable final String requestId, @Nullable final String noteKey,
            final State state, final long now) {
            this.question = question;
            this.requestId = requestId;
            this.noteKey = noteKey;
            this.state = state;
            this.lastEvent = now;
        }

        public State state() {
            return state;
        }

        /** Dave's answer, line by line. */
        public List<String> lines() {
            return Collections.unmodifiableList(lines);
        }

        /** What went wrong, as the bridge or Dave said it; null when nothing did. */
        @Nullable
        public String error() {
            return error;
        }

        /** The proposal Add to plan applies; null when the final reply carried none. */
        @Nullable
        public String proposalId() {
            return state == State.ANSWERED ? str(data, "proposalId") : null;
        }

        /** Dave's short note under the answer; null for none. */
        @Nullable
        public String note() {
            return state == State.ANSWERED ? str(data, "note") : null;
        }

        /** The recipes the final reply lists. */
        public List<Recipe> recipes() {
            final List<Recipe> out = new ArrayList<>();
            if (state != State.ANSWERED || data == null || !(data.get("recipes") instanceof final JsonArray a))
                return out;
            for (final JsonElement e : a) {
                if (!(e instanceof final JsonObject r)) continue;
                final String id = str(r, "recipeId");
                if (id == null || id.isEmpty()) continue;
                final String label = str(r, "label");
                out.add(new Recipe(id, label == null || label.isEmpty() ? id : label, str(r, "machine"), str(r, "tier")));
            }
            return out;
        }
    }

    /** How long a question may go without any event before the panel says it is still waiting. */
    public static final long QUIET_MS = 60_000;

    private final List<Turn> turns = new ArrayList<>();

    public List<Turn> turns() {
        return Collections.unmodifiableList(turns);
    }

    /** The question still running, or null. */
    @Nullable
    public Turn running() {
        final Turn last = turns.isEmpty() ? null : turns.get(turns.size() - 1);
        return last != null && last.state.running() ? last : null;
    }

    /** Whether a question of {@code text} may be sent: something to ask, within {@code max} characters. */
    public static boolean fits(final String text, final int max) {
        final String t = text.trim();
        return !t.isEmpty() && t.length() <= max;
    }

    /** A question the bridge took under {@code requestId}. */
    public Turn asked(final String question, final String requestId, @Nullable final String noteKey, final long now) {
        final Turn t = new Turn(question, requestId, noteKey, State.ASKING, now);
        turns.add(t);
        return t;
    }

    /** A question the bridge would not take, with what it said. */
    public Turn refused(final String question, final String message, final long now) {
        final Turn t = new Turn(question, null, null, State.ERROR, now);
        t.error = message;
        turns.add(t);
        return t;
    }

    /** Stops the running question; returns its request id to cancel, or null when none runs. */
    @Nullable
    public String stop() {
        final Turn t = running();
        if (t == null) return null;
        t.state = State.STOPPED;
        return t.requestId;
    }

    /** The bridge went away while a question ran: it ends with {@code message}. */
    public void lost(final String message) {
        final Turn t = running();
        if (t == null) return;
        t.state = State.ERROR;
        t.error = message;
    }

    /** Whether the running question has had no event for {@link #QUIET_MS}. */
    public boolean quiet(final long now) {
        final Turn t = running();
        return t != null && now - t.lastEvent >= QUIET_MS;
    }

    /** One event from the bridge; returns whether it changed anything. */
    public boolean onEvent(final String json, final long now) {
        final JsonObject e;
        try {
            final JsonElement parsed = new JsonParser().parse(json);
            if (!(parsed instanceof final JsonObject o)) return false;
            e = o;
        } catch (final RuntimeException bad) {
            return false;
        }
        final String surface = str(e, "surface");
        if (surface != null && !DaveLink.SURFACE.equals(surface)) return false;
        final String id = str(e, "requestId");
        final Turn t = running();
        if (t == null || id == null || !id.equals(t.requestId)) return false;
        final String type = str(e, "type");
        if ("thinking".equals(type)) {
            t.state = State.THINKING;
            t.lastEvent = now;
            return true;
        }
        if ("reply".equals(type)) {
            t.lastEvent = now;
            final String text = str(e, "text");
            if (text != null) for (final String line : text.split("\n", -1)) if (!line.isBlank()) t.lines.add(line);
            if (e.get("data") instanceof final JsonObject data) t.data = data;
            t.state = e.get("final") instanceof final JsonPrimitive f && f.isBoolean() && f.getAsBoolean()
                ? State.ANSWERED
                : State.THINKING;
            return true;
        }
        if ("error".equals(type)) {
            t.lastEvent = now;
            t.state = State.ERROR;
            final String message = str(e, "message");
            t.error = message == null ? "" : message;
            return true;
        }
        return false;
    }

    @Nullable
    private static String str(@Nullable final JsonObject o, final String field) {
        return o != null && o.get(field) instanceof final JsonPrimitive v && v.isString() ? v.getAsString() : null;
    }
}
