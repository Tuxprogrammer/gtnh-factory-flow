package com.gtnhplanner.core.view.corpus;

import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhplanner.core.base.Uuid;

/**
 * Reads a view record from its JSON by its components, so a corpus test does not carry a hand-written reader per
 * record. Tests only: the core itself never uses reflection.
 *
 * <p>
 * The record convention it reads (the one the {@code Json} writer writes): {@code int}, {@code double}, {@code Double},
 * {@code Integer}, {@code boolean}, {@code Boolean}, {@code String}, an enum (by name), {@link Uuid} (as text),
 * {@code List<T>}, a nested record and {@code Map<String, T>} (sorted keys). A key that is absent is the component's
 * empty value: null for an optional, false, 0, an empty list or map otherwise. {@code null} in the JSON is refused: the
 * wire has no nulls.
 */
public final class RecordReader {

    private RecordReader() {}

    public static <R> R read(final Class<R> type, final JsonElement json) {
        @SuppressWarnings("unchecked")
        final R r = (R) value(type, json);
        return r;
    }

    /** A JSON array of records of one type. */
    public static <R> List<R> readList(final Class<R> element, final JsonElement json) {
        final List<R> out = new ArrayList<>();
        for (final JsonElement e : json.getAsJsonArray()) out.add(read(element, e));
        return out;
    }

    private static Object value(final Type type, final JsonElement json) {
        if (json == null) throw new IllegalArgumentException("no value for " + type);
        if (json.isJsonNull()) throw new IllegalArgumentException("null on the wire for " + type);
        if (type instanceof ParameterizedType p) {
            final Class<?> raw = (Class<?>) p.getRawType();
            if (raw == List.class) {
                final List<Object> out = new ArrayList<>();
                for (final JsonElement e : json.getAsJsonArray()) out.add(value(p.getActualTypeArguments()[0], e));
                return out;
            }
            if (raw == Map.class) {
                final Map<String, Object> out = new TreeMap<>();
                for (final Map.Entry<String, JsonElement> e : json.getAsJsonObject()
                    .entrySet()) out.put(e.getKey(), value(p.getActualTypeArguments()[1], e.getValue()));
                return out;
            }
            throw new IllegalArgumentException("unsupported generic type " + type);
        }
        final Class<?> c = (Class<?>) type;
        if (c == int.class || c == Integer.class) return json.getAsInt();
        if (c == double.class || c == Double.class) return json.getAsDouble();
        if (c == boolean.class || c == Boolean.class) return json.getAsBoolean();
        if (c == String.class) return json.getAsString();
        if (c == Uuid.class) return Uuid.fromString(json.getAsString());
        if (c.isEnum()) {
            for (final Object k : c.getEnumConstants()) if (((Enum<?>) k).name()
                .equals(json.getAsString())) return k;
            throw new IllegalArgumentException(c.getSimpleName() + " has no " + json.getAsString());
        }
        if (c.isRecord()) return record(c, json.getAsJsonObject());
        throw new IllegalArgumentException("unsupported type " + type + ": a view record may not hold it");
    }

    private static Object record(final Class<?> c, final JsonObject o) {
        final RecordComponent[] parts = c.getRecordComponents();
        final Class<?>[] types = new Class<?>[parts.length];
        final Object[] args = new Object[parts.length];
        for (int i = 0; i < parts.length; i++) {
            types[i] = parts[i].getType();
            final JsonElement e = o.get(parts[i].getName());
            args[i] = e == null ? empty(parts[i]) : value(parts[i].getGenericType(), e);
        }
        for (final Map.Entry<String, JsonElement> e : o.entrySet()) {
            boolean known = false;
            for (final RecordComponent p : parts) known |= p.getName()
                .equals(e.getKey());
            if (!known) throw new IllegalArgumentException(
                c.getSimpleName() + " has no component \"" + e.getKey() + "\"");
        }
        try {
            final Constructor<?> k = c.getDeclaredConstructor(types);
            k.setAccessible(true);
            return k.newInstance(args);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalArgumentException("cannot build " + c.getName(), e);
        }
    }

    /** An optional component: {@code @Nullable} on it or on its accessor. */
    public static boolean isOptional(final RecordComponent part) {
        return part.isAnnotationPresent(Nullable.class) || part.getAccessor()
            .isAnnotationPresent(Nullable.class);
    }

    private static Object empty(final RecordComponent part) {
        final Type type = part.getGenericType();
        if (isOptional(part)) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == double.class) return 0.0;
        if (type instanceof ParameterizedType p) {
            if (p.getRawType() == List.class) return new ArrayList<>();
            if (p.getRawType() == Map.class) return new TreeMap<>();
        }
        return null;
    }
}
