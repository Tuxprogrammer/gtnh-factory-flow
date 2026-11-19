package org.teavm.classlib.java.util.concurrent;

import java.util.Collection;
import java.util.concurrent.LinkedBlockingDeque;

/** Single-threaded: a LinkedBlockingDeque used as a queue. */
public class TLinkedTransferQueue<E> extends LinkedBlockingDeque<E> {
    public TLinkedTransferQueue() {}

    public TLinkedTransferQueue(final Collection<? extends E> c) {
        addAll(c);
    }
}
