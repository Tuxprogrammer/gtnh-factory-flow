package org.ojalgo.concurrent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Web build's replacement for ojAlgo's daemon pool: every task runs on the calling thread at submit, and its Future is
 * already done. ojAlgo only parallelises above sizes it reads from the hardware, and the solver sets parallelism(1).
 */
public final class DaemonPoolExecutor implements ExecutorService {

    static final DaemonPoolExecutor INSTANCE = new DaemonPoolExecutor();

    public static <T> Future<T> invoke(final Callable<T> task) {
        return INSTANCE.submit(task);
    }

    public static Future<?> invoke(final Runnable task) {
        return INSTANCE.submit(task);
    }

    public static <T> Future<T> invoke(final Runnable task, final T result) {
        return INSTANCE.submit(task, result);
    }

    public static ExecutorService newCachedThreadPool(final String name) {
        return INSTANCE;
    }

    private DaemonPoolExecutor() {}

    @Override
    public void execute(final Runnable command) {
        command.run();
    }

    @Override
    public <T> Future<T> submit(final Callable<T> task) {
        try {
            return new Done<>(task.call(), null);
        } catch (final Exception e) {
            return new Done<>(null, e);
        }
    }

    @Override
    public Future<?> submit(final Runnable task) {
        return submit(task, null);
    }

    @Override
    public <T> Future<T> submit(final Runnable task, final T result) {
        try {
            task.run();
            return new Done<>(result, null);
        } catch (final RuntimeException e) {
            return new Done<>(null, e);
        }
    }

    @Override
    public <T> List<Future<T>> invokeAll(final Collection<? extends Callable<T>> tasks) {
        final List<Future<T>> out = new ArrayList<>();
        for (final Callable<T> t : tasks) out.add(submit(t));
        return out;
    }

    @Override
    public void shutdown() {}

    // The rest of ExecutorService, for javac only: the web classlib's interface has just the calls above.
    @Override
    public List<Runnable> shutdownNow() {
        return List.of();
    }

    @Override
    public boolean isShutdown() {
        return false;
    }

    @Override
    public boolean isTerminated() {
        return false;
    }

    @Override
    public boolean awaitTermination(final long timeout, final TimeUnit unit) {
        return true;
    }

    @Override
    public <T> List<Future<T>> invokeAll(final Collection<? extends Callable<T>> tasks, final long timeout,
        final TimeUnit unit) {
        return invokeAll(tasks);
    }

    @Override
    public <T> T invokeAny(final Collection<? extends Callable<T>> tasks) throws ExecutionException {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> T invokeAny(final Collection<? extends Callable<T>> tasks, final long timeout, final TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    private record Done<T>(T value, Exception failure) implements Future<T> {

        @Override
        public boolean cancel(final boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public T get() throws ExecutionException {
            if (failure != null) throw new ExecutionException(failure);
            return value;
        }

        @Override
        public T get(final long timeout, final TimeUnit unit) throws ExecutionException {
            return get();
        }
    }
}
