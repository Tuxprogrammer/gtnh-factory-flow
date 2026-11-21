package com.gtnhplanner;

import java.io.IOException;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.gtnhplanner.core.base.Uuid;

/**
 * Where the mod's ids meet saved data. The mod names everything of a plan by the core's {@link Uuid}; its saves were
 * written by Gson when they were {@code java.util.UUID}s, which Gson spells as their text. Registered on the plan
 * serializer, so every saved id (as a field, in a list or as a map key) reads and writes exactly as it always did.
 * The game's own ids (a player's, a quest's) stay {@code java.util.UUID} where the game hands them over; nothing of the
 * plan meets them.
 */
public final class CoreIds {

    private CoreIds() {}

    /** An id in a save as Gson writes a {@code java.util.UUID}: its text, or null. */
    public static final TypeAdapter<Uuid> GSON = new TypeAdapter<>() {

        @Override
        public void write(final JsonWriter out, final Uuid value) throws IOException {
            out.value(value == null ? null : value.toString());
        }

        @Override
        public Uuid read(final JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return Uuid.fromString(in.nextString());
        }
    };
}
