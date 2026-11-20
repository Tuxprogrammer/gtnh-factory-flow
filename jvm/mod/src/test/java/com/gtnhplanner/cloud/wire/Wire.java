package com.gtnhplanner.cloud.wire;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.gtnhplanner.conformance.Conformance;

/** The wire contract's files ({@code conformance/wire}) and the {@code {name}} fill every runner does. */
public final class Wire {

    private static final Pattern WHOLE = Pattern.compile("^\\{([A-Za-z0-9_.]+)}$");
    private static final Pattern PART = Pattern.compile("\\{([A-Za-z0-9_.]+)}");

    private Wire() {}

    /** A contract file, parsed ({@code shapes.json}, {@code rest.json}, {@code live.json}, {@code plan.json}). */
    public static JsonObject read(final String file) {
        try (Reader r = new InputStreamReader(Conformance.open("wire/" + file), StandardCharsets.UTF_8)) {
            return new JsonParser().parse(r)
                .getAsJsonObject();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Fills the variables in a contract value: a string that is exactly one variable becomes that variable's value (an
     * object stays an object), one inside a longer string is written into it. An unknown variable throws, so a step
     * cannot run with a hole in it.
     */
    public static JsonElement fill(final JsonElement value, final Map<String, JsonElement> vars) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive()
            .isString()) {
            final String s = value.getAsString();
            final Matcher whole = WHOLE.matcher(s);
            if (whole.matches()) {
                final JsonElement v = vars.get(whole.group(1));
                if (v == null) throw new IllegalStateException("no value for {" + whole.group(1) + "}");
                return new com.google.gson.JsonParser().parse(v.toString());
            }
            final Matcher part = PART.matcher(s);
            final StringBuilder out = new StringBuilder();
            while (part.find()) {
                final JsonElement v = vars.get(part.group(1));
                if (v == null) throw new IllegalStateException("no value for {" + part.group(1) + "}");
                part.appendReplacement(
                    out,
                    Matcher.quoteReplacement(
                        v.isJsonPrimitive() ? v.getAsString()
                            : v.toString()));
            }
            part.appendTail(out);
            return new JsonPrimitive(out.toString());
        }
        if (value.isJsonArray()) {
            final JsonArray a = new JsonArray();
            for (final JsonElement e : value.getAsJsonArray()) a.add(fill(e, vars));
            return a;
        }
        if (value.isJsonObject()) {
            final JsonObject o = new JsonObject();
            for (final Map.Entry<String, JsonElement> e : value.getAsJsonObject()
                .entrySet()) o.add(e.getKey(), fill(e.getValue(), vars));
            return o;
        }
        return value;
    }

    /**
     * The variables every run of the contract fills with: the four accounts' ids and session tokens (a session is
     * {@code <id>.<expires>.<signature>}), the plans, the post and dataset ids, and the live doc keys.
     */
    public static Map<String, JsonElement> vars() {
        final Map<String, JsonElement> vars = new java.util.HashMap<>();
        final String[] names = { "ann", "bob", "carol", "sam" };
        for (int i = 0; i < names.length; i++) {
            final String id = String.valueOf(i + 1)
                .repeat(8) + "-0000-4000-8000-000000000000";
            vars.put(names[i] + ".id", new JsonPrimitive(id));
            vars.put(names[i] + ".token", new JsonPrimitive(id + ".4102444800000.sig"));
        }
        vars.put("post.id", new JsonPrimitive("22222222-2222-4222-8222-222222222222"));
        vars.put("dataset.id", new JsonPrimitive("gtnh-2.9.0"));
        final JsonObject plans = read("plan.json");
        vars.put("plan", plans.get("plan"));
        vars.put("emptyPlan", plans.get("emptyPlan"));
        final String ann = vars.get("ann.id")
            .getAsString();
        final String bob = vars.get("bob.id")
            .getAsString();
        vars.put("doc", new JsonPrimitive(ann + ":design-0001"));
        vars.put("bobdoc", new JsonPrimitive(bob + ":design-0002"));
        vars.put("strangerdoc", new JsonPrimitive(bob + ":design-0003"));
        vars.put("missingdoc", new JsonPrimitive(ann + ":design-9999"));
        return vars;
    }
}
