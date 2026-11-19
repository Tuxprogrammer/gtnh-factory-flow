package com.gtnhplanner.ui.theme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import com.gtnhplanner.core.view.Tokens;

/**
 * A colour mix has the same bytes on both hosts: {@link Hyb#mix} gives every entry of the shared table
 * (core-view/tokens/mixes.expected.json) that the website's generator and the core's are held to as well.
 */
class HybMixTest {

    private static final File DIR = new File("../core/core-view/tokens");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(final String name) throws Exception {
        try (Reader in = new FileReader(new File(DIR, name))) {
            return (Map<String, Object>) new Yaml().load(in);
        }
    }

    private static int colour(final String hex) {
        return 0xFF000000 | Integer.parseInt(hex.substring(1), 16);
    }

    @Test
    @SuppressWarnings("unchecked")
    void hybMixGivesEveryEntryOfTheSharedTable() throws Exception {
        final Map<String, Object> tokens = read("tokens.json");
        final Map<String, String> expected = (Map<String, String>) (Map<?, ?>) read("mixes.expected.json");
        final Map<String, Object> kindMixes = (Map<String, Object>) tokens.get("kindMixes");
        final Map<String, String> kinds = (Map<String, String>) kindMixes.get("kinds");
        final Map<String, Map<String, Object>> mixes = (Map<String, Map<String, Object>>) kindMixes.get("mixes");
        final Map<String, Map<String, String>> colors = (Map<String, Map<String, String>>) tokens.get("colors");
        int checked = 0;
        for (final Map.Entry<String, String> kind : kinds.entrySet()) {
            final int tint = colour(colors.get("palette").get(kind.getValue()));
            for (final Map.Entry<String, Map<String, Object>> mix : mixes.entrySet()) {
                final int over = colour(colors.get("palette").get((String) mix.getValue().get("over")));
                final float share = ((Number) mix.getValue().get("share")).floatValue();
                final String key = kind.getKey() + "." + mix.getKey();
                final String got = String.format("#%06X", Hyb.mix(tint, over, share) & 0xFFFFFF);
                assertEquals(expected.get(key), got, key);
                checked++;
            }
        }
        assertEquals(expected.size(), checked);
    }

    @Test
    void theCardsColoursAreTheSharedTokens() {
        assertEquals(Tokens.Palette.SURFACE, com.gtnhplanner.ui.card.CardPaint.SURFACE);
        assertEquals(Tokens.Palette.SOURCE, Hyb.SOURCE_INK);
        assertEquals(Tokens.KindMixes.SOURCE_EDGE & 0xFFFFFF, com.gtnhplanner.ui.drawer.DrawerPaint
            .edge(com.gtnhplanner.data.flowchart.Drawer.Kind.SOURCE) & 0xFFFFFF);
        assertEquals(Tokens.Drawer.WIDTH, com.gtnhplanner.ui.drawer.DrawerPaint.W);
        assertEquals(Tokens.Drawer.HEIGHT, com.gtnhplanner.ui.drawer.DrawerPaint.H);
        assertEquals(Tokens.Card.CARD_WIDTH, com.gtnhplanner.ui.card.CardLayout.W);
        assertFalse(Hyb.tier("MV").underline());
        assertEquals(0xFF111111, Hyb.tier("MV").text());
        assertEquals(0xFFFFFFFF, Hyb.tier("MAX").bg());
    }
}
