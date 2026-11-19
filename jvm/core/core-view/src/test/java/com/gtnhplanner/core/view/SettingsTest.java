package com.gtnhplanner.core.view;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The generated ViewSettings and ViewSounds say what settings.json and sounds.json say, and the schema is consistent
 * with itself: a default is inside its range, a choice's default is one of its ids, every sound a rule names exists.
 */
class SettingsTest {

    private static JsonObject read(final String file) throws IOException {
        try (Reader r = Files.newBufferedReader(Paths.get("settings", file), StandardCharsets.UTF_8)) {
            return new JsonParser().parse(r)
                .getAsJsonObject();
        }
    }

    private static String upper(final String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
            .toUpperCase();
    }

    /** A value that differs by host is {web, mod}; anything else is the same on both. */
    private static JsonElement forMod(final JsonElement v) {
        if (v != null && v.isJsonObject()) {
            final JsonObject o = v.getAsJsonObject();
            if (o.has("web") || o.has("mod")) return o.get("mod");
        }
        return v;
    }

    private static Object constant(final Class<?> type, final String name) throws Exception {
        final Field f = type.getField(name);
        return f.get(null);
    }

    private static String[] strings(final JsonElement list) {
        final JsonArray a = list.getAsJsonArray();
        final String[] out = new String[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i)
            .getAsString();
        return out;
    }

    @Test
    void everyModSettingHasItsKeyAndDefault() throws Exception {
        final JsonObject all = read("settings.json").getAsJsonObject("settings");
        int seen = 0;
        final Set<String> keys = new HashSet<>();
        for (final Map.Entry<String, JsonElement> e : all.entrySet()) {
            final JsonObject spec = e.getValue()
                .getAsJsonObject();
            if (!spec.has("mod")) continue;
            seen++;
            final String n = upper(e.getKey());
            final String key = spec.getAsJsonObject("mod")
                .get("key")
                .getAsString();
            assertEquals(key, constant(ViewSettings.class, n + "_KEY"), e.getKey());
            assertTrue(keys.add(key + "|" + (spec.getAsJsonObject("mod").has("store")
                ? spec.getAsJsonObject("mod").get("store").getAsString()
                : "")), "two settings share the key " + key);
            final JsonElement def = forMod(spec.get("default"));
            switch (spec.get("type")
                .getAsString()) {
                case "bool" -> assertEquals(def.getAsBoolean(), constant(ViewSettings.class, n + "_DEFAULT"), e.getKey());
                case "int" -> assertEquals(def.getAsInt(), constant(ViewSettings.class, n + "_DEFAULT"), e.getKey());
                case "text" -> assertEquals(def.getAsString(), constant(ViewSettings.class, n + "_DEFAULT"), e.getKey());
                case "set" -> assertArrayEquals(
                    strings(def),
                    (String[]) constant(ViewSettings.class, n + "_DEFAULT"),
                    e.getKey());
                case "choice" -> {
                    final String[] ids = strings(spec.get("ids"));
                    assertArrayEquals(ids, (String[]) constant(ViewSettings.class, n + "_IDS"), e.getKey());
                    assertEquals(
                        strings(spec.get("labels")).length,
                        ids.length,
                        e.getKey() + " has one label per id");
                    final int at = (Integer) constant(ViewSettings.class, n + "_DEFAULT");
                    assertEquals(def.getAsString(), ids[at], e.getKey() + " default is among its ids");
                }
                default -> throw new AssertionError(e.getKey() + " has an unknown type");
            }
        }
        assertTrue(seen > 20, "the mod has settings in the schema");
    }

    @Test
    void anIntDefaultIsInsideItsRange() throws Exception {
        final JsonObject all = read("settings.json").getAsJsonObject("settings");
        for (final Map.Entry<String, JsonElement> e : all.entrySet()) {
            final JsonObject spec = e.getValue()
                .getAsJsonObject();
            if (!spec.has("mod") || !"int".equals(
                spec.get("type")
                    .getAsString()))
                continue;
            final String n = upper(e.getKey());
            final int def = (Integer) constant(ViewSettings.class, n + "_DEFAULT");
            final JsonElement min = forMod(spec.get("min"));
            final JsonElement max = forMod(spec.get("max"));
            if (min != null) {
                assertEquals(min.getAsInt(), constant(ViewSettings.class, n + "_MIN"), e.getKey());
                assertTrue(def >= min.getAsInt(), e.getKey() + " default is under its minimum");
            }
            if (max != null) {
                assertEquals(max.getAsInt(), constant(ViewSettings.class, n + "_MAX"), e.getKey());
                assertTrue(def <= max.getAsInt(), e.getKey() + " default is over its maximum");
            }
        }
    }

