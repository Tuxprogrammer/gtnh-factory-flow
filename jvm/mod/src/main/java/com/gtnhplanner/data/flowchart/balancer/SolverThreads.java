package com.gtnhplanner.data.flowchart.balancer;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.function.Supplier;

import com.gtnhplanner.core.base.Worker;

/**
 * The game's threads for the solver's independent models ({@link Parallel}): all but a couple of the machine's
 * threads, at most 16, since the game draws on one and the solve service waits on another. A job started from one of
 * these threads (a model inside an island) joins the pool's own queue, and waiting for it helps run the queue.
 */
public final class SolverThreads implements Worker {

    private static final int THREADS = Math.max(
        1,
        Math.min(
            16,
            Runtime.getRuntime()
                .availableProcessors() - 2));

    private final ForkJoinPool pool = new ForkJoinPool(THREADS, p -> {
        final ForkJoinWorkerThread t = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(p);
        t.setName("GTNH Planner solver worker " + t.getPoolIndex());
        t.setDaemon(true);
        return t;
    }, null, false);

    private SolverThreads() {}

    /** Gives the solver this machine's threads, or none (it solves in order) when there are fewer than two to spare. */
    public static void install() {
        if (THREADS >= 2) Parallel.install(new SolverThreads());
    }

    @Override
    public <T> Job<T> start(final Supplier<T> work) {
        final ForkJoinTask<T> task = pool.submit(work::get);
        return task::join;
    }
}
