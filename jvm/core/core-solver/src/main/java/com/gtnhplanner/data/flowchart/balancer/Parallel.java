package com.gtnhplanner.data.flowchart.balancer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import com.gtnhplanner.core.base.Worker;

/**
 * The solver's worker threads, for models that do not depend on each other (the alternatives' swaps, the gate
 * filter's trials, the islands): solved side by side, and their answers always taken in the order the work was listed,
 * so a solve answers exactly as it would one model at a time. Every model is built and solved on its own (ojAlgo keeps
 * nothing between models but its presolver list, which nothing changes while solving), and the {@link SolveContext} is
 * only read while they run.
 *
 * <p>
 * The threads are the host's ({@link #install}): the mod lends a pool of its own, and without one (the web build, the
 * core's own tests run with {@code -PoneThread}) every model is solved in order on the calling thread.
 */
public final class Parallel {

    private Parallel() {}

    private static Worker worker = Worker.INLINE;

    /**
     * Installs the host's worker for the solver's models: one whose jobs may run side by side. A host with fewer than
     * two threads to spare installs nothing ({@link Worker#INLINE} is the default). Installed once at start-up, before
     * the first solve.
     */
    public static void install(final Worker worker) {
        Parallel.worker = Objects.requireNonNull(worker, "worker");
    }

    /**
     * How many independent models a search hands over at once when it looks at its budget between batches: a fixed
     * number, so where a spent budget cuts the search never depends on how many threads this computer has.
     */
    public static int batch() {
        return 16;
    }

    /** {@code work} applied to every item, side by side when there are several; the answers in the items' order. */
    public static <T, R> List<R> map(final List<T> items, final Function<T, R> work) {
        return map(null, items, work);
    }

    /**
     * {@code work} applied to every item, side by side when there are several and nothing is listening (a profiler
     * hears every model, in order, so it gets them one at a time); the answers in the items' order.
     */
    public static <T, R> List<R> map(final SolveContext ctx, final List<T> items, final Function<T, R> work) {
        final Worker w = worker;
        if (items.size() < 2 || w == Worker.INLINE || ctx != null && ctx.profiler.enabled()) {
            final List<R> out = new ArrayList<>(items.size());
            for (final T item : items) out.add(work.apply(item));
            return out;
        }
        final List<Worker.Job<R>> jobs = new ArrayList<>(items.size());
        for (final T item : items) jobs.add(w.start(() -> work.apply(item)));
        // Every job is waited for, even after one fails, so no model outlives the call (a stopped solve's islands
        // touch the cache only before the solve is over); the first failure, in the items' order, is rethrown.
        final List<R> out = new ArrayList<>(items.size());
        RuntimeException failed = null;
        Error broke = null;
        for (final Worker.Job<R> job : jobs) {
            try {
                out.add(job.await());
            } catch (final RuntimeException e) {
                if (failed == null && broke == null) failed = e;
            } catch (final Error e) {
                if (failed == null && broke == null) broke = e;
            }
        }
        if (broke != null) throw broke;
        if (failed != null) throw failed;
        return out;
    }
}