    @Test
    void aSettingNamesItsHostsHonestly() throws Exception {
        final JsonObject all = read("settings.json").getAsJsonObject("settings");
        for (final Map.Entry<String, JsonElement> e : all.entrySet()) {
            final JsonObject spec = e.getValue()
                .getAsJsonObject();
            final String parity = spec.get("parity")
                .getAsString();
            assertTrue(
                parity.equals("shared") || parity.equals("drift") || parity.equals("web") || parity.equals("mod"),
                e.getKey());
            assertEquals("web".equals(parity), !spec.has("mod"), e.getKey() + " is web only exactly when it has no mod entry");
            assertEquals("mod".equals(parity), !spec.has("web"), e.getKey() + " is mod only exactly when it has no web entry");
        }
    }

    @Test
    void theSoundTableIsConsistent() throws Exception {
        final JsonObject table = read("sounds.json");
        final Set<String> known = new HashSet<>();
        final List<String> modIds = new ArrayList<>();
        for (final JsonElement s : table.getAsJsonArray("sounds")) {
            final JsonObject o = s.getAsJsonObject();
            final String id = o.get("id")
                .getAsString();
            assertTrue(known.add(id), "duplicate sound " + id);
            for (final String host : strings(o.get("hosts"))) {
                assertTrue(host.equals("web") || host.equals("mod"), id + " host " + host);
                if (host.equals("mod")) modIds.add(id);
            }
        }
        assertArrayEquals(modIds.toArray(new String[0]), ViewSounds.IDS);

        final JsonObject ladders = table.getAsJsonObject("ladders");
        for (final Map.Entry<String, JsonElement> e : ladders.entrySet()) {
            final JsonObject l = e.getValue()
                .getAsJsonObject();
            assertTrue(known.contains(l.get("sound").getAsString()), "ladder " + e.getKey());
            final String n = upper(e.getKey());
            assertEquals(
                l.get("sound").getAsString(),
                constant(ViewSounds.class, n + "_LADDER_SOUND"));
            assertEquals(
                l.get("base").getAsDouble(),
                (Double) constant(ViewSounds.class, n + "_LADDER_BASE"),
                0.0);
            assertEquals(
                l.get("ratio").getAsDouble(),
                (Double) constant(ViewSounds.class, n + "_LADDER_RATIO"),
                0.0);
        }

        final JsonObject pitches = table.getAsJsonObject("pitches");
        for (final Map.Entry<String, JsonElement> e : pitches.entrySet()) {
            assertEquals(
                e.getValue().getAsDouble(),
                (Double) constant(ViewSounds.class, upper(e.getKey())),
                0.0,
                e.getKey());
        }

        final Set<String> eventIds = new HashSet<>();
        final Set<String> played = new HashSet<>();
        for (final JsonElement ev : table.getAsJsonArray("events")) {
            final JsonObject o = ev.getAsJsonObject();
            final String id = o.get("id")
                .getAsString();
            assertTrue(eventIds.add(id), "duplicate event " + id);
            final String[] sounds = strings(o.get("sounds"));
            assertTrue(sounds.length > 0, id);
            for (final String s : sounds) {
                assertTrue(known.contains(s), id + " plays " + s);
                played.add(s);
            }
            for (final String k : new String[] { "volume", "pitch", "pitchUp", "pitchDown" }) {
                if (o.has(k)) assertTrue(pitches.has(o.get(k).getAsString()), id + " " + k);
            }
            if (o.has("ladders")) {
                for (final String l : strings(o.get("ladders"))) {
                    assertNotNull(ladders.get(l), id + " ladder " + l);
                    final String ladderSound = ladders.getAsJsonObject(l)
                        .get("sound")
                        .getAsString();
                    assertTrue(java.util.Arrays.asList(sounds).contains(ladderSound), id + " plays its ladder's sound");
                }
            }
        }
        assertEquals(known, played, "every sound is played by some event");
        assertTrue(
            read("settings.json").getAsJsonObject("settings")
                .has(
                    table.get("gatedBy")
                        .getAsString()),
            "the setting that gates the sounds exists");
    }
}
