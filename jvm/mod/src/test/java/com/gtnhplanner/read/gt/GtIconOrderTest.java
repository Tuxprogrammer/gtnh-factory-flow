package com.gtnhplanner.read.gt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The export reads a GregTech map's recipes by first output, EU/t and time, ties in the map's order, so the first data
 * orb the icon renderer sees is the one the served dataset's export saw. Stand-in recipes: first output, EU/t, time,
 * and the NBT text of the data orb the recipe holds.
 */
class GtIconOrderTest {

    private static final class Fake {

        final String firstOutput;
        final long eut;
        final int duration;
        final String orbNbt;

        Fake(final String firstOutput, final long eut, final int duration, final String orbNbt) {
            this.firstOutput = firstOutput;
            this.eut = eut;
            this.duration = duration;
            this.orbNbt = orbNbt;
        }
    }

    private static final GtIconOrder.Keys<Fake> KEYS = new GtIconOrder.Keys<Fake>() {

        @Override
        public String firstOutput(final Fake recipe) {
            return recipe.firstOutput;
        }

        @Override
        public long eut(final Fake recipe) {
            return recipe.eut;
        }

        @Override
        public int duration(final Fake recipe) {
            return recipe.duration;
        }
    };

    /** The Bio Lab registers its DNA extraction (orb made with the name set first) before its plasmid recipes. */
    @Test
    void bioLabPlasmidOrbIsReadBeforeTheExtractedOne() {
        final Fake extraction = new Fake(
            "gregtech:gt.metaitem.01@32707x1",
            480,
            500,
            "{mDataName:\"beta-Lactamase\",mDataTitle:\"DNA Sample\"}");
        final Fake plasmid = new Fake(
            "bartworks:BioLabParts@2x1",
            1920,
            8,
            "{mDataTitle:\"DNA Sample\",mDataName:\"beta-Lactamase\"}");
        final List<Fake> read = GtIconOrder.sorted(Arrays.asList(extraction, plasmid), KEYS);
        assertEquals(Arrays.asList(plasmid, extraction), read);
        assertEquals("{mDataTitle:\"DNA Sample\",mDataName:\"beta-Lactamase\"}", read.get(0).orbNbt);
    }

    /** The renderer names a picture by sha1 of registry@damage#NBT text; this is the served file for that orb. */
    @Test
    void servedDataOrbFileKey() throws Exception {
        final String key = "gregtech:gt.metaitem.01@32707#{mDataName:\"beta-Lactamase\",mDataTitle:\"DNA Sample\"}";
        final byte[] digest = MessageDigest.getInstance("SHA-1")
            .digest(key.getBytes(StandardCharsets.UTF_8));
        final StringBuilder hex = new StringBuilder();
        for (final byte b : digest) hex.append(String.format("%02x", b & 255));
        assertEquals("data_orb-0777ff2a8618.png", "data_orb-" + hex.substring(0, 12) + ".png");
    }

    @Test
    void tiesKeepTheMapOrder() {
        final Fake a = new Fake("tconstruct:boltPart@2x1", 30, 20, "{DualMat:{Material2:1595}}");
        final Fake b = new Fake("tconstruct:boltPart@2x1", 30, 20, "{DualMat:{Material2:1505}}");
        final Fake c = new Fake("tconstruct:boltPart@2x1", 16, 20, "{DualMat:{Material2:1518}}");
        assertEquals(Arrays.asList(c, a, b), GtIconOrder.sorted(Arrays.asList(a, b, c), KEYS));
    }
}
