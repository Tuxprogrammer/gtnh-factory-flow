package org.teavm.classlib.java.util.concurrent;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

public interface TExecutorService extends Executor {
    <T> Future<T> submit(Callable<T> task);

    Future<?> submit(Runnable task);

    <T> Future<T> submit(Runnable task, T result);

    <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException;

    void shutdown();
}
