package com.gtnhplanner.core.base;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;

/**
 * A hash set that iterates in exactly the order {@code java.util.HashSet} of JDK 19 and later does, on the JVM and on
 * both web targets: a {@link JavaHashMap} of its elements, sized as the JDK sizes one (a set made from a collection
 * holds the collection, or 12 elements, before it grows). Why the core needs it: {@link JavaHashMap}.
 */
public class JavaHashSet<E> extends AbstractSet<E> implements Cloneable {

    private static final Object PRESENT = new Object();

    private JavaHashMap<E, Object> map;

    public JavaHashSet() {
        map = new JavaHashMap<>();
    }

    @SuppressWarnings("this-escape")
    public JavaHashSet(final Collection<? extends E> c) {
        map = JavaHashMap.newHashMap(Math.max(c.size(), 12));
        addAll(c);
    }

    public JavaHashSet(final int initialCapacity, final float loadFactor) {
        map = new JavaHashMap<>(initialCapacity, loadFactor);
    }

    public JavaHashSet(final int initialCapacity) {
        map = new JavaHashMap<>(initialCapacity);
    }

    /** A set sized for {@code elements} elements without growing: {@code HashSet.newHashSet}. */
    public static <E> JavaHashSet<E> newHashSet(final int elements) {
        if (elements < 0) throw new IllegalArgumentException("Negative number of elements: " + elements);
        return new JavaHashSet<>(JavaHashMap.capacityFor(elements));
    }

    @Override
    public Iterator<E> iterator() {
        return map.keySet()
            .iterator();
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public boolean isEmpty() {
        return map.isEmpty();
    }

    @Override
    public boolean contains(final Object o) {
        return map.containsKey(o);
    }

    @Override
    public boolean add(final E e) {
        return map.put(e, PRESENT) == null;
    }

    @Override
    public boolean remove(final Object o) {
        return map.remove(o) == PRESENT;
    }

    @Override
    public void clear() {
        map.clear();
    }

    /** A shallow copy, its map copied as JavaHashMap.clone copies one. */
    @Override
    @SuppressWarnings("unchecked")
    public Object clone() {
        final JavaHashSet<E> copy;
        try {
            copy = (JavaHashSet<E>) super.clone();
        } catch (final CloneNotSupportedException e) {
            throw new IllegalStateException(e);
        }
        copy.map = (JavaHashMap<E, Object>) map.clone();
        return copy;
    }
}
