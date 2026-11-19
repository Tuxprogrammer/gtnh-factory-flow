package com.gtnhplanner.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.teavm.jso.JSExport;
import org.teavm.jso.JSObject;

/**
 * The export convention (README.md): each core module has one class {@code <Module>Exports} that the host sees as an
 * object of functions, names are unique inside it, and only types that cross the boundary the same way on both targets are used. The
 * build exports every class in the exports package without listing it; this test is what keeps those classes honest.
 */
class ExportConventionTest {

    private static final File EXPORTS = new File("src/main/java/com/gtnhplanner/web/exports");

    private static List<Class<?>> exportClasses() throws Exception {
        final List<Class<?>> out = new ArrayList<>();
        final String[] files = EXPORTS.list((d, n) -> n.endsWith(".java"));
        assertTrue(files != null && files.length > 0, "no exports classes found under " + EXPORTS.getAbsolutePath());
        for (final String f : files) out.add(Class.forName("com.gtnhplanner.web.exports." + f.replace(".java", "")));
        out.add(WebCore.class);
        out.add(WebSolver.class);
        return out;
    }

    private static void assertExportsClassName(final Class<?> c) {
        final String n = c.getSimpleName();
        assertTrue(n.endsWith("Exports") && n.length() > "Exports".length() && Character.isUpperCase(n.charAt(0)),
            n + ": an exports class is named <Module>Exports (TeaVM exports it by that name, so it is the namespace)");
    }

    private static boolean crosses(final Class<?> t) {
        return t == int.class || t == double.class || t == boolean.class || t == String.class || t == void.class
            || JSObject.class.isAssignableFrom(t);
    }

    @Test
    void exportNamesAreUniqueInTheirNamespace() throws Exception {
        int count = 0;
        for (final Class<?> c : exportClasses()) {
            final boolean main = c == WebCore.class || c == WebSolver.class;
            if (!main) assertExportsClassName(c);
            final Map<String, Method> seen = new HashMap<>();
            for (final Method m : c.getDeclaredMethods()) {
                if (!m.isAnnotationPresent(JSExport.class)) continue;
                count++;
                final String name = m.getName();
                final String where = c.getSimpleName() + "." + name;
                assertTrue(Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers()),
                    where + ": an export is public static");
                assertEquals(null, seen.put(name, m), where + " is exported twice (an overload would clash in JavaScript)");
                // The main class's exports are top-level functions next to every module's class: they carry a prefix.
                if (main) assertTrue(name.startsWith("webCore") && name.length() > 7 && Character.isUpperCase(name.charAt(7)),
                    where + ": a top-level export starts with 'webCore' then a capital");
                else assertTrue(Character.isLowerCase(name.charAt(0)), where + ": a function name starts lower case");
            }
        }
        assertTrue(count >= 10, "only " + count + " exports found");
    }

    @Test
    void exportsTakeAndReturnOnlyTypesThatCrossTheBoundaryTheSameOnBothTargets() throws Exception {
        for (final Class<?> c : exportClasses()) {
            for (final Method m : c.getDeclaredMethods()) {
                if (!m.isAnnotationPresent(JSExport.class)) continue;
                final String where = c.getSimpleName() + "." + m.getName();
                assertTrue(crosses(m.getReturnType()), where + ": return type " + m.getReturnType().getName()
                    + " (long, arrays and Java objects do not cross; use int, double, boolean, String or a JSObject)");
                for (final Class<?> p : m.getParameterTypes()) {
                    assertTrue(crosses(p) && p != void.class, where + ": parameter type " + p.getName());
                }
            }
        }
    }

    @Test
    void anExportsClassHasNoOtherPublicStaticFunctions() throws Exception {
        for (final Class<?> c : exportClasses()) {
            if (c == WebCore.class || c == WebSolver.class) continue;
            for (final Method m : c.getDeclaredMethods()) {
                if (!Modifier.isPublic(m.getModifiers()) || !Modifier.isStatic(m.getModifiers())) continue;
                assertTrue(m.isAnnotationPresent(JSExport.class), c.getSimpleName() + "." + m.getName()
                    + " is public static but not exported: make it private or export it");
            }
            assertFalse(Modifier.isAbstract(c.getModifiers()), c.getSimpleName());
        }
    }

    /** modules.txt names a main class and the exports classes of each module; every exports class is in exactly one. */
    @Test
    void everyExportsClassBelongsToOneModule() throws Exception {
        final List<String> all = new ArrayList<>();
        for (final String f : EXPORTS.list((d, n) -> n.endsWith(".java"))) all.add(f.replace(".java", ""));
        final List<String> named = new ArrayList<>();
        int wildcards = 0;
        for (final String line : java.nio.file.Files.readAllLines(new File("modules.txt").toPath())) {
            if (line.isBlank() || line.startsWith("#")) continue;
            final String[] parts = line.trim().split("\\s+");
            assertTrue(parts.length >= 3, "modules.txt: " + line);
            Class.forName("com.gtnhplanner.web." + parts[1]);
            for (int i = 2; i < parts.length; i++) {
                if (parts[i].equals("*")) wildcards++;
                else {
                    assertTrue(all.contains(parts[i]), "modules.txt names " + parts[i] + ", which is not an exports class");
                    assertFalse(named.contains(parts[i]), parts[i] + " is in two modules");
                    named.add(parts[i]);
                }
            }
        }
        assertEquals(1, wildcards, "exactly one module takes the rest (*)");
    }
}
