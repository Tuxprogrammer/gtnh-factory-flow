package com.gtnhplanner.core.view.rail;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.NumberText;

/**
 * The one JSON writer of the view records: objects and arrays written in the order the caller names them, strings
 * escaped as {@code JSON.stringify} escapes them, numbers spelled by {@link NumberText#json}. An optional that is
 * {@code null} is left out, never written as {@code null}: the wire has no nulls, so a reader sees a missing key.
 */
public final class Json {

    private final StringBuilder out = new StringBuilder();
    private boolean[] comma = new boolean[16];
    private int depth;

    /** The text written so far. */
    @Override
    public String toString() {
        return out.toString();
    }

    private void sep() {
        if (depth > 0 && comma[depth]) out.append(',');
        if (depth > 0) comma[depth] = true;
    }

    private void open(final char c) {
        sep();
        out.append(c);
        depth++;
        if (depth >= comma.length) comma = java.util.Arrays.copyOf(comma, comma.length * 2);
        comma[depth] = false;
    }

    private void close(final char c) {
        depth--;
        out.append(c);
    }

    private Json key(final String name) {
        sep();
        string(out, name);
        out.append(':');
        // The value that follows is this key's own: it must not write a separator.
        comma[depth] = false;
        return this;
    }

    private void after() {
        comma[depth] = true;
    }

    public Json begin() {
        open('{');
        return this;
    }

    public Json end() {
        close('}');
        return this;
    }

    public Json str(final String name, final String value) {
        key(name);
        string(out, value);
        after();
        return this;
    }

    public Json opt(final String name, @Nullable final String value) {
        return value == null ? this : str(name, value);
    }

    public Json num(final String name, final double value) {
        key(name);
        out.append(NumberText.json(value));
        after();
        return this;
    }

    public Json opt(final String name, @Nullable final Double value) {
        return value == null ? this : num(name, value);
    }

    public Json bool(final String name, final boolean value) {
        key(name);
        out.append(value ? "true" : "false");
        after();
        return this;
    }

    public Json opt(final String name, @Nullable final Boolean value) {
        return value == null ? this : bool(name, value);
    }

    /** A key whose value the callback writes (an object, an array or a scalar through {@link #value}). */
    public Json field(final String name, final Consumer<Json> value) {
        key(name);
        value.accept(this);
        after();
        return this;
    }

    public <T> Json list(final String name, final List<T> items, final java.util.function.BiConsumer<Json, T> each) {
        key(name);
        array(items, each);
        after();
        return this;
    }

    public <T> Json array(final List<T> items, final java.util.function.BiConsumer<Json, T> each) {
        open('[');
        for (final T item : items) each.accept(this, item);
        close(']');
        return this;
    }

    /** An element of the array being written: a string. */
    public Json item(final String value) {
        sep();
        string(out, value);
        return this;
    }

    public Json item(final double value) {
        sep();
        out.append(NumberText.json(value));
        return this;
    }

    public Json strings(final String name, final List<String> items) {
        return list(name, items, Json::item);
    }

    public Json numbers(final String name, final List<Double> items) {
        return list(name, items, (j, d) -> j.item(d.doubleValue()));
    }

    /** An object of numbers, keys sorted so the text never depends on a map's order. */
    public Json numberMap(final String name, final Map<String, Double> items) {
        return field(name, j -> {
            j.begin();
            for (final Map.Entry<String, Double> e : new TreeMap<>(items).entrySet()) j.num(e.getKey(), e.getValue());
            j.end();
        });
    }

    /** {@code JSON.stringify} of a string. */
    public static void string(final StringBuilder sb, final String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u00");
                        sb.append("0123456789abcdef".charAt(c >> 4));
                        sb.append("0123456789abcdef".charAt(c & 15));
                    } else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
