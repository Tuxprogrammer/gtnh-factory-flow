package com.gtnhplanner.core.codec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.ExactMath;
import com.gtnhplanner.core.base.NumberText;

/**
 * JSON in one spelling, whatever wrote it: keys sorted, numbers by their value ({@code 1}, {@code 1.0} and {@code 1e0}
 * all {@code 1}), the rest as JSON writes it. Two trees with the same values spell the same here, so the spelling can
 * be compared and hashed: a thing's {@code sum} in a shared plan survives the website reading and writing it again.
 *
 * <p>
 * The spelling is the mod's (its cloud map's {@code Canon}, which wrote every sum already in players' cloud plans), so
 * it must not change: whole numbers below 10^15 without a point, other numbers as Java's {@code Double.toString} spells
 * them (JDK 19 and later, {@link NumberText#javaString}), strings escaped as Gson escapes them ({@code \u001f},
 * {@code  }, {@code  }). Vectors: conformance/codec/canon.json.
 */
public final class Canon {

    private Canon() {}

    /** The canonical spelling of a tree: maps, lists, strings, numbers, booleans and null. */
    public static String of(@Nullable final Object tree) {
        final StringBuilder out = new StringBuilder();
        write(tree, out);
        return out.toString();
    }

    /** A short hash of the canonical spelling: 16 hex digits of its SHA-256. */
    public static String sum(@Nullable final Object tree) {
        return Sha256.hex(Sha256.utf8(of(tree)))
            .substring(0, 16);
    }

    private static void write(@Nullable final Object e, final StringBuilder out) {
        if (e == null) out.append("null");
        else if (e instanceof Number) out.append(number(((Number) e).doubleValue()));
        else if (e instanceof Boolean) out.append(((Boolean) e).booleanValue());
        else if (e instanceof String) quote((String) e, out);
        else if (e instanceof Map) {
            final Map<?, ?> o = (Map<?, ?>) e;
            final List<String> keys = new ArrayList<>();
            for (final Object k : o.keySet()) keys.add(String.valueOf(k));
            Collections.sort(keys);
            out.append('{');
            boolean first = true;
            for (final String key : keys) {
                if (!first) out.append(',');
                first = false;
                quote(key, out);
                out.append(':');
                write(o.get(key), out);
            }
            out.append('}');
        } else if (e instanceof Iterable) {
            out.append('[');
            boolean first = true;
            for (final Object x : (Iterable<?>) e) {
                if (!first) out.append(',');
                first = false;
                write(x, out);
            }
            out.append(']');
        } else throw new IllegalArgumentException(
            "Not a JSON value: " + e.getClass()
                .getName());
    }

    /** A number by its value: whole numbers without a point, the rest as Java spells the double. */
    static String number(final double d) {
        if (d == ExactMath.rint(d) && (d < 0 ? -d : d) < 1e15) return Long.toString((long) d);
        return NumberText.javaString(d);
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** A string as Gson writes one (its JsonWriter, not HTML-safe). */
    static void quote(final String s, final StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\f' -> out.append("\\f");
                case ' ' -> out.append("\\u2028");
                case ' ' -> out.append("\\u2029");
                default -> {
                    if (c < 0x20) out.append("\\u00")
                        .append(HEX[c >> 4])
                        .append(HEX[c & 15]);
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }
}
