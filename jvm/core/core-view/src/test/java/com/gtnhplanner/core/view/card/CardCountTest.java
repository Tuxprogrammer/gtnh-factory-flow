package com.gtnhplanner.core.view.card;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhplanner.core.view.corpus.ViewCorpus;
import com.gtnhplanner.data.flowchart.PinRule;

/** The count's arithmetic against the website's (conformance/view/card/count.json): the wheel, typed text, the box. */
class CardCountTest {

    private static JsonObject vectors() throws IOException {
        return ViewCorpus.open("card", "count")
            .vectors();
    }

    @Test
    void stepsTheWheel() throws IOException {
        int n = 0;
        for (final JsonElement e : vectors().getAsJsonArray("wheel")) {
            final JsonObject v = e.getAsJsonObject();
            final Double out = CardViews.wheelCount(
                PinRule.valueOf(v.get("rule")
                    .getAsString()),
                ViewCorpus.num(v, "set"),
                v.get("value")
                    .getAsDouble(),
                v.get("direction")
                    .getAsInt());
            assertEquals(ViewCorpus.num(v, "out"), out, v.toString());
            n++;
        }
        assertTrue(n >= 16);
    }

    @Test
    void readsATypedCount() throws IOException {
        int n = 0;
        for (final JsonElement e : vectors().getAsJsonArray("parse")) {
            final JsonObject v = e.getAsJsonObject();
            assertEquals(
                ViewCorpus.num(v, "out"),
                CardViews.parseCountText(
                    v.get("text")
                        .getAsString()),
                v.toString());
            n++;
        }
        assertTrue(n >= 15);
    }

    @Test
    void stepsAnOpenBox() throws IOException {
        int n = 0;
        for (final JsonElement e : vectors().getAsJsonArray("draft")) {
            final JsonObject v = e.getAsJsonObject();
            assertEquals(
                v.get("out")
                    .getAsString(),
                CardViews.wheelDraft(
                    v.get("draft")
                        .getAsString(),
                    v.get("value")
                        .getAsDouble(),
                    v.get("direction")
                        .getAsInt()),
                v.toString());
            n++;
        }
        assertTrue(n >= 10);
    }
}
