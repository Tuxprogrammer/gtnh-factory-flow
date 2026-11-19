package com.gtnhplanner.solver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.gtnhplanner.data.flowchart.balancer.Balancer;

/**
 * Every corpus case's whole answer (the 33 golden cases and the 74 Up to plans), as UpToParityTest digests it, one file
 * per case under {@code build/digests/<label>/}, then the whole corpus timed: a change that must not move any answer
 * (the LP interface, a speed-up, worker threads against none) is checked by dumping before and after and comparing the
 * files byte for byte. {@code ./gradlew :core-solver:test -Pbench=<label> --tests '*DigestDump*'} (add
 * {@code -PoneThread} for the one-thread solve).
 */
@Tag("bench")
class DigestDump {

    @Test
    void dump() throws Exception {
        final Path out = Path.of("build/digests", System.getProperty("gtnhplanner.bench"));
        Files.createDirectories(out);
        final List<SolverCorpus.Case> cases = new ArrayList<>(SolverCorpus.cases(4, 10));
        cases.addAll(SolverCorpus.upToCases());
        for (final SolverCorpus.Case c : cases) Files.writeString(
            out.resolve(
                c.name()
                    .replace(':', '_')
                    .replace(' ', '_') + ".json"),
            UpToParityTest.normalised(UpToParityTest.digest(Balancer.solveWithAlternatives(c.input())))
                .toString(),
            StandardCharsets.UTF_8);
        System.out.println("[digests] " + cases.size() + " cases to " + out.toAbsolutePath());
        // Three more passes over the corpus: the first after the dump's own, so classes are loaded and compiled.
        final long[] total = new long[3];
        for (int pass = 0; pass < 3; pass++) for (final SolverCorpus.Case c : cases) {
            final long t = System.nanoTime();
            Balancer.solveWithAlternatives(c.input());
            total[pass] += System.nanoTime() - t;
        }
        System.out.printf(
            "[timing] %d cases: pass1 %.1f ms, pass2 %.1f ms, pass3 %.1f ms%n",
            cases.size(),
            total[0] / 1e6,
            total[1] / 1e6,
            total[2] / 1e6);
    }
}
