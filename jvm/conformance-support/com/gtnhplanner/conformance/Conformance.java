package com.gtnhplanner.conformance;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where a test finds the conformance tree (the repository's {@code conformance/}), the one copy of the test data both
 * the website and the game must agree on. Compiled into the test source set of every Gradle project under {@code jvm/}
 * (see {@code jvm/build.gradle.kts}); never into a main source set.
 *
 * <p>
 * The tree is found by the system property {@code gtnh.conformance}, which Gradle sets on every Test task. A test run
 * from an IDE has no such property, so the lookup walks up from the working directory to the first directory holding
 * {@code conformance/manifest.json}. Nothing is read through the classpath, so a file that is not in the tree cannot be
 * found by accident, and a golden-writing run writes to the real file.
 */
public final class Conformance {

    public static final String PROPERTY = "gtnh.conformance";

    private static Path root;

    private Conformance() {}

    /** The conformance directory. */
    public static synchronized Path root() {
        if (root == null) root = locate();
        return root;
    }

    private static Path locate() {
        final String set = System.getProperty(PROPERTY);
        if (set != null && !set.isEmpty()) {
            final Path p = Paths.get(set)
                .toAbsolutePath();
            if (!Files.isRegularFile(p.resolve("manifest.json"))) {
                throw new IllegalStateException(PROPERTY + " is " + p + ", which holds no manifest.json");
            }
            return p;
        }
        Path dir = Paths.get("")
            .toAbsolutePath();
        while (dir != null) {
            final Path candidate = dir.resolve("conformance");
            if (Files.isRegularFile(candidate.resolve("manifest.json"))) return candidate;
            dir = dir.getParent();
        }
        throw new IllegalStateException(
            "no conformance tree: set -D" + PROPERTY + "=<dir> or run under a checkout holding conformance/manifest.json");
    }

    /** A path under the tree, written with forward slashes. Not checked to exist (a golden writer creates it). */
    public static Path path(final String relative) {
        return root().resolve(relative);
    }

    /** Opens a file of the tree; the caller closes it. */
    public static InputStream open(final String relative) throws IOException {
        final Path p = path(relative);
        if (!Files.isRegularFile(p)) throw new IOException("no conformance file " + relative + " (looked in " + p + ")");
        return Files.newInputStream(p);
    }

    /** The regular files directly under a directory of the tree, sorted by name. */
    public static List<Path> list(final String relativeDir) throws IOException {
        final List<Path> out = new ArrayList<>();
        try (java.nio.file.DirectoryStream<Path> s = Files.newDirectoryStream(path(relativeDir))) {
            for (final Path p : s) if (Files.isRegularFile(p)) out.add(p);
        }
        Collections.sort(out);
        return out;
    }

    /** The whole file as UTF-8 text. */
    public static String text(final String relative) throws IOException {
        return new String(Files.readAllBytes(path(relative)), StandardCharsets.UTF_8);
    }
}
