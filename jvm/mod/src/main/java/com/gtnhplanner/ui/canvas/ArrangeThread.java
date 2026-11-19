package com.gtnhplanner.ui.canvas;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import com.gtnhplanner.core.layout.arrange.Arrange;

/**
 * Arrange's second thread in the game (the core starts none): the free placement searches beside the challenger and
 * the second polish beside the first. One per arrange; stopped, its job interrupted, when the arrange ends.
 */
final class ArrangeThread implements Arrange.Worker {

    private final ExecutorService pool = Executors.newFixedThreadPool(1, r -> {
        final Thread t = new Thread(r, "GTNH Planner arrange");
        t.setDaemon(true);
        return t;
    });

    @Override
    public <T> Arrange.Job<T> start(final Supplier<T> work) {
        final Future<T> job = pool.submit(work::get);
        return () -> {
            try {
                return job.get();
            } catch (final InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
                throw new Arrange.Cancelled();
            } catch (final ExecutionException e) {
                if (e.getCause() instanceof final RuntimeException r) throw r;
                if (e.getCause() instanceof final Error r) throw r;
                throw new IllegalStateException(e.getCause());
            }
        };
    }

    @Override
    public void stop() {
        pool.shutdownNow();
    }
}
