package com.gtnhplanner.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.solver.SolverCodec;

/**
 * No solver class makes a java.util.HashMap or HashSet: the web compile's HashMap iterates in another order than the
 * JDK's, and the solver's loops over its maps and sets decide the order of LP rows and of sums, so the web's answers
 * moved in the last bits. The solver uses core-base's JavaHashMap and JavaHashSet (the JDK's order on every target), or
 * a LinkedHashMap or TreeMap. ojAlgo's own maps are replaced when its jar is rewritten (core-ojalgo).
 */
class HashOrderTest {

    @Test
    void theSolverIteratesNoHostHashMap() throws Exception {
        final Path classes = Path.of(
            SolverCodec.class.getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        assertTrue(Files.isDirectory(classes), "the solver's classes are a directory: " + classes);
        final List<Path> files;
        try (Stream<Path> walk = Files.walk(classes)) {
            files = walk.filter(
                p -> p.toString()
                    .endsWith(".class"))
                .toList();
        }
        assertTrue(files.size() > 50, "found the solver's classes: " + files.size());
        final List<String> naming = files.stream()
            .filter(p -> {
                try {
                    final String text = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
                    return text.contains("java/util/HashMap") || text.contains("java/util/HashSet");
                } catch (final java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            })
            .map(p -> classes.relativize(p)
                .toString())
            .toList();
        assertEquals(List.of(), naming, "solver classes that make a java.util.HashMap or HashSet");
    }
}
