package com.gtnhplanner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.gtnhplanner.core.base.Uuid;

/**
 * Where the mod's ids ({@link UUID}, on its plan model) meet the core's ({@link Uuid}, on everything the solver reads
 * and answers): the same 128 bits, so they convert both ways with nothing lost, and print, hash and sort alike. Every
 * crossing goes through here, so the crossings can be found and go away when the plan model takes the core's id.
 * Null crosses as null.
 */
public final class CoreIds {

    private CoreIds() {}

    /** The core's id for a mod id. */
    public static Uuid core(@Nullable final UUID id) {
        return id == null ? null : new Uuid(id.getMostSignificantBits(), id.getLeastSignificantBits());
    }

    /** The mod's id for a core id. */
    public static UUID mod(@Nullable final Uuid id) {
        return id == null ? null : new UUID(id.getMostSignificantBits(), id.getLeastSignificantBits());
    }

    /** The core's ids for mod ids, in order. */
    public static List<Uuid> coreList(final Collection<UUID> ids) {
        final List<Uuid> out = new ArrayList<>(ids.size());
        for (final UUID id : ids) out.add(core(id));
        return out;
    }

    /** The mod's ids for core ids, in order. */
    public static List<UUID> modList(final Collection<Uuid> ids) {
        final List<UUID> out = new ArrayList<>(ids.size());
        for (final Uuid id : ids) out.add(mod(id));
        return out;
    }

    /**
     * A core id in a save as Gson writes a {@link UUID}: its text, or null. Registered on the plan serializer, so a
     * saved excess choice (its ports' node ids) reads and writes as it always did.
     */
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
