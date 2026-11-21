import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * Checks the web build's class overrides against the classpath TeaVM compiles from (single-file Java; run by
 * :web:checkOverrides*).
 *
 * <p>An override is a class of the web module that has the same name as a class in another classpath entry (a classlib
 * class, a library class). TeaVM uses the first class of a name on its classpath, in order, so an override works only
 * when the module's own classes come first. The check fails when:
 * <ul>
 * <li>an override is not the first entry that has its name (it would be ignored, silently);
 * <li>the classes that shadow another class are not exactly the ones overrides.txt lists: an unlisted one is a surprise,
 * a listed one that shadows nothing is an override whose target a plugin or library upgrade moved or renamed.
 * </ul>
 *
 * <p>Arguments: {@code --overrides <file>} {@code --own <the module's classes directory>} {@code --classpath <entry>}
 * (repeated, in TeaVM's order, including the own directory) {@code --report <file>}.
 */
public final class OverrideCheck {

    public static void main(final String[] args) throws IOException {
        Path overrides = null, own = null, report = null;
        final List<Path> classpath = new ArrayList<>();
        for (int i = 0; i < args.length; i += 2) {
            final Path value = Path.of(args[i + 1]);
            switch (args[i]) {
                case "--overrides" -> overrides = value;
                case "--own" -> own = value;
                case "--classpath" -> classpath.add(value);
                case "--report" -> report = value;
                default -> throw new IllegalArgumentException("unknown argument " + args[i]);
            }
        }
        final List<String> problems = new ArrayList<>();
        final List<String> lines = new ArrayList<>();

        final Map<String, String> listed = new TreeMap<>();
        for (final String line : Files.readAllLines(overrides)) {
            final String text = line.strip();
            if (text.isEmpty() || text.startsWith("#")) continue;
            final int space = text.indexOf(' ');
            if (space < 0) problems.add("overrides.txt: '" + text + "' has no reason after the class name");
            else listed.put(text.substring(0, space), text.substring(space + 1));
        }

        final Path ownDir = own.toAbsolutePath().normalize();
        final int ownIndex = indexOf(classpath, ownDir);
        if (ownIndex < 0) problems.add("the module's classes " + ownDir + " are not on the classpath at all");

        // Outer class name -> the classpath indices that have it.
        final Map<String, List<Integer>> where = new TreeMap<>();
        for (int i = 0; i < classpath.size(); i++) {
            for (final String name : classesOf(classpath.get(i))) where.computeIfAbsent(name, k -> new ArrayList<>()).add(i);
        }
        final TreeSet<String> shadowing = new TreeSet<>();
        for (final Map.Entry<String, List<Integer>> e : where.entrySet()) {
            if (e.getValue().contains(ownIndex) && e.getValue().size() > 1) shadowing.add(e.getKey());
        }

        for (final String name : listed.keySet()) {
            final List<Integer> at = where.getOrDefault(name, List.of());
            if (!at.contains(ownIndex)) {
                problems.add(name + ": listed in overrides.txt but the module has no such class");
            } else if (at.size() == 1) {
                problems.add(name + ": listed as an override but nothing else on the classpath has this class, so it replaces"
                    + " nothing (the class it replaced was renamed or moved by an upgrade?)");
            } else if (at.get(0) != ownIndex) {
                problems.add(name + ": not first on the classpath, so TeaVM compiles " + classpath.get(at.get(0))
                    + " instead of the override");
            } else {
                lines.add("ok " + name + " (first of " + at.size() + ": replaces a class in " + classpath.get(at.get(1)).getFileName() + ")");
            }
        }
        for (final String name : shadowing) {
            if (!listed.containsKey(name)) problems.add(name + ": replaces a class on the classpath but is not in overrides.txt"
                + " (an accidental name clash, or list it with its reason)");
        }

        lines.add("classpath (" + classpath.size() + " entries, own classes at index " + ownIndex + ")");
        for (int i = 0; i < classpath.size(); i++) lines.add("  " + i + " " + classpath.get(i));
        for (final String p : problems) lines.add("PROBLEM " + p);
        Files.createDirectories(report.getParent());
        Files.write(report, lines);
        lines.stream().filter(l -> l.startsWith("ok ") || l.startsWith("PROBLEM ")).forEach(System.out::println);
        if (!problems.isEmpty()) {
            System.err.println("override check FAILED: " + problems.size() + " problem(s); see " + report);
            System.exit(1);
        }
        System.out.println("override check passed: " + listed.size() + " override(s), first on the classpath");
    }

    private static int indexOf(final List<Path> classpath, final Path dir) {
        for (int i = 0; i < classpath.size(); i++) {
            if (classpath.get(i).toAbsolutePath().normalize().equals(dir)) return i;
        }
        return -1;
    }

    /** Outer class names ({@code a.b.C}, no {@code $...}) in a jar or a classes directory; module-info and the like skipped. */
    private static TreeSet<String> classesOf(final Path entry) throws IOException {
        final TreeSet<String> out = new TreeSet<>();
        if (Files.isDirectory(entry)) {
            try (Stream<Path> walk = Files.walk(entry)) {
                for (final Path p : (Iterable<Path>) walk::iterator) {
                    if (Files.isRegularFile(p)) add(out, entry.relativize(p).toString().replace('\\', '/'));
                }
            }
        } else if (Files.isRegularFile(entry)) {
            try (ZipFile zip = new ZipFile(entry.toFile())) {
                zip.stream().forEach(z -> add(out, z.getName()));
            }
        }
        return out;
    }

    private static void add(final TreeSet<String> out, final String path) {
        if (!path.endsWith(".class") || path.startsWith("META-INF/") || path.contains("module-info")) return;
        final String name = path.substring(0, path.length() - ".class".length()).replace('/', '.');
        final int dollar = name.indexOf('$');
        out.add(dollar < 0 ? name : name.substring(0, dollar));
    }
}
