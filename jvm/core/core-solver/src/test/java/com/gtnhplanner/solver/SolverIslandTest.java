package com.gtnhplanner.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.flowchart.balancer.Balancer;
import com.gtnhplanner.data.flowchart.balancer.SolutionView;
import com.gtnhplanner.data.flowchart.balancer.SolveInput;
import com.gtnhplanner.data.flowchart.balancer.UpToReadout;

/**
 * Islands answer as if alone: every copy of a plan tiled into one (copies nothing joins) gets exactly the plan's own
 * answer, its machines, its flows, its leftovers and its choices, however many copies sit beside it.
 */
class SolverIslandTest {


    @AfterAll
    static void tearDown() {}

    @Test
    void everyCopyAnswersAsThePlanAlone() {
        for (final String plan : new String[] { "platline", "mega-iv-nitrobenzene-power-plant", "hv-diesel" }) {
            final SolveInput one = SolverCorpus.plan(plan);
            final SolutionView alone = solved(Balancer.solveWithAlternatives(one));
            final int copies = 4;
            final Balancer.Answer tiledAnswer = Balancer.solveWithAlternatives(SolverCorpus.tile(one, copies));
            final SolutionView tiled = solved(tiledAnswer);
            for (int c = 0; c < copies; c++) {
                same(plan, c, alone.machineCounts, tiled.machineCounts);
                same(plan, c, alone.edgeFlowsPerSecond, tiled.edgeFlowsPerSecond);
            }
            assertEquals(copies * alone.openGates, tiled.openGates, plan + ": open gates");
            assertEquals(
                copies * alone.spares()
                    .size(),
                tiled.spares()
                    .size(),
                plan + ": leftovers");
            assertEquals(
                copies * ((Balancer.Answer.Solved) Balancer.solveWithAlternatives(one)).alternatives()
                    .options()
                    .size(),
                ((Balancer.Answer.Solved) tiledAnswer).alternatives()
                    .options()
                    .size(),
                plan + ": choices");
        }
    }

    /**
     * The same with every count Up to (docs/design/up-to.md): each island pushes and reads out its own Up to cards, so
     * every copy reads as the plan alone.
     */
    @Test
    void everyCopyOfAnUpToPlanAnswersAsThePlanAlone() {
        for (final String plan : new String[] { "platline", "hv-diesel", "oxygen-silicon-loop" }) {
            final SolveInput one = SolverCorpus.everyCountUpTo(SolverCorpus.plan(plan));
            final SolutionView alone = solved(Balancer.solveWithAlternatives(one));
            final int copies = 3;
            final SolutionView tiled = solved(Balancer.solveWithAlternatives(SolverCorpus.tile(one, copies)));
            for (int c = 0; c < copies; c++) same(plan, c, alone.machineCounts, tiled.machineCounts);
            assertEquals(
                copies * alone.upTo()
                    .size(),
                tiled.upTo()
                    .size(),
                plan + ": read-outs");
            final Map<Uuid, UpToReadout> byNode = new java.util.HashMap<>();
            for (final UpToReadout r : tiled.upTo()) byNode.put(r.node(), r);
            for (final UpToReadout r : alone.upTo()) {
                for (int c = 0; c < copies; c++) {
                    final Uuid node = c == 0 ? r.node()
                        : Uuid.nameUUIDFromBytes((c + ":" + r.node()).getBytes(StandardCharsets.UTF_8));
                    final UpToReadout copy = byNode.get(node);
                    assertTrue(
                        copy != null && copy.reason() == r.reason(),
                        plan + " copy " + c + ": " + r + " / " + copy);
                    assertEquals(r.reached(), copy.reached(), 1e-9 * Math.max(1, r.reached()), plan + " copy " + c);
                }
            }
        }
    }

