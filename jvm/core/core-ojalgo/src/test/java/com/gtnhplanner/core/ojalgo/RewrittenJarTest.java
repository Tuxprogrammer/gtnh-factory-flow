package com.gtnhplanner.core.ojalgo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The jar on the classpath is the rewritten ojAlgo: the classes whose maths decide the solver's numbers name core-base's
 * ExactMath and NumberText (log10 in ModelEntity's scaling through MissingMath, pow in NumberContext's tolerances,
 * BigDecimal.valueOf in ModelEntity), and every class that made a HashMap or HashSet makes core-base's JavaHashMap or
 * JavaHashSet instead (the LP's rows in ExpressionsBasedModel, a row's terms in Expression).
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

    /** The classes of the published jar that named HashMap or HashSet (javap on ojAlgo 57.4.0). */
    private static final List<String> HASHED = List.of("org/ojalgo/concurrent/ProcessOptions$Builder",
        "org/ojalgo/concurrent/ProcessOptions", "org/ojalgo/data/cluster/FeatureBasedClusterer",
        "org/ojalgo/data/cluster/GeneralisedKMeans", "org/ojalgo/data/cluster/GreedyClustering",
        "org/ojalgo/data/cluster/RandomClustering", "org/ojalgo/data/cluster/SpectralClusterer",
        "org/ojalgo/optimisation/Expression", "org/ojalgo/optimisation/ExpressionsBasedModel",
        "org/ojalgo/optimisation/FileFormatMPS", "org/ojalgo/optimisation/GenericSolver$Builder",
        "org/ojalgo/optimisation/integer/FlowCoverSeparator", "org/ojalgo/optimisation/integer/GMISeparator",
        "org/ojalgo/optimisation/integer/NodeSolver", "org/ojalgo/optimisation/IntermediateSolver",
        "org/ojalgo/random/FrequencyMap", "org/ojalgo/series/CoordinationSet", "org/ojalgo/type/collection/CloseableMap");

    @Test
    void theSolversMapsIterateAsTheJdks() throws Exception {
        for (final String name : HASHED) {
            final String text = classText(name);
            assertFalse(text.contains("java/util/HashMap"), name + " still names HashMap");
            assertFalse(text.contains("java/util/HashSet"), name + " still names HashSet");
            assertTrue(text.contains("com/gtnhplanner/core/base/JavaHash"), name + " names JavaHashMap or JavaHashSet");
            // Loading and initialising verifies the rewritten code (stack maps, the method handle of HashMap::new).
            Class.forName(name.replace('/', '.'), true, RewrittenJarTest.class.getClassLoader());
        }
        // A subclass of HashMap now extends JavaHashMap, its super calls included.
        final Class<?> set = Class.forName("org.ojalgo.series.CoordinationSet");
        assertEquals("com.gtnhplanner.core.base.JavaHashMap", set.getSuperclass().getName());
        @SuppressWarnings("unchecked")
        final Map<String, Object> made = (Map<String, Object>) set.getConstructor(Map.class).newInstance(Map.of());
        assertTrue(made.isEmpty() && made.get("x") == null);
    }
}
