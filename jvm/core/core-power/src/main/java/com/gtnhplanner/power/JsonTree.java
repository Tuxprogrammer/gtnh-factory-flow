package com.gtnhplanner.power;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.NumberText;

/**
 * A small JSON reader for the workbook and resource tables: objects become ordered maps, arrays lists, numbers
 * doubles (as JavaScript reads them), strings, booleans, and null stays null. No reflection, so the same code runs
 * where Gson cannot.
 */
final class JsonTree {

    private final String text;
    private int at;

    private JsonTree(final String text) {
        this.text = text;
    }

    /** The value a JSON document holds. */
    @Nullable
    static Object parse(final String text) {
        final JsonTree reader = new JsonTree(text);
        final Object value = reader.value();
        reader.space();
        if (reader.at != text.length()) throw reader.fail("trailing text");
        return value;
    }

    // ---- typed reads of a parsed tree; an absent or null field reads as 0, null or empty

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(@Nullable final Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(@Nullable final Object value) {
        return value instanceof List ? (List<Object>) value : new ArrayList<>();
    }

    static double num(final Map<String, Object> from, final String key) {
        return from.get(key) instanceof Double ? (Double) from.get(key) : 0;
    }

    @Nullable
    static Double boxed(final Map<String, Object> from, final String key) {
        return from.get(key) instanceof Double ? (Double) from.get(key) : null;
    }

    @Nullable
    static String str(final Map<String, Object> from, final String key) {
        return from.get(key) instanceof String ? (String) from.get(key) : null;
    }

    /** A ladder: numbers with null where the table has none. Null when the field is absent. */
    @Nullable
    static Double[] ladder(final Map<String, Object> from, final String key) {
        if (!(from.get(key) instanceof List)) return null;
        final List<Object> list = array(from.get(key));
        final Double[] out = new Double[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i) instanceof Double ? (Double) list.get(i) : null;
        return out;
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

    @Nullable
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
        if (text.charAt(at) == '}') {
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
        if (text.charAt(at) == ']') {
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
