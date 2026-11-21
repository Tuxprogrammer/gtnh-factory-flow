package com.gtnhplanner.core.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.conformance.Conformance;

/**
 * Uuid against java.util.UUID on this JVM, over many ids: the same hash, order, equality, spelling, parse and
 * name-based ids, so a map, a sort or a saved id that moves from one to the other changes nothing. The committed
 * vectors (uuid/uuid-vectors.json) are what the web runner will check the compiled core against.
 */
class UuidTest {

    private static final int MANY = 100_000;

    private static Uuid of(final UUID u) {
        return new Uuid(u.getMostSignificantBits(), u.getLeastSignificantBits());
    }

    private static List<UUID> ids(final long seed, final int n) {
        final Random r = new Random(seed);
        final List<UUID> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            // Random version 4 ids as the mod makes them, and any 128 bits (sign bits set and not).
            out.add(i % 2 == 0 ? new UUID(r.nextLong() & ~0xf000L | 0x4000L, r.nextLong() & 0x3fffffffffffffffL
                | 0x8000000000000000L) : new UUID(r.nextLong(), r.nextLong()));
        }
        return out;
    }

    @Test
    void hashEqualsStringAndParseAgreeOverManyIds() {
        for (final UUID u : ids(1, MANY)) {
            final Uuid v = of(u);
            assertEquals(u.hashCode(), v.hashCode());
            assertEquals(u.toString(), v.toString());
            assertEquals(v, Uuid.fromString(u.toString()));
            assertEquals(v, Uuid.fromString(u.toString()
                .toUpperCase(java.util.Locale.ROOT)));
            assertEquals(u, UUID.fromString(v.toString()));
            assertEquals(u.version(), v.version());
            assertEquals(u.variant(), v.variant());
            assertEquals(v, new Uuid(u.getMostSignificantBits(), u.getLeastSignificantBits()));
        }
    }

    @Test
    void orderAgreesOverManyIds() {
        final List<UUID> a = ids(2, MANY);
        final List<UUID> b = ids(3, MANY);
        for (int i = 0; i < MANY; i++) {
            assertEquals(Integer.signum(a.get(i)
                .compareTo(b.get(i))),
                Integer.signum(
                    of(a.get(i)).compareTo(of(b.get(i)))));
            assertEquals(a.get(i)
                .equals(b.get(i)),
                of(a.get(i)).equals(of(b.get(i))));
        }
        final List<UUID> sortedJdk = new ArrayList<>(a);
        sortedJdk.sort(null);
        final List<Uuid> sortedMine = new ArrayList<>();
        for (final UUID u : a) sortedMine.add(of(u));
        sortedMine.sort(null);
        for (int i = 0; i < MANY; i++) assertEquals(sortedJdk.get(i)
            .toString(),
            sortedMine.get(i)
                .toString());
    }

    @Test
    void mapAndSetIterationOrderIsTheSame() {
        final List<UUID> a = ids(4, 5000);
        final Map<UUID, Integer> jdk = new HashMap<>();
        final Map<Uuid, Integer> mine = new HashMap<>();
        final Set<UUID> jdkSet = new HashSet<>();
        final Set<Uuid> mineSet = new HashSet<>();
        for (int i = 0; i < a.size(); i++) {
            jdk.put(a.get(i), i);
            mine.put(of(a.get(i)), i);
            jdkSet.add(a.get(i));
            mineSet.add(of(a.get(i)));
        }
        final List<String> x = new ArrayList<>();
        final List<String> y = new ArrayList<>();
        jdk.forEach((k, v) -> x.add(k + "=" + v));
        mine.forEach((k, v) -> y.add(k + "=" + v));
        assertEquals(x, y);
        final List<String> xs = new ArrayList<>();
        final List<String> ys = new ArrayList<>();
        jdkSet.forEach(k -> xs.add(k.toString()));
        mineSet.forEach(k -> ys.add(k.toString()));
        assertEquals(xs, ys);
    }

    @Test
    void nameBasedIdsAgreeOverManyNames() {
        final Random r = new Random(5);
        for (int i = 0; i < MANY; i++) {
            final byte[] name = new byte[r.nextInt(i % 10 == 0 ? 300 : 70)];
            r.nextBytes(name);
            assertEquals(UUID.nameUUIDFromBytes(name)
                .toString(),
                Uuid.nameUUIDFromBytes(name)
                    .toString());
        }
        // The ids the mod makes from website ids (cloud/map/PlanIds, layout/arrange/RouterJudge, ModelData).
        for (final String id : List.of("", "node-1", "drawer-12#in", "edge-a>b#3", "ü-ñ-漢字", "x".repeat(1000))) {
            final byte[] name = id.getBytes(StandardCharsets.UTF_8);
            assertEquals(UUID.nameUUIDFromBytes(name), UUID.fromString(Uuid.nameUUIDFromBytes(name)
                .toString()));
        }
    }

    /** What the JDK reads from a string, or the exception type it throws. */
    private static String jdk(final String s) {
        try {
            return UUID.fromString(s)
                .toString();
        } catch (final IllegalArgumentException e) {
            return e.getClass()
                .getSimpleName();
        }
    }

    private static String mine(final String s) {
        try {
            return Uuid.fromString(s)
                .toString();
        } catch (final IllegalArgumentException e) {
            return e.getClass()
                .getSimpleName();
        }
    }

    @Test
    void oddSpellingsReadAsTheJdkReadsThem() {
        final List<String> odd = new ArrayList<>(List.of("1-2-3-4-5", "+1-2-3-4-5", "-1-2-3-4-5", "0-0-0-0-0",
            "ffffffffffffffff-1-1-1-1", "7fffffffffffffff-1-1-1-1", "-8000000000000000-1-1-1-1", "", "-", "----",
            "1-2-3-4", "1-2-3-4-5-6", "1--3-4-5", "123e4567-e89b-12d3-a456-42661417400g",
            "123e4567-e89b-12d3-a456-4266141740000", "123E4567-E89B-12D3-A456-426614174000",
            "+23e4567-e89b-12d3-a456-426614174000", "123e4567-e89b-12d3-a456-+26614174000", " 1-2-3-4-5",
            "1-2-3-4-5 ", "123456789-1-1-1-1", "1-12345-1-1-1", "1-1-1-1-1234567890123", "1-1-1-+-1"));
        final Random r = new Random(6);
        final String alphabet = "0123456789abcdefABCDEFg-+ ";
        for (int i = 0; i < 50_000; i++) {
            final StringBuilder b = new StringBuilder();
            final int n = r.nextInt(42);
            for (int j = 0; j < n; j++) b.append(alphabet.charAt(r.nextInt(alphabet.length())));
            odd.add(b.toString());
        }
        // Near-misses of the usual form: one character changed.
        for (final UUID u : ids(7, 20_000)) {
            final char[] c = u.toString()
                .toCharArray();
            c[r.nextInt(36)] = alphabet.charAt(r.nextInt(alphabet.length()));
            odd.add(new String(c));
        }
        for (final String s : odd) assertEquals(jdk(s), mine(s), "'" + s + "'");
    }

    @Test
    void otherScriptsDigitsAreRejectedWhereTheJdkTakesThem() {
        // The one deliberate difference (see Uuid): the web compile need not share the JDK's Unicode digit tables.
        assertEquals("00000003-0002-0003-0004-000000000005", jdk("٣-2-3-4-5"));
        assertThrows(NumberFormatException.class, () -> Uuid.fromString("٣-2-3-4-5"));
    }

    @Test
    void randomIdsAreVersionFourAndDistinct() {
        final Set<Uuid> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            final Uuid u = Uuid.randomUUID();
            assertEquals(4, u.version());
            assertEquals(2, u.variant());
            assertTrue(seen.add(u));
        }
    }

    @Test
    void committedVectorsAgreeWithTheJdkAndUuid() throws Exception {
        final JsonObject v;
        try (Reader r = new InputStreamReader(Conformance.open("core-base/uuid/uuid-vectors.json"),
            StandardCharsets.UTF_8)) {
            v = new JsonParser().parse(r)
                .getAsJsonObject();
        }
        assertEquals(vectors().toString(), v.toString(), "uuid-vectors.json is out of date: run writeVectors");
        for (final JsonElement e : v.getAsJsonArray("ids")) {
            final JsonArray a = e.getAsJsonArray();
            final Uuid u = new Uuid(Long.parseUnsignedLong(a.get(0)
                .getAsString(), 16),
                Long.parseUnsignedLong(
                    a.get(1)
                        .getAsString(),
                    16));
            assertEquals(a.get(2)
                .getAsString(), u.toString());
            assertEquals(a.get(3)
                .getAsInt(), u.hashCode());
        }
    }

    /** Rewrites conformance/core-base/uuid/uuid-vectors.json: run with -PwriteVectors (core-base's build.gradle.kts). */
    @Test
    void writeVectors() throws Exception {
        if (System.getProperty("gtnh.writeVectors") == null) return;
        Files.writeString(Conformance.path("core-base/uuid/uuid-vectors.json"), vectors() + "\n");
    }

    /**
     * The vectors, from java.util.UUID: ids as [msb hex, lsb hex, toString, hashCode]; their order as indices sorted
     * by compareTo; name-based ids as [UTF-8 name, id]; parsed spellings as [text, id or the exception's name].
     */
    private static JsonObject vectors() {
        final JsonObject out = new JsonObject();
        out.addProperty("about", "java.util.UUID's own answers for core-base's Uuid (UuidTest writes and checks"
            + " this file). ids: [msb hex, lsb hex, toString, hashCode]; order: the ids' indices sorted by compareTo;"
            + " names: [UTF-8 text, nameUUIDFromBytes]; parse: [text, fromString or the exception's simple name].");
        final List<UUID> ids = ids(8, 400);
        final JsonArray idRows = new JsonArray();
        for (final UUID u : ids) {
            final JsonArray row = new JsonArray();
            row.add(new com.google.gson.JsonPrimitive(Long.toHexString(u.getMostSignificantBits())));
            row.add(new com.google.gson.JsonPrimitive(Long.toHexString(u.getLeastSignificantBits())));
            row.add(new com.google.gson.JsonPrimitive(u.toString()));
            row.add(new com.google.gson.JsonPrimitive(u.hashCode()));
            idRows.add(row);
        }
        out.add("ids", idRows);
        final List<Integer> order = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) order.add(i);
        order.sort((x, y) -> ids.get(x)
            .compareTo(ids.get(y)));
        final JsonArray orderRow = new JsonArray();
        for (final int i : order) orderRow.add(new com.google.gson.JsonPrimitive(i));
        out.add("order", orderRow);
        final JsonArray names = new JsonArray();
        for (final String n : List.of("", "node-1", "drawer-12#in", "edge-a>b#3", "ü-ñ-漢字", "plan#meta",
            "a".repeat(55), "a".repeat(56), "a".repeat(64), "a".repeat(200))) {
            final JsonArray row = new JsonArray();
            row.add(new com.google.gson.JsonPrimitive(n));
            row.add(new com.google.gson.JsonPrimitive(UUID.nameUUIDFromBytes(n.getBytes(StandardCharsets.UTF_8))
                .toString()));
            names.add(row);
        }
        out.add("names", names);
        final JsonArray parse = new JsonArray();
        for (final String s : List.of("123e4567-e89b-12d3-a456-426614174000", "123E4567-E89B-12D3-A456-426614174000",
            "1-2-3-4-5", "-1-2-3-4-5", "ffffffffffffffff-1-1-1-1", "1-2-3-4", "", "node-1",
            "123e4567-e89b-12d3-a456-4266141740000")) {
            final JsonArray row = new JsonArray();
            row.add(new com.google.gson.JsonPrimitive(s));
            row.add(new com.google.gson.JsonPrimitive(jdk(s)));
            parse.add(row);
        }
        out.add("parse", parse);
        return out;
    }
}
