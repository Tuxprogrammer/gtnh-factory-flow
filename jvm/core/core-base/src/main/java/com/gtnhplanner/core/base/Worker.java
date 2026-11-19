package com.gtnhplanner.core.base;

import java.util.function.Supplier;

/**
 * Threads, as the host lends them: the core starts none. A worker runs a job (a {@link Supplier}) somewhere and hands
 * back a {@link Job} whose {@link Job#await()} is its result. Core code that can use more than one thread (the solver's
 * independent models, Arrange's second search) takes a worker; a host with threads (the mod) gives one backed by them,
 * a host without (the web build) gives {@link #INLINE}. Whatever the worker, the core reads the answers in the order
 * it started the jobs, so the answer never depends on which worker ran them.
 */
public interface Worker {

    /** Starts {@code work}; its result is the job's. */
    <T> Job<T> start(Supplier<T> work);

    /** The caller is done with the worker (finished, failed or cancelled): it may let go of its threads. */
    default void stop() {}

    /** A started job. */
    interface Job<T> {

        /** The job's result, waiting for it if it is still running; its failure rethrown as it was. */
        T await();
    }

    /** No threads: each job runs when it is awaited, on the thread that awaits it. */
    Worker INLINE = new Worker() {

        @Override
        public <T> Job<T> start(final Supplier<T> work) {
            return work::get;
        }
    };
}
