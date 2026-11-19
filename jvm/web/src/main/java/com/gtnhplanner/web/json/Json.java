package com.gtnhplanner.web.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.gtnhplanner.core.base.NumberText;

/**
 * The JSON the web exports speak: a reader into plain Java values (objects are ordered maps, arrays lists, numbers
 * doubles as JavaScript reads them, strings, booleans, null) and a writer that spells numbers as JavaScript does.
 * No reflection, so it compiles under TeaVM. Not an export: the exports classes call it.
 *
 * Non-finite numbers are written as the strings "Infinity", "-Infinity" and "NaN", as the conformance files spell
 * them (JSON has no such numbers, and null would lose which one it was).
 */
public final class Json {

    private final String text;
    private int at;

    private Json(final String text) {
        this.text = text;
    }

    public static Object parse(final String text) {
        final Json reader = new Json(text);
        final Object value = reader.value();
        reader.space();
        if (reader.at != text.length()) throw reader.fail("trailing text");
        return value;
    }

    // ---- typed reads: an absent or null field reads as the fallback

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(final Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> array(final Object value) {
        return value instanceof List ? (List<Object>) value : new ArrayList<>();
    }

    public static double num(final Map<String, Object> from, final String key, final double fallback) {
        final Object v = from.get(key);
        if (v instanceof Double) return (Double) v;
        if ("Infinity".equals(v)) return Double.POSITIVE_INFINITY;
        if ("-Infinity".equals(v)) return Double.NEGATIVE_INFINITY;
        if ("NaN".equals(v)) return Double.NaN;
        return fallback;
    }

    /** A number, or null when the field is absent or not a number. */
    public static Double boxed(final Map<String, Object> from, final String key) {
        return from.get(key) instanceof Double ? (Double) from.get(key) : null;
    }

    public static boolean bool(final Map<String, Object> from, final String key, final boolean fallback) {
        return from.get(key) instanceof Boolean ? (Boolean) from.get(key) : fallback;
    }

    public static String str(final Map<String, Object> from, final String key) {
        return from.get(key) instanceof String ? (String) from.get(key) : null;
    }

    // ---- writer

    /** Spells a value: maps, lists, Doubles/Integers, strings, booleans, null. Maps keep their order. */
    public static String write(final Object value) {
        final StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    public static void write(final StringBuilder out, final Object value) {
        if (value == null) out.append("null");
        else if (value instanceof String) quote(out, (String) value);
        else if (value instanceof Boolean) out.append(((Boolean) value) ? "true" : "false");
        else if (value instanceof Number) number(out, ((Number) value).doubleValue());
        else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (final Map.Entry<String, Object> e : ((Map<String, Object>) value).entrySet()) {
                if (!first) out.append(',');
                first = false;
                quote(out, e.getKey());
                out.append(':');
                write(out, e.getValue());
            }
            out.append('}');
        } else if (value instanceof List) {
            out.append('[');
            boolean first = true;
            for (final Object e : (List<Object>) value) {
                if (!first) out.append(',');
                first = false;
                write(out, e);
            }
            out.append(']');
        } else throw new IllegalArgumentException("cannot write " + value.getClass().getName());
    }

    private static void number(final StringBuilder out, final double n) {
        if (n != n || n == Double.POSITIVE_INFINITY || n == Double.NEGATIVE_INFINITY) quote(out, NumberText.of(n));
        else out.append(NumberText.of(n));
    }

    private static void quote(final StringBuilder out, final String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        final String hex = Integer.toHexString(c);
                        out.append("\\u");
                        for (int p = hex.length(); p < 4; p++) out.append('0');
                        out.append(hex);
                    } else out.append(c);
            }
        }
        out.append('"');
    }

    // ---- reader

    private IllegalStateException fail(final String why) {
        return new IllegalStateException("Bad JSON at " + at + ": " + why);
    }

    private void space() {
        while (at < text.length()) {
            final char c = text.charAt(at);
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t') return;
            at++;
        }
    }

    private Object value() {
        space();
        if (at >= text.length()) throw fail("unexpected end");
        final char c = text.charAt(at);
        switch (c) {
            case '{':
                return members();
            case '[':
                return elements();
            case '"':
                return string();
            case 't':
                word("true");
                return Boolean.TRUE;
            case 'f':
                word("false");
                return Boolean.FALSE;
            case 'n':
                word("null");
                return null;
            default:
                return number();
        }
    }

    private void word(final String word) {
        if (!text.startsWith(word, at)) throw fail("expected " + word);
        at += word.length();
    }

    private Map<String, Object> members() {
        final Map<String, Object> out = new LinkedHashMap<>();
        at++;
        space();
        if (at < text.length() && text.charAt(at) == '}') {
            at++;
            return out;
        }
        while (true) {
            space();
            if (at >= text.length() || text.charAt(at) != '"') throw fail("expected a key");
            final String key = string();
            space();
            if (at >= text.length() || text.charAt(at) != ':') throw fail("expected :");
            at++;
            out.put(key, value());
            space();
            if (at >= text.length()) throw fail("unexpected end");
            final char c = text.charAt(at++);
            if (c == '}') return out;
            if (c != ',') throw fail("expected , or }");
        }
    }

    private List<Object> elements() {
        final List<Object> out = new ArrayList<>();
        at++;
        space();
        if (at < text.length() && text.charAt(at) == ']') {
            at++;
            return out;
        }
        while (true) {
            out.add(value());
            space();
            if (at >= text.length()) throw fail("unexpected end");
            final char c = text.charAt(at++);
            if (c == ']') return out;
            if (c != ',') throw fail("expected , or ]");
        }
    }

    private String string() {
        final StringBuilder out = new StringBuilder();
        at++;
        while (at < text.length()) {
            final char c = text.charAt(at++);
            if (c == '"') return out.toString();
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (at >= text.length()) break;
            final char e = text.charAt(at++);
            switch (e) {
                case 'n':
                    out.append('\n');
                    break;
                case 't':
                    out.append('\t');
                    break;
                case 'r':
                    out.append('\r');
                    break;
                case 'b':
                    out.append('\b');
                    break;
                case 'f':
                    out.append('\f');
                    break;
                case 'u':
                    if (at + 4 > text.length()) throw fail("short \\u escape");
                    out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    at += 4;
                    break;
                default:
                    out.append(e);
                    break;
            }
        }
        throw fail("unterminated string");
    }

    private Double number() {
        final int start = at;
        while (at < text.length()) {
            final char c = text.charAt(at);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') at++;
            else break;
        }
        if (start == at) throw fail("unexpected character");
        return NumberText.parse(text.substring(start, at));
    }
}