    /**
     * The island cache never answers for an island that changed: after one copy's pin moves, a solve through the
     * cache answers as a solve without it.
     */
    @Test
    void theCacheOnlyKeepsWhatDidNotChange() {
        final SolveInput tiled = SolverCorpus.tile(SolverCorpus.plan("platline"), 3);
        final Balancer.Cache cache = new Balancer.Cache();
        Balancer.solveWithAlternatives(tiled, cache, () -> false);
        final java.util.List<SolveInput.Machine> machines = new java.util.ArrayList<>(tiled.machines());
        int edited = -1;
        for (int m = machines.size() - 1; m >= 0 && edited < 0; m--) if (machines.get(m)
            .countFixed()) edited = m;
        final SolveInput.Machine pinned = machines.get(edited);
        machines.set(
            edited,
            new SolveInput.Machine(
                pinned.id(),
                pinned.name(),
                pinned.durationTicks(),
                pinned.energyPerTick(),
                pinned.throughputFactor(),
                pinned.inputs(),
                pinned.outputs(),
                true,
                pinned.machineCount() + 3,
                pinned.targetOutputRates(),
                pinned.properties(),
                pinned.targetInputRates()));
        final SolveInput edit = new SolveInput(
            tiled.mode(),
            tiled.choice(),
            machines,
            tiled.edges(),
            tiled.pools(),
            tiled.drawers(),
            tiled.extentPins(),
            tiled.spares());
        final SolutionView cached = solved(Balancer.solveWithAlternatives(edit, cache, () -> false));
        final SolutionView fresh = solved(Balancer.solveWithAlternatives(edit));
        assertEquals(fresh.machineCounts, cached.machineCounts);
        assertEquals(fresh.edgeFlowsPerSecond, cached.edgeFlowsPerSecond);
    }

    /**
     * A cache with a capacity (the website's solve Worker keeps 64 islands, as its own engine's cache does) still has a
     * state three solves back; the default keeps the last two solves only. A one-island plan's answer read back from
     * the cache is the very answer it kept.
     */
    @Test
    void aCacheWithACapacityKeepsOlderStates() {
        final SolveInput plan = SolverCorpus.plan("single-card-v2");
        final SolveInput[] states = { plan, withCount(plan, 1), withCount(plan, 2), plan };
        final Balancer.Answer[] lru = new Balancer.Answer[states.length], two = new Balancer.Answer[states.length];
        final Balancer.Cache withCapacity = new Balancer.Cache(64), lastTwo = new Balancer.Cache();
        for (int i = 0; i < states.length; i++) {
            lru[i] = Balancer.solveWithAlternatives(states[i], withCapacity, () -> false);
            two[i] = Balancer.solveWithAlternatives(states[i], lastTwo, () -> false);
        }
        assertSame(lru[0], lru[3]);
        assertNotSame(two[0], two[3]);
        assertEquals(solved(two[3]).machineCounts, solved(lru[3]).machineCounts);
    }

    /** The plan with its first pinned machine's count raised by {@code more}. */
    private static SolveInput withCount(final SolveInput in, final int more) {
        final java.util.List<SolveInput.Machine> machines = new java.util.ArrayList<>(in.machines());
        for (int m = 0; m < machines.size(); m++) {
            final SolveInput.Machine x = machines.get(m);
            if (!x.countFixed()) continue;
            machines.set(
                m,
                new SolveInput.Machine(
                    x.id(),
                    x.name(),
                    x.durationTicks(),
                    x.energyPerTick(),
                    x.throughputFactor(),
                    x.inputs(),
                    x.outputs(),
                    true,
                    x.machineCount() + more,
                    x.targetOutputRates(),
                    x.properties(),
                    x.targetInputRates()));
            break;
        }
        return new SolveInput(
            in.mode(),
            in.choice(),
            machines,
            in.edges(),
            in.pools(),
            in.drawers(),
            in.extentPins(),
            in.spares());
    }

    private static SolutionView solved(final Balancer.Answer answer) {
        return assertInstanceOf(Balancer.Answer.Solved.class, answer).solution();
    }

    private static void same(final String plan, final int copy, final Map<Uuid, Double> alone,
        final Map<Uuid, Double> tiled) {
        for (final Map.Entry<Uuid, Double> e : alone.entrySet()) {
            final Uuid id = copy == 0 ? e.getKey()
                : Uuid.nameUUIDFromBytes((copy + ":" + e.getKey()).getBytes(StandardCharsets.UTF_8));
            final Double got = tiled.get(id);
            assertTrue(
                got != null && Math.abs(got - e.getValue()) <= 1e-9 * Math.max(1, Math.abs(e.getValue())),
                plan + " copy " + copy + ": " + e.getKey() + " " + e.getValue() + " alone, " + got + " tiled");
        }
    }
}
