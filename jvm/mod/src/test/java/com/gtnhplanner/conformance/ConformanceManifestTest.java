package com.gtnhplanner.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Drift check for the conformance tree: every data file under {@code conformance/} is listed in {@code manifest.json}
 * with the sha256 and size it has, nothing listed is missing, and every area says what it specifies and who reads it.
 * The website has the same check in TypeScript ({@code conformance-manifest.test.ts});
 * {@code node tools/conformance/manifest.mjs --write} brings the manifest up to date after a deliberate change.
 */
class ConformanceManifestTest {

    private static boolean isData(final String rel) {
        return !rel.equals("manifest.json") && !rel.equals(".gitattributes")
            && !rel.substring(rel.lastIndexOf('/') + 1)
                .equals("README.md");
    }

    private static TreeSet<String> dataFiles() throws IOException {
        final Path root = Conformance.root();
        final TreeSet<String> out = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .forEach(p -> {
                    final String rel = root.relativize(p)
                        .toString()
                        .replace('\\', '/');
                    if (isData(rel)) out.add(rel);
                });
        }
        return out;
    }

    private static String sha256(final byte[] bytes) throws Exception {
        final StringBuilder sb = new StringBuilder();
        for (final byte b : MessageDigest.getInstance("SHA-256")
            .digest(bytes)) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private static JsonObject manifest() throws IOException {
        return new JsonParser().parse(Conformance.text("manifest.json"))
            .getAsJsonObject();
    }

    @Test
    void everyAreaSaysWhatItSpecifiesAndWhoReadsIt() throws Exception {
        final JsonObject m = manifest();
        assertEquals(
            1,
            m.get("version")
                .getAsInt());
        final JsonObject areas = m.getAsJsonObject("areas");
        assertTrue(
            areas.entrySet()
                .size() > 0);
        for (final Map.Entry<String, JsonElement> a : areas.entrySet()) {
            final JsonObject o = a.getValue()
                .getAsJsonObject();
            assertTrue(
                o.has("specifies") && !o.get("specifies")
                    .getAsString()
                    .isEmpty(),
                "area " + a.getKey() + " specifies");
            assertTrue(
                o.has("readers") && o.get("readers")
                    .isJsonArray(),
                "area " + a.getKey() + " readers");
        }
    }

    @Test
    void listsEveryFileInTheTreeAndNothingThatIsNotThere() throws Exception {
        final TreeSet<String> onDisk = dataFiles();
        final TreeSet<String> listed = new TreeSet<>();
        for (final Map.Entry<String, JsonElement> e : manifest().getAsJsonObject("files")
            .entrySet()) listed.add(e.getKey());
        final List<String> missing = new ArrayList<>(onDisk);
        missing.removeAll(listed);
        final List<String> stale = new ArrayList<>(listed);
        stale.removeAll(onDisk);
        assertEquals(new ArrayList<String>(), missing, "files not in the manifest");
        assertEquals(new ArrayList<String>(), stale, "manifest entries with no file");
    }

    @Test
    void holdsTheBytesTheManifestHashedInTheAreaOfItsDirectory() throws Exception {
        final JsonObject m = manifest();
        final JsonObject files = m.getAsJsonObject("files");
        final JsonObject areas = m.getAsJsonObject("areas");
        final List<String> wrong = new ArrayList<>();
        for (final String rel : dataFiles()) {
            if (!files.has(rel)) continue;
            final JsonObject entry = files.getAsJsonObject(rel);
            final byte[] bytes = Files.readAllBytes(Conformance.path(rel));
            if (!sha256(bytes).equals(
                entry.get("sha256")
                    .getAsString()))
                wrong.add(rel + ": sha256");
            else if (bytes.length != entry.get("bytes")
                .getAsLong()) wrong.add(rel + ": size");
            String area = null;
            for (final Map.Entry<String, JsonElement> a : areas.entrySet()) {
                if (rel.startsWith(a.getKey() + "/") && (area == null || a.getKey()
                    .length() > area.length())) area = a.getKey();
            }
            if (area == null || !area.equals(
                entry.get("area")
                    .getAsString()))
                wrong.add(rel + ": area");
        }
        assertEquals(new ArrayList<String>(), wrong);
    }
}
