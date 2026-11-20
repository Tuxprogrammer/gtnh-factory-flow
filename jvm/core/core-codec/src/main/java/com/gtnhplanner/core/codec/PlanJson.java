package com.gtnhplanner.core.codec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.JsonTree;
import com.gtnhplanner.core.base.NumberText;

/**
 * A plan file (the website's {@code FactoryProject}, conformance/schema/plan.schema.json) as text and back. The tree is
 * the plan: objects are ordered maps holding every key, the ones a reader has no use for included, so a plan read and
 * written again keeps what a newer version added. Numbers are read as JavaScript reads them and written as
 * {@code JSON.stringify} writes them: a plan the website wrote comes back byte for byte, and one the mod's Gson wrote
 * ({@code 1.0}) comes back as the website would write it ({@code 1}).
 */
public final class PlanJson {

    private PlanJson() {}

    /**
     * The plan a text holds.
     *
     * @throws IllegalArgumentException when the text is not JSON or not an object
     */
    public static Map<String, Object> parse(final String text) {
        final Object tree;
        try {
            tree = JsonTree.parse(text);
        } catch (final RuntimeException e) {
            throw new IllegalArgumentException("Not a plan: " + e.getMessage(), e);
        }
        if (!(tree instanceof Map)) throw new IllegalArgumentException("Not a plan: the JSON is not an object");
        return JsonTree.object(tree);
    }

    /**
     * The compact text of a plan (or any JSON tree), as {@code JSON.stringify} writes what {@code JSON.parse} read: keys
     * in order, except that keys which are array indexes ({@code "0"}, {@code "12"}) come first in numeric order, as a
     * JavaScript object holds them (a card's {@code recipeInputOverrides} are keyed so); numbers by
     * {@link NumberText#json}; a lone surrogate escaped.
     */
    public static String write(@Nullable final Object tree) {
        final StringBuilder out = new StringBuilder();
        write(out, tree);
        return out.toString();
    }

    private static void write(final StringBuilder out, @Nullable final Object value) {
        if (value == null) out.append("null");
        else if (value instanceof String) quote(out, (String) value);
        else if (value instanceof Boolean) out.append(((Boolean) value).booleanValue() ? "true" : "false");
        else if (value instanceof Number) out.append(NumberText.json(((Number) value).doubleValue()));
        else if (value instanceof Map) {
            final Map<?, ?> map = (Map<?, ?>) value;
            final List<String> keys = new ArrayList<>();
            for (final Object k : map.keySet()) keys.add(String.valueOf(k));
            final List<String> indexes = new ArrayList<>();
            for (final String k : keys) if (index(k) >= 0) indexes.add(k);
            if (!indexes.isEmpty()) {
                keys.removeAll(indexes);
                indexes.sort((a, b) -> Long.compare(index(a), index(b)));
                keys.addAll(0, indexes);
            }
            out.append('{');
            boolean first = true;
            for (final String k : keys) {
                if (!first) out.append(',');
                first = false;
                quote(out, k);
                out.append(':');
                write(out, map.get(k));
            }
            out.append('}');
        } else if (value instanceof Iterable) {
            out.append('[');
            boolean first = true;
            for (final Object item : (Iterable<?>) value) {
                if (!first) out.append(',');
                first = false;
                write(out, item);
            }
            out.append(']');
        } else throw new IllegalArgumentException(
            "Not a JSON value: " + value.getClass()
                .getName());
    }

    /** The key as an array index (0 to 2^32 - 2, written without a leading zero), or -1. */
    private static long index(final String k) {
        if (k.isEmpty() || k.length() > 10 || k.length() > 1 && k.charAt(0) == '0') return -1;
        long v = 0;
        for (int i = 0; i < k.length(); i++) {
            final char c = k.charAt(i);
            if (c < '0' || c > '9') return -1;
            v = v * 10 + (c - '0');
        }
        return v <= 4294967294L ? v : -1;
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** A string as {@code JSON.stringify} writes it. */
    private static void quote(final StringBuilder out, final String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    final boolean lone = Character.isHighSurrogate(c)
                        ? i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))
                        : Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(s.charAt(i - 1)));
                    if (c < 0x20 || lone) out.append("\\u")
                        .append(HEX[c >> 12 & 15])
                        .append(HEX[c >> 8 & 15])
                        .append(HEX[c >> 4 & 15])
                        .append(HEX[c & 15]);
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }
}
