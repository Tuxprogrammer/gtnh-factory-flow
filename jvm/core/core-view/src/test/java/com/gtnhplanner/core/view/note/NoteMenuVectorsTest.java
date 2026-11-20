package com.gtnhplanner.core.view.note;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhplanner.core.view.corpus.ViewCorpus;

/** The note's colour menu against the website's: the vectors of {@code conformance/view/note/menu.json}. */
class NoteMenuVectorsTest {

    @Test
    void menuListsTheWebsitesColoursInItsOrder() throws IOException {
        final JsonArray menu = ViewCorpus.open("note", "menu").vectors().getAsJsonArray("menu");
        assertEquals(NoteMenu.ENTRIES.size(), menu.size(), "entries");
        for (int i = 0; i < menu.size(); i++) {
            final JsonObject j = menu.get(i).getAsJsonObject();
            assertEquals(i, j.get("index").getAsInt());
            assertEquals(NoteMenu.ENTRIES.get(i)[0], j.get("tag").getAsString(), "tag " + i);
            assertEquals(NoteMenu.ENTRIES.get(i)[1], j.get("out").getAsString(), "name " + i);
        }
    }

    @Test
    void everyMenuColourIsAKnownTag() {
        final int yellow = NoteLookBuilder.swatch("yellow");
        for (final String[] e : NoteMenu.ENTRIES) {
            if (!e[0].equals("yellow")) assertNotEquals(yellow, NoteLookBuilder.swatch(e[0]), e[0] + " reads as the default");
        }
    }
}
