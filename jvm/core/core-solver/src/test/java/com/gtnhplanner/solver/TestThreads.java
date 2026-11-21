package com.gtnhplanner.solver;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.function.Supplier;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

import com.gtnhplanner.core.base.Worker;
import com.gtnhplanner.data.flowchart.balancer.Parallel;

/**
 * The solver's tests run with worker threads, as the mod solves (its {@code SolverThreads}: the same pool, the same
 * size), unless {@code -PoneThread} asks for the core's own default, every model in order on one thread, as the web
 * build solves. The answers are the same either way; DigestDump is how that is checked. Registered in
 * {@code META-INF/services/org.junit.platform.launcher.LauncherSessionListener}.
 */
public final class TestThreads implements LauncherSessionListener, Worker {

    private static final int THREADS = Math.max(
        1,
        Math.min(
            16,
            Runtime.getRuntime()
                .availableProcessors() - 2));

    private ForkJoinPool pool;

    @Override
    public void launcherSessionOpened(final LauncherSession session) {
        if (Boolean.getBoolean("gtnhplanner.solver.oneThread") || THREADS < 2) return;
        pool = new ForkJoinPool(THREADS, p -> {
            final ForkJoinWorkerThread t = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(p);
            t.setName("solver test worker " + t.getPoolIndex());
            t.setDaemon(true);
            return t;
        }, null, false);
        Parallel.install(this);
    }

    @Override
    public <T> Job<T> start(final Supplier<T> work) {
        final ForkJoinTask<T> task = pool.submit(work::get);
        return task::join;
    }
}
