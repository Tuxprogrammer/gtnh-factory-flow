package com.gtnhplanner.core.ojalgo;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * The jar on the classpath is the rewritten ojAlgo: the classes whose maths decide the solver's numbers name core-base's
 * ExactMath and NumberText (log10 in ModelEntity's scaling through MissingMath, pow in NumberContext's tolerances,
 * BigDecimal.valueOf in ModelEntity).
 */
class RewrittenJarTest {

    /** A class file's bytes as Latin-1 text, so constant-pool names can be searched. */
    private static String classText(final String name) throws IOException {
        try (InputStream in = RewrittenJarTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertTrue(in != null, name + " is not on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    @Test
    void theSolversMathsGoesToCoreBase() throws IOException {
        final String missing = classText("org/ojalgo/function/special/MissingMath");
        assertTrue(missing.contains("com/gtnhplanner/core/base/ExactMath"), "MissingMath calls ExactMath");
        final String context = classText("org/ojalgo/type/context/NumberContext");
        assertTrue(context.contains("com/gtnhplanner/core/base/ExactMath"), "NumberContext calls ExactMath");
        final String entity = classText("org/ojalgo/optimisation/ModelEntity");
        assertTrue(entity.contains("com/gtnhplanner/core/base/NumberText"), "ModelEntity calls NumberText.javaDecimal");
        assertTrue(entity.contains("javaDecimal"), "ModelEntity calls NumberText.javaDecimal");
    }
}
