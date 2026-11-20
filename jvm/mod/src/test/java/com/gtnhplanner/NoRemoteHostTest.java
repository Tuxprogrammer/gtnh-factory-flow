package com.gtnhplanner;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * A jar built from this repository contacts no host until its configuration names one
 * ({@link com.gtnhplanner.library.SiteConfig}), so no address of a real remote host may sit in the sources: a default
 * would be a host every install talks to. This reads the mod's, the core's and the web build's sources and fails on
 * a web address (http, https, ws, wss) whose host is not one of the allowed kinds, and on a named host that this
 * project once defaulted to, wherever it is written.
 */
class NoRemoteHostTest {

    /** The source trees, from the mod's directory (where its tests run). */
    private static final List<Path> ROOTS = List.of(Paths.get("src"), Paths.get("../core"), Paths.get("../web"));

    /** What is read: text files that can hold an address. Binary resources are not. */
    private static final Set<String> TEXT = Set
        .of("java", "json", "info", "properties", "lang", "txt", "cfg", "mcmeta");

    /** Never read: build output and tool caches. */
    private static final List<String> SKIPPED_DIRS = List.of("/build/", "/.gradle/", "/node_modules/");

    /** A licence header's link: a document, not a contact. */
    private static final Set<String> ALLOWED_HOSTS = Set.of("www.apache.org");

    /** Hosts that are this machine, or that can never resolve: reserved names (RFC 2606, RFC 6761). */
    private static boolean harmless(final String host) {
        final String h = host.toLowerCase(Locale.ROOT);
        return !h.contains(".") // localhost, host, x: not a name on the internet
            || h.equals("127.0.0.1")
            || h.equals("[::1]")
            || h.endsWith(".localhost")
            || h.equals("example.com")
            || h.equals("example.org")
            || h.equals("example.net")
            || h.endsWith(".example")
            || h.endsWith(".test")
            || h.endsWith(".invalid")
            || ALLOWED_HOSTS.contains(h);
    }

    private static final Pattern URL = Pattern
        .compile("\\b(?:https?|wss?)://(?:[^/\\s\"'<>@]*@)?([A-Za-z0-9.\\[\\]:-]+)");

    /** Hosts and names this project defaulted to before: they stay out of every source, scheme or not. */
    private static final Pattern WAS_DEFAULT = Pattern
        .compile("gtnhplanner\\.com|jackwrichards|github\\.com|api\\.github|discord\\.(?:com|gg)|tuxhpc");

    /** The few lines allowed to name one anyway: a path suffix and a line fragment, each with its reason. */
    private static final List<String[]> ALLOWED_LINES = List.of(
        // The mod's licence names its copyright holder.
        new String[] { "src/main/resources/LICENSE", "jackwrichards" },
        // The credit for the mod this one was built on.
        new String[] { "src/main/resources/mcmod.info", "github.com/sbancuz/PlanNH" },
        // A comment in a file the model stream owns; drop this line when it says "the website" instead.
        new String[] { "data/flowchart/Graph.java", "The post on gtnhplanner.com" },
        // This test spells the names it hunts.
        new String[] { "NoRemoteHostTest.java", "" });

    private static boolean allowedLine(final Path file, final String line) {
        final String p = file.toString()
            .replace('\\', '/');
        for (final String[] a : ALLOWED_LINES) if (p.endsWith(a[0]) && line.contains(a[1])) return true;
        return false;
    }

    private static boolean text(final Path p) {
        final String n = p.getFileName()
            .toString();
        final String s = p.toString()
            .replace('\\', '/');
        for (final String d : SKIPPED_DIRS) if (s.contains(d)) return false;
        if (n.equals("LICENSE")) return true;
        final int dot = n.lastIndexOf('.');
        return dot > 0 && TEXT.contains(
            n.substring(dot + 1)
                .toLowerCase(Locale.ROOT));
    }

    /** Every offending line of a file, as {@code path:line: text}. */
    static List<String> offences(final Path file, final List<String> lines) {
        final List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            final String line = lines.get(i);
            if (allowedLine(file, line)) continue;
            boolean bad = WAS_DEFAULT.matcher(line)
                .find();
            final Matcher m = URL.matcher(line);
            while (m.find()) {
                if (!harmless(
                    m.group(1)
                        .replaceAll(":\\d*$", "")))
                    bad = true;
            }
            if (bad) out.add(file + ":" + (i + 1) + ": " + line.strip());
        }
        return out;
    }

    @Test
    void noSourceNamesARemoteHost() throws IOException {
        final List<String> found = new ArrayList<>();
        int files = 0;
        for (final Path root : ROOTS) {
            if (!Files.isDirectory(root)) continue;
            final List<Path> sources;
            try (Stream<Path> all = Files.walk(root)) {
                sources = all.filter(Files::isRegularFile)
                    .filter(NoRemoteHostTest::text)
                    .toList();
            }
            for (final Path f : sources) {
                final List<String> lines;
                try {
                    lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                } catch (final java.nio.charset.MalformedInputException e) {
                    continue; // Not UTF-8 text: not a source of addresses.
                }
                files++;
                found.addAll(offences(f, lines));
            }
        }
        assertTrue(files > 200, "the scan found the sources (" + files + " files)");
        assertTrue(
            found.isEmpty(),
            "A remote host is named in the sources; a host comes from configuration (SiteConfig), never from code:\n"
                + String.join("\n", found));
    }

    @Test
    void theScanSeesWhatItIsMeantToCatch() {
        final Path f = Paths.get("src/main/java/Example.java");
        for (final String bad : new String[] { "String s = \"https://planner.example.org/x\";",
            "String s = \"wss://live.shop/live\";", "// see https://api.somewhere.dev/v1", "x = \"gtnhplanner.com\";",
            "https://user@host.io/", "see discord.com/channels/1" })
            assertTrue(!offences(f, List.of(bad)).isEmpty(), bad);
        for (final String ok : new String[] { "\"http://127.0.0.1:8080\"", "\"ws://localhost:3001/live\"",
            "\"https://planner.test/#p=\"", "\"https://example.com/p.png\"", "\"http://staging.test\"",
            "\"https://host\"", "http://www.apache.org/licenses/LICENSE-2.0", "\"https://a.invalid/\"" })
            assertTrue(offences(f, List.of(ok)).isEmpty(), ok);
    }
}
