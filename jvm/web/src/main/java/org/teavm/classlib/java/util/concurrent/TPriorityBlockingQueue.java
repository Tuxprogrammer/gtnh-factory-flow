package org.teavm.classlib.java.util.concurrent;

import java.util.Collection;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/** Single-threaded: a PriorityQueue with the blocking calls returning at once. */
public class TPriorityBlockingQueue<E> extends PriorityQueue<E> implements BlockingQueue<E> {
    public TPriorityBlockingQueue() {}

    public TPriorityBlockingQueue(final int initialCapacity) {
        super(initialCapacity);
    }

    public TPriorityBlockingQueue(final int initialCapacity, final Comparator<? super E> comparator) {
        super(initialCapacity, comparator);
    }

    @Override
    public void put(final E e) {
        offer(e);
    }

    @Override
    public boolean offer(final E e, final long timeout, final TimeUnit unit) {
        return offer(e);
    }

    @Override
    public E take() {
        return poll();
    }

    @Override
    public E poll(final long timeout, final TimeUnit unit) {
        return poll();
    }

    @Override
    public int remainingCapacity() {
        return Integer.MAX_VALUE;
    }

    @Override
    public int drainTo(final Collection<? super E> c) {
        return drainTo(c, Integer.MAX_VALUE);
    }

    @Override
    public int drainTo(final Collection<? super E> c, final int max) {
        int n = 0;
        while (n < max && !isEmpty()) {
            c.add(poll());
            n++;
        }
        return n;
    }
}
