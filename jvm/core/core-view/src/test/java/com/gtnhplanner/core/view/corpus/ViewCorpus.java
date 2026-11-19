package com.gtnhplanner.core.view.corpus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.conformance.Conformance;

/**
 * One file of the view golden corpus ({@code conformance/view/<record>/<set>.json}, see {@code conformance/view/README.md}):
 * an envelope {@code {record, set, measure, cases | vectors}}. Opening one checks the envelope against the path it was
 * read from and that it names the text measure its numbers were computed with, so a golden never has an unstated
 * measure.
 */
public final class ViewCorpus {

    /** The measure of goldens that put no text through a {@code TextMeasure}. */
    public static final String NONE = "none";

    /** The fixed test measure: six pixels a character, one and a half times that for {@code ROW_FIGURE}. */
    public static final String SIX_PX = "six-px";

    /** A named case: {@code input} and the {@code view} the website's own code made of it. */
    public record Case(String name, JsonElement input, JsonElement view) {}

    public final String record;
    public final String set;
    public final String measure;
    private final JsonObject envelope;

    private ViewCorpus(final String record, final String set, final JsonObject envelope) {
        this.record = record;
        this.set = set;
        this.measure = envelope.get("measure")
            .getAsString();
        this.envelope = envelope;
    }

    /** Opens {@code conformance/view/<record>/<set>.json}. Fails when the envelope is not what the layout says. */
    public static ViewCorpus open(final String record, final String set) throws IOException {
        final String path = "view/" + record + "/" + set + ".json";
        final JsonElement root;
        try (InputStream in = Conformance.open(path)) {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            in.transferTo(bytes);
            root = new JsonParser().parse(bytes.toString(StandardCharsets.UTF_8));
        }
        final JsonObject o = root.getAsJsonObject();
        for (final String key : new String[] { "record", "set", "measure" }) {
            if (!o.has(key)) throw new IllegalStateException(path + " has no \"" + key + "\"");
        }
        if (!record.equals(
            o.get("record")
                .getAsString())
            || !set.equals(
                o.get("set")
                    .getAsString())) {
            throw new IllegalStateException(path + " names itself " + o.get("record") + "/" + o.get("set"));
        }
        final String measure = o.get("measure")
            .getAsString();
        if (!NONE.equals(measure) && !SIX_PX.equals(measure)) {
            throw new IllegalStateException(path + " names an unknown measure \"" + measure + "\"");
        }
        if (o.has("cases") == o.has("vectors")) {
            throw new IllegalStateException(path + " holds exactly one of \"cases\" and \"vectors\"");
        }
        return new ViewCorpus(record, set, o);
    }

    /** The named cases: each has {@code name}, {@code input} and {@code view}. */
    public List<Case> cases() {
        final JsonArray array = envelope.getAsJsonArray("cases");
        if (array == null) throw new IllegalStateException(record + "/" + set + " holds vectors, not cases");
        final List<Case> out = new ArrayList<>();
        for (final JsonElement e : array) {
            final JsonObject c = e.getAsJsonObject();
            out.add(
                new Case(
                    c.get("name")
                        .getAsString(),
                    c.get("input"),
                    c.get("view")));
        }
        return out;
    }

    /** The vector groups of an arithmetic file: group name to its list of vectors. */
    public JsonObject vectors() {
        final JsonObject v = envelope.getAsJsonObject("vectors");
        if (v == null) throw new IllegalStateException(record + "/" + set + " holds cases, not vectors");
        return v;
    }

    /** A number of a vector, or null when the key is absent. */
    @Nullable
    public static Double num(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsDouble();
    }

    /** A string of a vector, or null when the key is absent. */
    @Nullable
    public static String str(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    /** A list of numbers of a vector. */
    public static double[] doubles(final JsonElement e) {
        final JsonArray a = e.getAsJsonArray();
        final double[] out = new double[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i)
            .getAsDouble();
        return out;
    }
}
