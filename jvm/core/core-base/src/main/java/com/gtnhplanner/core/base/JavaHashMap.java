package com.gtnhplanner.core.base;

import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A hash map that iterates in exactly the order {@code java.util.HashMap} of JDK 19 and later does, on the JVM and on
 * both web targets. The web compile's own HashMap places keys differently (no mixing of the hash's high bits, new keys
 * at the head of a bucket, buckets reversed on growth), so code that iterates a HashMap visits its entries in another
 * order there: the solver's LP rows and the terms of a row came out in another order, and ten corpus answers moved in
 * the last bits. Java 8 to 18 also size a copied map differently from JDK 19 and later. Every core map whose iteration
 * order can reach an answer is this one (or {@link JavaHashSet}), so the order is the same everywhere: the JDK's own,
 * so answers on the JVM do not move.
 *
 * <p>
 * The layout rules reproduced, and checked against java.util.HashMap by JavaHashMapTest: the hash is the key's
 * {@code hashCode()} with its high half folded into the low half ({@code h ^ h >>> 16}, null is 0); the table is a power
 * of two, allocated on first use (16, or the next power of two at or above the capacity asked for), and doubles when
 * the size passes three quarters of it, each bucket splitting into a low and a high bucket in the order it had; a new
 * key goes at the end of its bucket's chain from put and putAll, at the head from computeIfAbsent, compute and merge
 * (which grow the table before they look, not after they add); a chain reaching nine keys doubles a table shorter than
 * 64, and otherwise becomes a red-black tree ordered by hash, then by {@code compareTo} for keys of the same Comparable
 * class, whose iteration order is the chain kept beside the tree: a new key goes right after its tree parent, and the
 * tree's root is moved to the front after every insertion and every removal (except one made through an iterator); a
 * tree bucket that shrinks to six keys on a split, or whose tree becomes too shallow on a removal, is a chain again.
 * Copies (the copy constructor, putAll into an empty map, clone) size the table to hold the source at three quarters
 * full, rounded up.
 *
 * <p>
 * Where the JDK's own order depends on identity hash codes (keys of the same hash that do not compare, or compare equal
 * while not being equal), it differs from run to run on the JVM; this map then puts the new key first, which is one of
 * the JDK's possible orders. Keys whose {@code hashCode()} is the identity hash (enums, plain objects) iterate in a
 * different order on every run and every target whatever the map: no map can make that order agree.
 */
public class JavaHashMap<K, V> extends AbstractMap<K, V> implements Cloneable {

    static final int MAXIMUM_CAPACITY = 1 << 30;
    static final int DEFAULT_CAPACITY = 16;
    static final float DEFAULT_LOAD_FACTOR = 0.75f;
    /** A chain that reaches this many keys plus one becomes a tree (or doubles a small table). */
    static final int TREEIFY_THRESHOLD = 8;
    /** A tree bucket left with this many keys or fewer on a split is a chain again. */
    static final int UNTREEIFY_THRESHOLD = 6;
    /** Below this table length a long chain doubles the table instead of becoming a tree. */
    static final int MIN_TREEIFY_CAPACITY = 64;

    Node<K, V>[] table;
    int size;
    int modCount;
    /** The size past which the table doubles; before the first allocation, the length to allocate (0: the default). */
    int threshold;
    final float loadFactor;

    private Set<K> keys;
    private Collection<V> vals;
    private Set<Map.Entry<K, V>> entries;

    public JavaHashMap() {
        loadFactor = DEFAULT_LOAD_FACTOR;
    }

    public JavaHashMap(final int initialCapacity) {
        this(initialCapacity, DEFAULT_LOAD_FACTOR);
    }

    public JavaHashMap(int initialCapacity, final float loadFactor) {
        if (initialCapacity < 0) throw new IllegalArgumentException("Illegal initial capacity: " + initialCapacity);
        if (initialCapacity > MAXIMUM_CAPACITY) initialCapacity = MAXIMUM_CAPACITY;
        if (!(loadFactor > 0)) throw new IllegalArgumentException("Illegal load factor");
        this.loadFactor = loadFactor;
        this.threshold = tableSizeFor(initialCapacity);
    }

    public JavaHashMap(final Map<? extends K, ? extends V> m) {
        loadFactor = DEFAULT_LOAD_FACTOR;
        putMapEntries(m);
    }

    /** A map sized for {@code mappings} keys without growing: {@code HashMap.newHashMap}. */
    public static <K, V> JavaHashMap<K, V> newHashMap(final int mappings) {
        if (mappings < 0) throw new IllegalArgumentException("Negative number of mappings: " + mappings);
        return new JavaHashMap<>(capacityFor(mappings));
    }

    /** The capacity that holds {@code mappings} keys at the default load factor. */
    static int capacityFor(final int mappings) {
        return (int) Math.ceil(mappings / (double) DEFAULT_LOAD_FACTOR);
    }

    /** The bucket hash: the key's hash code with its high half folded into the low half. */
    static int spread(final Object key) {
        if (key == null) return 0;
        final int h = key.hashCode();
        return h ^ (h >>> 16);
    }

    /** The smallest power of two at or above {@code capacity} (1 for 0), at most {@link #MAXIMUM_CAPACITY}. */
    static int tableSizeFor(final int capacity) {
        final int n = -1 >>> Integer.numberOfLeadingZeros(capacity - 1);
        return n < 0 ? 1 : n >= MAXIMUM_CAPACITY ? MAXIMUM_CAPACITY : n + 1;
    }

    /** The probe key's equals, as the JDK calls it: {@code key.equals(stored)}. */
    static boolean same(final Object key, final Object stored) {
        return stored == key || (key != null && key.equals(stored));
    }

    // ------------------------------------------------------------------------------------------------------ entries

    static class Node<K, V> implements Map.Entry<K, V> {

        final int hash;
        final K key;
        V value;
        Node<K, V> next;

        Node(final int hash, final K key, final V value, final Node<K, V> next) {
            this.hash = hash;
            this.key = key;
            this.value = value;
            this.next = next;
        }

        @Override
        public final K getKey() {
            return key;
        }

        @Override
        public final V getValue() {
            return value;
        }

        @Override
        public final V setValue(final V newValue) {
            final V old = value;
            value = newValue;
            return old;
        }

        @Override
        public final boolean equals(final Object o) {
            if (o == this) return true;
            return o instanceof Map.Entry<?, ?> e && Objects.equals(key, e.getKey()) && Objects.equals(value, e.getValue());
        }

        @Override
        public final int hashCode() {
            return Objects.hashCode(key) ^ Objects.hashCode(value);
        }

        @Override
        public final String toString() {
            return key + "=" + value;
        }
    }

    /** A node of a tree bucket: red-black links, plus {@code prev} beside {@code next} for the bucket's chain. */
    static final class TreeNode<K, V> extends Node<K, V> {

        TreeNode<K, V> parent;
        TreeNode<K, V> left;
        TreeNode<K, V> right;
        TreeNode<K, V> prev;
        boolean red;

        TreeNode(final int hash, final K key, final V value, final Node<K, V> next) {
            super(hash, key, value, next);
        }

        TreeNode<K, V> root() {
            TreeNode<K, V> r = this;
            while (r.parent != null) r = r.parent;
            return r;
        }
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Node<K, V>[] newTable(final int length) {
        return (Node<K, V>[]) new Node[length];
    }

    // -------------------------------------------------------------------------------------------------------- reads

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    /** The node for {@code key}, or null. A tree bucket keeps every key on its chain too, so one walk finds it. */
    final Node<K, V> node(final Object key) {
        final Node<K, V>[] tab = table;
        if (tab == null || tab.length == 0) return null;
        final int h = spread(key);
        for (Node<K, V> e = tab[(tab.length - 1) & h]; e != null; e = e.next) {
            if (e.hash == h && same(key, e.key)) return e;
        }
        return null;
    }

    @Override
    public V get(final Object key) {
        final Node<K, V> e = node(key);
        return e == null ? null : e.value;
    }

    @Override
    public V getOrDefault(final Object key, final V defaultValue) {
        final Node<K, V> e = node(key);
        return e == null ? defaultValue : e.value;
    }

    @Override
    public boolean containsKey(final Object key) {
        return node(key) != null;
    }

    @Override
    public boolean containsValue(final Object value) {
        final Node<K, V>[] tab = table;
        if (tab == null || size == 0) return false;
        for (Node<K, V> e : tab) {
            for (; e != null; e = e.next) {
                if (e.value == value || (value != null && value.equals(e.value))) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------------- writes

    @Override
    public V put(final K key, final V value) {
        return putVal(spread(key), key, value, false);
    }

    @Override
    public V putIfAbsent(final K key, final V value) {
        return putVal(spread(key), key, value, true);
    }

    final V putVal(final int h, final K key, final V value, final boolean onlyIfAbsent) {
        Node<K, V>[] tab = table;
        if (tab == null || tab.length == 0) tab = resize();
        final int i = (tab.length - 1) & h;
        Node<K, V> p = tab[i];
        Node<K, V> found = null;
        if (p == null) {
            tab[i] = new Node<>(h, key, value, null);
        } else if (p.hash == h && same(key, p.key)) {
            found = p;
        } else if (p instanceof TreeNode<K, V> first) {
            found = putTree(tab, first, h, key, value);
        } else {
            for (int count = 0;; ++count) {
                final Node<K, V> e = p.next;
                if (e == null) {
                    p.next = new Node<>(h, key, value, null);
                    if (count >= TREEIFY_THRESHOLD - 1) treeifyBin(tab, h);
                    break;
                }
                if (e.hash == h && same(key, e.key)) {
                    found = e;
                    break;
                }
                p = e;
            }
        }
        if (found != null) {
            final V old = found.value;
            if (!onlyIfAbsent || old == null) found.value = value;
            return old;
        }
        ++modCount;
        if (++size > threshold) resize();
        return null;
    }

    final void putMapEntries(final Map<? extends K, ? extends V> m) {
        final int s = m.size();
        if (s <= 0) return;
        if (table == null) {
            final double wanted = Math.ceil(s / (double) loadFactor);
            final int t = wanted < MAXIMUM_CAPACITY ? (int) wanted : MAXIMUM_CAPACITY;
            if (t > threshold) threshold = tableSizeFor(t);
        } else {
            while (s > threshold && table.length < MAXIMUM_CAPACITY) resize();
        }
        for (final Map.Entry<? extends K, ? extends V> e : m.entrySet()) {
            final K key = e.getKey();
            putVal(spread(key), key, e.getValue(), false);
        }
    }

    @Override
    public void putAll(final Map<? extends K, ? extends V> m) {
        putMapEntries(m);
    }

    @Override
    public V remove(final Object key) {
        final Node<K, V> e = removeNode(spread(key), key, null, false, true);
        return e == null ? null : e.value;
    }

    @Override
    public boolean remove(final Object key, final Object value) {
        return removeNode(spread(key), key, value, true, true) != null;
    }

    /**
     * Removes the node for {@code key} (when {@code matchValue}, only if it holds {@code value}). {@code movable} false
     * (a removal through an iterator) leaves a tree bucket's root where it is and keeps the bucket a tree.
     */
    final Node<K, V> removeNode(final int h, final Object key, final Object value, final boolean matchValue,
        final boolean movable) {
        final Node<K, V>[] tab = table;
        if (tab == null || tab.length == 0) return null;
        final int index = (tab.length - 1) & h;
        Node<K, V> p = tab[index];
        if (p == null) return null;
        Node<K, V> node = null;
        if (p.hash == h && same(key, p.key)) {
            node = p;
        } else {
            for (Node<K, V> e = p.next; e != null; e = e.next) {
                if (e.hash == h && same(key, e.key)) {
                    node = e;
                    break;
                }
                p = e;
            }
        }
        if (node == null) return null;
        if (matchValue) {
            final V v = node.value;
            if (v != value && (value == null || !value.equals(v))) return null;
        }
        if (node instanceof TreeNode<K, V> t) removeTree(tab, t, movable);
        else if (node == p) tab[index] = node.next;
        else p.next = node.next;
        ++modCount;
        --size;
        return node;
    }

    @Override
    public void clear() {
        modCount++;
        final Node<K, V>[] tab = table;
        if (tab != null && size > 0) {
            size = 0;
            for (int i = 0; i < tab.length; ++i) tab[i] = null;
        }
    }

    @Override
    public boolean replace(final K key, final V oldValue, final V newValue) {
        final Node<K, V> e = node(key);
        if (e != null && (e.value == oldValue || (e.value != null && e.value.equals(oldValue)))) {
            e.value = newValue;
            return true;
        }
        return false;
    }

    @Override
    public V replace(final K key, final V value) {
        final Node<K, V> e = node(key);
        if (e == null) return null;
        final V old = e.value;
        e.value = value;
        return old;
    }

    /** The bucket {@code key} goes in, after growing the table first as computeIfAbsent, compute and merge do. */
    private Node<K, V>[] tableBeforeCompute() {
        final Node<K, V>[] tab = table;
        return size > threshold || tab == null || tab.length == 0 ? resize() : tab;
    }

    /** The chain length of a plain bucket that does not hold the key (0 for a tree bucket or a found key). */
    private static int chainLength(final Node<?, ?> first) {
        int n = 0;
        for (Node<?, ?> e = first; e != null; e = e.next) ++n;
        return n;
    }

    /** Adds a key that is not in the map, at the head of a plain bucket (or into a tree), as the compute family does. */
    private void addAtHead(final Node<K, V>[] tab, final int i, final Node<K, V> first, final int h, final K key,
        final V value) {
        if (first instanceof TreeNode<K, V> t) {
            putTree(tab, t, h, key, value);
        } else {
            tab[i] = new Node<>(h, key, value, first);
            if (chainLength(first) >= TREEIFY_THRESHOLD - 1) treeifyBin(tab, h);
        }
    }

    private static <K, V> Node<K, V> find(final Node<K, V> first, final int h, final Object key) {
        for (Node<K, V> e = first; e != null; e = e.next) if (e.hash == h && same(key, e.key)) return e;
        return null;
    }

    @Override
    public V computeIfAbsent(final K key, final Function<? super K, ? extends V> mapping) {
        if (mapping == null) throw new NullPointerException();
        final int h = spread(key);
        final Node<K, V>[] tab = tableBeforeCompute();
        final int i = (tab.length - 1) & h;
        final Node<K, V> first = tab[i];
        final Node<K, V> old = find(first, h, key);
        if (old != null && old.value != null) return old.value;
        final int mc = modCount;
        final V v = mapping.apply(key);
        if (mc != modCount) throw new ConcurrentModificationException();
        if (v == null) return null;
        if (old != null) {
            old.value = v;
            return v;
        }
        addAtHead(tab, i, first, h, key, v);
        modCount = mc + 1;
        ++size;
        return v;
    }

    @Override
    public V computeIfPresent(final K key, final BiFunction<? super K, ? super V, ? extends V> remapping) {
        if (remapping == null) throw new NullPointerException();
        final Node<K, V> e = node(key);
        if (e == null || e.value == null) return null;
        final int mc = modCount;
        final V v = remapping.apply(key, e.value);
        if (mc != modCount) throw new ConcurrentModificationException();
        if (v != null) {
            e.value = v;
            return v;
        }
        removeNode(spread(key), key, null, false, true);
        return null;
    }

    @Override
    public V compute(final K key, final BiFunction<? super K, ? super V, ? extends V> remapping) {
        if (remapping == null) throw new NullPointerException();
        final int h = spread(key);
        final Node<K, V>[] tab = tableBeforeCompute();
        final int i = (tab.length - 1) & h;
        final Node<K, V> first = tab[i];
        final Node<K, V> old = find(first, h, key);
        final int mc = modCount;
        final V v = remapping.apply(key, old == null ? null : old.value);
        if (mc != modCount) throw new ConcurrentModificationException();
        if (old != null) {
            if (v != null) old.value = v;
            else removeNode(h, key, null, false, true);
        } else if (v != null) {
            addAtHead(tab, i, first, h, key, v);
            modCount = mc + 1;
            ++size;
        }
        return v;
    }

    @Override
    public V merge(final K key, final V value, final BiFunction<? super V, ? super V, ? extends V> remapping) {
        if (value == null || remapping == null) throw new NullPointerException();
        final int h = spread(key);
        final Node<K, V>[] tab = tableBeforeCompute();
        final int i = (tab.length - 1) & h;
        final Node<K, V> first = tab[i];
        final Node<K, V> old = find(first, h, key);
        if (old != null) {
            final V v;
            if (old.value != null) {
                final int mc = modCount;
                v = remapping.apply(old.value, value);
                if (mc != modCount) throw new ConcurrentModificationException();
            } else {
                v = value;
            }
            if (v != null) old.value = v;
            else removeNode(h, key, null, false, true);
            return v;
        }
        addAtHead(tab, i, first, h, key, value);
        ++modCount;
        ++size;
        return value;
    }

    @Override
    public void forEach(final BiConsumer<? super K, ? super V> action) {
        if (action == null) throw new NullPointerException();
        final Node<K, V>[] tab = table;
        if (size == 0 || tab == null) return;
        final int mc = modCount;
        for (Node<K, V> e : tab) for (; e != null; e = e.next) action.accept(e.key, e.value);
        if (modCount != mc) throw new ConcurrentModificationException();
    }

    @Override
    public void replaceAll(final BiFunction<? super K, ? super V, ? extends V> function) {
        if (function == null) throw new NullPointerException();
        final Node<K, V>[] tab = table;
        if (size == 0 || tab == null) return;
        final int mc = modCount;
        for (Node<K, V> e : tab) for (; e != null; e = e.next) e.value = function.apply(e.key, e.value);
        if (modCount != mc) throw new ConcurrentModificationException();
    }

    /** A shallow copy, sized as the copy constructor sizes it (so it may iterate in another order than this map). */
    @Override
    @SuppressWarnings("unchecked")
    public Object clone() {
        final JavaHashMap<K, V> copy;
        try {
            copy = (JavaHashMap<K, V>) super.clone();
        } catch (final CloneNotSupportedException e) {
            throw new IllegalStateException(e);
        }
        copy.table = null;
        copy.keys = null;
        copy.vals = null;
        copy.entries = null;
        copy.modCount = 0;
        copy.threshold = 0;
        copy.size = 0;
        copy.putMapEntries(this);
        return copy;
    }

    // ------------------------------------------------------------------------------------------------------- growth

    /** Allocates the table (the length held in threshold, or 16) or doubles it, keeping each bucket's order. */
    final Node<K, V>[] resize() {
        final Node<K, V>[] old = table;
        final int oldCap = old == null ? 0 : old.length;
        final int oldThr = threshold;
        int newCap;
        int newThr = 0;
        if (oldCap > 0) {
            if (oldCap >= MAXIMUM_CAPACITY) {
                threshold = Integer.MAX_VALUE;
                return old;
            }
            newCap = oldCap << 1;
            if (newCap < MAXIMUM_CAPACITY && oldCap >= DEFAULT_CAPACITY) newThr = oldThr << 1;
        } else if (oldThr > 0) {
            newCap = oldThr;
        } else {
            newCap = DEFAULT_CAPACITY;
            newThr = (int) (DEFAULT_LOAD_FACTOR * DEFAULT_CAPACITY);
        }
        if (newThr == 0) {
            final float limit = Float32Ops.mul(Float32Ops.of(newCap), loadFactor);
            newThr = newCap < MAXIMUM_CAPACITY && limit < (float) MAXIMUM_CAPACITY ? (int) limit : Integer.MAX_VALUE;
        }
        threshold = newThr;
        final Node<K, V>[] tab = newTable(newCap);
        table = tab;
        if (old == null) return tab;
        for (int j = 0; j < oldCap; ++j) {
            Node<K, V> e = old[j];
            if (e == null) continue;
            old[j] = null;
            if (e.next == null) {
                tab[e.hash & (newCap - 1)] = e;
            } else if (e instanceof TreeNode<K, V> t) {
                splitTree(tab, t, j, oldCap);
            } else {
                Node<K, V> loHead = null, loTail = null, hiHead = null, hiTail = null;
                for (Node<K, V> next; e != null; e = next) {
                    next = e.next;
                    if ((e.hash & oldCap) == 0) {
                        if (loTail == null) loHead = e;
                        else loTail.next = e;
                        loTail = e;
                    } else {
                        if (hiTail == null) hiHead = e;
                        else hiTail.next = e;
                        hiTail = e;
                    }
                }
                if (loTail != null) {
                    loTail.next = null;
                    tab[j] = loHead;
                }
                if (hiTail != null) {
                    hiTail.next = null;
                    tab[j + oldCap] = hiHead;
                }
            }
        }
        return tab;
    }

    /** A chain grew past the limit: double a small table, or make the bucket a tree. */
    final void treeifyBin(final Node<K, V>[] tab, final int h) {
        if (tab == null || tab.length < MIN_TREEIFY_CAPACITY) {
            resize();
            return;
        }
        final int index = (tab.length - 1) & h;
        TreeNode<K, V> head = null, tail = null;
        for (Node<K, V> e = tab[index]; e != null; e = e.next) {
            final TreeNode<K, V> t = new TreeNode<>(e.hash, e.key, e.value, null);
            if (tail == null) head = t;
            else {
                t.prev = tail;
                tail.next = t;
            }
            tail = t;
        }
        tab[index] = head;
        if (head != null) treeify(tab, head);
    }

    // ---------------------------------------------------------------------------------------------------- tree bins

    /** Which side of {@code p} a key goes: by hash, then by compareTo within one Comparable class, then a tie rule. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    static int direction(final int h, final Object key, final TreeNode<?, ?> p) {
        if (p.hash > h) return -1;
        if (p.hash < h) return 1;
        final Object other = p.key;
        if (key instanceof Comparable && other != null && other.getClass() == key.getClass()) {
            final int d = ((Comparable) key).compareTo(other);
            if (d != 0) return d;
        }
        return tieBreak(key, other);
    }

    /**
     * Keys of one hash that do not compare: the JDK orders them by class name, then by identity hash code (null first),
     * which is not the same from one run to the next; here the new key goes first.
     */
    static int tieBreak(final Object a, final Object b) {
        if (a == null) return -1;
        if (b == null) return 1;
        final int d = a.getClass()
            .getName()
            .compareTo(
                b.getClass()
                    .getName());
        return d != 0 ? d : -1;
    }

    /** Builds the tree of a bucket's chain, in chain order, and moves its root to the front. */
    static <K, V> void treeify(final Node<K, V>[] tab, final TreeNode<K, V> head) {
        TreeNode<K, V> root = null;
        for (TreeNode<K, V> x = head, next; x != null; x = next) {
            next = (TreeNode<K, V>) x.next;
            x.left = x.right = null;
            if (root == null) {
                x.parent = null;
                x.red = false;
                root = x;
                continue;
            }
            for (TreeNode<K, V> p = root;;) {
                final int dir = direction(x.hash, x.key, p);
                final TreeNode<K, V> child = dir <= 0 ? p.left : p.right;
                if (child == null) {
                    x.parent = p;
                    if (dir <= 0) p.left = x;
                    else p.right = x;
                    root = fixAfterInsert(root, x);
                    break;
                }
                p = child;
            }
        }
        moveRootToFront(tab, root);
    }

    /** A chain of plain nodes for a tree bucket's chain, in its order. */
    static <K, V> Node<K, V> untreeify(final Node<K, V> first) {
        Node<K, V> head = null, tail = null;
        for (Node<K, V> q = first; q != null; q = q.next) {
            final Node<K, V> p = new Node<>(q.hash, q.key, q.value, null);
            if (tail == null) head = p;
            else tail.next = p;
            tail = p;
        }
        return head;
    }

    /** put into a tree bucket: the node already holding the key, or null after adding it. */
    final Node<K, V> putTree(final Node<K, V>[] tab, final TreeNode<K, V> first, final int h, final K key,
        final V value) {
        final Node<K, V> existing = find(first, h, key);
        if (existing != null) return existing;
        final TreeNode<K, V> root = first.parent != null ? first.root() : first;
        for (TreeNode<K, V> p = root;;) {
            final int dir = direction(h, key, p);
            final TreeNode<K, V> child = dir <= 0 ? p.left : p.right;
            if (child == null) {
                final Node<K, V> after = p.next;
                final TreeNode<K, V> x = new TreeNode<>(h, key, value, after);
                if (dir <= 0) p.left = x;
                else p.right = x;
                p.next = x;
                x.parent = p;
                x.prev = p;
                if (after != null) ((TreeNode<K, V>) after).prev = x;
                moveRootToFront(tab, fixAfterInsert(root, x));
                return null;
            }
            p = child;
        }
    }

    /** Makes {@code root} the first node of its bucket's chain. */
    static <K, V> void moveRootToFront(final Node<K, V>[] tab, final TreeNode<K, V> root) {
        if (root == null || tab == null || tab.length == 0) return;
        final int index = (tab.length - 1) & root.hash;
        final TreeNode<K, V> first = (TreeNode<K, V>) tab[index];
        if (root == first) return;
        tab[index] = root;
        final TreeNode<K, V> before = root.prev;
        final Node<K, V> after = root.next;
        if (after != null) ((TreeNode<K, V>) after).prev = before;
        if (before != null) before.next = after;
        if (first != null) first.prev = root;
        root.next = first;
        root.prev = null;
    }

    /**
     * Removes {@code node} from its tree bucket: off the chain first; then, if the tree is too shallow (when
     * {@code movable}), the bucket becomes a plain chain; otherwise the node leaves the tree (an inner node trades
     * places with its successor), the tree is rebalanced and, when {@code movable}, its root moved to the front.
     */
    final void removeTree(final Node<K, V>[] tab, final TreeNode<K, V> node, final boolean movable) {
        if (tab == null || tab.length == 0) return;
        final int index = (tab.length - 1) & node.hash;
        TreeNode<K, V> first = (TreeNode<K, V>) tab[index];
        TreeNode<K, V> root = first;
        final TreeNode<K, V> succ = (TreeNode<K, V>) node.next;
        final TreeNode<K, V> pred = node.prev;
        if (pred == null) tab[index] = first = succ;
        else pred.next = succ;
        if (succ != null) succ.prev = pred;
        if (first == null) return;
        if (root.parent != null) root = root.root();
        if (movable && (root.right == null || root.left == null || root.left.left == null)) {
            tab[index] = untreeify(first);
            return;
        }
        final TreeNode<K, V> p = node, pl = p.left, pr = p.right;
        final TreeNode<K, V> replacement;
        if (pl != null && pr != null) {
            TreeNode<K, V> s = pr;
            while (s.left != null) s = s.left;
            final boolean color = s.red;
            s.red = p.red;
            p.red = color;
            final TreeNode<K, V> sr = s.right;
            final TreeNode<K, V> pp = p.parent;
            if (s == pr) {
                p.parent = s;
                s.right = p;
            } else {
                final TreeNode<K, V> sp = s.parent;
                p.parent = sp;
                if (sp != null) {
                    if (s == sp.left) sp.left = p;
                    else sp.right = p;
                }
                s.right = pr;
                pr.parent = s;
            }
            p.left = null;
            p.right = sr;
            if (sr != null) sr.parent = p;
            s.left = pl;
            pl.parent = s;
            s.parent = pp;
            if (pp == null) root = s;
            else if (p == pp.left) pp.left = s;
            else pp.right = s;
            replacement = sr != null ? sr : p;
        } else if (pl != null) {
            replacement = pl;
        } else if (pr != null) {
            replacement = pr;
        } else {
            replacement = p;
        }
        if (replacement != p) {
            final TreeNode<K, V> pp = p.parent;
            replacement.parent = pp;
            if (pp == null) {
                root = replacement;
                replacement.red = false;
            } else if (p == pp.left) pp.left = replacement;
            else pp.right = replacement;
            p.left = p.right = p.parent = null;
        }
        final TreeNode<K, V> r = p.red ? root : fixAfterDelete(root, replacement);
        if (replacement == p) {
            final TreeNode<K, V> pp = p.parent;
            p.parent = null;
            if (pp != null) {
                if (p == pp.left) pp.left = null;
                else if (p == pp.right) pp.right = null;
            }
        }
        if (movable) moveRootToFront(tab, r);
    }

    /** Splits a tree bucket on a doubling into its low and high buckets, chain order kept; small halves become chains. */
    static <K, V> void splitTree(final Node<K, V>[] tab, final TreeNode<K, V> bucket, final int index, final int bit) {
        TreeNode<K, V> loHead = null, loTail = null, hiHead = null, hiTail = null;
        int lo = 0, hi = 0;
        for (TreeNode<K, V> e = bucket, next; e != null; e = next) {
            next = (TreeNode<K, V>) e.next;
            e.next = null;
            if ((e.hash & bit) == 0) {
                e.prev = loTail;
                if (loTail == null) loHead = e;
                else loTail.next = e;
                loTail = e;
                ++lo;
            } else {
                e.prev = hiTail;
                if (hiTail == null) hiHead = e;
                else hiTail.next = e;
                hiTail = e;
                ++hi;
            }
        }
        if (loHead != null) {
            if (lo <= UNTREEIFY_THRESHOLD) tab[index] = untreeify(loHead);
            else {
                tab[index] = loHead;
                if (hiHead != null) treeify(tab, loHead);
            }
        }
        if (hiHead != null) {
            if (hi <= UNTREEIFY_THRESHOLD) tab[index + bit] = untreeify(hiHead);
            else {
                tab[index + bit] = hiHead;
                if (loHead != null) treeify(tab, hiHead);
            }
        }
    }

    // Red-black balancing, as in Cormen, Leiserson and Rivest; a missing child counts as black.

    static <K, V> TreeNode<K, V> rotateLeft(TreeNode<K, V> root, final TreeNode<K, V> p) {
        final TreeNode<K, V> r = p == null ? null : p.right;
        if (r == null) return root;
        final TreeNode<K, V> rl = r.left;
        p.right = rl;
        if (rl != null) rl.parent = p;
        final TreeNode<K, V> pp = p.parent;
        r.parent = pp;
        if (pp == null) {
            root = r;
            r.red = false;
        } else if (pp.left == p) pp.left = r;
        else pp.right = r;
        r.left = p;
        p.parent = r;
        return root;
    }

    static <K, V> TreeNode<K, V> rotateRight(TreeNode<K, V> root, final TreeNode<K, V> p) {
        final TreeNode<K, V> l = p == null ? null : p.left;
        if (l == null) return root;
        final TreeNode<K, V> lr = l.right;
        p.left = lr;
        if (lr != null) lr.parent = p;
        final TreeNode<K, V> pp = p.parent;
        l.parent = pp;
        if (pp == null) {
            root = l;
            l.red = false;
        } else if (pp.right == p) pp.right = l;
        else pp.left = l;
        l.right = p;
        p.parent = l;
        return root;
    }

    static <K, V> TreeNode<K, V> fixAfterInsert(TreeNode<K, V> root, TreeNode<K, V> x) {
        x.red = true;
        while (true) {
            TreeNode<K, V> xp = x.parent;
            if (xp == null) {
                x.red = false;
                return x;
            }
            if (!xp.red) return root;
            TreeNode<K, V> xpp = xp.parent;
            if (xpp == null) return root;
            if (xp == xpp.left) {
                final TreeNode<K, V> uncle = xpp.right;
                if (uncle != null && uncle.red) {
                    uncle.red = false;
                    xp.red = false;
                    xpp.red = true;
                    x = xpp;
                    continue;
                }
                if (x == xp.right) {
                    x = xp;
                    root = rotateLeft(root, x);
                    xp = x.parent;
                    xpp = xp == null ? null : xp.parent;
                }
                if (xp != null) {
                    xp.red = false;
                    if (xpp != null) {
                        xpp.red = true;
                        root = rotateRight(root, xpp);
                    }
                }
            } else {
                final TreeNode<K, V> uncle = xpp.left;
                if (uncle != null && uncle.red) {
                    uncle.red = false;
                    xp.red = false;
                    xpp.red = true;
                    x = xpp;
                    continue;
                }
                if (x == xp.left) {
                    x = xp;
                    root = rotateRight(root, x);
                    xp = x.parent;
                    xpp = xp == null ? null : xp.parent;
                }
                if (xp != null) {
                    xp.red = false;
                    if (xpp != null) {
                        xpp.red = true;
                        root = rotateLeft(root, xpp);
                    }
                }
            }
        }
    }

    static <K, V> TreeNode<K, V> fixAfterDelete(TreeNode<K, V> root, TreeNode<K, V> x) {
        while (true) {
            if (x == null || x == root) return root;
            TreeNode<K, V> xp = x.parent;
            if (xp == null) {
                x.red = false;
                return x;
            }
            if (x.red) {
                x.red = false;
                return root;
            }
            if (xp.left == x) {
                TreeNode<K, V> w = xp.right;
                if (w != null && w.red) {
                    w.red = false;
                    xp.red = true;
                    root = rotateLeft(root, xp);
                    xp = x.parent;
                    w = xp == null ? null : xp.right;
                }
                if (w == null) {
                    x = xp;
                    continue;
                }
                final TreeNode<K, V> wl = w.left, wr = w.right;
                if ((wr == null || !wr.red) && (wl == null || !wl.red)) {
                    w.red = true;
                    x = xp;
                    continue;
                }
                if (wr == null || !wr.red) {
                    if (wl != null) wl.red = false;
                    w.red = true;
                    root = rotateRight(root, w);
                    xp = x.parent;
                    w = xp == null ? null : xp.right;
                }
                if (w != null) {
                    w.red = xp != null && xp.red;
                    if (w.right != null) w.right.red = false;
                }
                if (xp != null) {
                    xp.red = false;
                    root = rotateLeft(root, xp);
                }
                x = root;
            } else {
                TreeNode<K, V> w = xp.left;
                if (w != null && w.red) {
                    w.red = false;
                    xp.red = true;
                    root = rotateRight(root, xp);
                    xp = x.parent;
                    w = xp == null ? null : xp.left;
                }
                if (w == null) {
                    x = xp;
                    continue;
                }
                final TreeNode<K, V> wl = w.left, wr = w.right;
                if ((wl == null || !wl.red) && (wr == null || !wr.red)) {
                    w.red = true;
                    x = xp;
                    continue;
                }
                if (wl == null || !wl.red) {
                    if (wr != null) wr.red = false;
                    w.red = true;
                    root = rotateLeft(root, w);
                    xp = x.parent;
                    w = xp == null ? null : xp.left;
                }
                if (w != null) {
                    w.red = xp != null && xp.red;
                    if (w.left != null) w.left.red = false;
                }
                if (xp != null) {
                    xp.red = false;
                    root = rotateRight(root, xp);
                }
                x = root;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- views, iterator

    /** Walks the table bucket by bucket, each bucket's chain in order. */
    abstract class Walk {

        Node<K, V> next;
        Node<K, V> current;
        int expectedModCount;
        int index;

        Walk() {
            expectedModCount = modCount;
            final Node<K, V>[] t = table;
            if (t != null && size > 0) {
                while (index < t.length && (next = t[index++]) == null) {}
            }
        }

        public final boolean hasNext() {
            return next != null;
        }

        final Node<K, V> nextNode() {
            final Node<K, V> e = next;
            if (modCount != expectedModCount) throw new ConcurrentModificationException();
            if (e == null) throw new NoSuchElementException();
            current = e;
            next = e.next;
            final Node<K, V>[] t = table;
            if (next == null && t != null) {
                while (index < t.length && (next = t[index++]) == null) {}
            }
            return e;
        }

        public final void remove() {
            final Node<K, V> p = current;
            if (p == null) throw new IllegalStateException();
            if (modCount != expectedModCount) throw new ConcurrentModificationException();
            current = null;
            removeNode(p.hash, p.key, null, false, false);
            expectedModCount = modCount;
        }
    }

    final class KeyWalk extends Walk implements Iterator<K> {

        @Override
        public K next() {
            return nextNode().key;
        }
    }

    final class ValueWalk extends Walk implements Iterator<V> {

        @Override
        public V next() {
            return nextNode().value;
        }
    }

    final class EntryWalk extends Walk implements Iterator<Map.Entry<K, V>> {

        @Override
        public Map.Entry<K, V> next() {
            return nextNode();
        }
    }

    @Override
    public Set<K> keySet() {
        Set<K> ks = keys;
        if (ks == null) keys = ks = new KeyView();
        return ks;
    }

    @Override
    public Collection<V> values() {
        Collection<V> vs = vals;
        if (vs == null) vals = vs = new ValueView();
        return vs;
    }

    @Override
    public Set<Map.Entry<K, V>> entrySet() {
        Set<Map.Entry<K, V>> es = entries;
        if (es == null) entries = es = new EntryView();
        return es;
    }

    final class KeyView extends AbstractSet<K> {

        @Override
        public int size() {
            return size;
        }

        @Override
        public void clear() {
            JavaHashMap.this.clear();
        }

        @Override
        public Iterator<K> iterator() {
            return new KeyWalk();
        }

        @Override
        public boolean contains(final Object o) {
            return containsKey(o);
        }

        @Override
        public boolean remove(final Object key) {
            return removeNode(spread(key), key, null, false, true) != null;
        }

        @Override
        public void forEach(final Consumer<? super K> action) {
            if (action == null) throw new NullPointerException();
            final Node<K, V>[] tab = table;
            if (size == 0 || tab == null) return;
            final int mc = modCount;
            for (Node<K, V> e : tab) for (; e != null; e = e.next) action.accept(e.key);
            if (modCount != mc) throw new ConcurrentModificationException();
        }
    }

    final class ValueView extends AbstractCollection<V> {

        @Override
        public int size() {
            return size;
        }

        @Override
        public void clear() {
            JavaHashMap.this.clear();
        }

        @Override
        public Iterator<V> iterator() {
            return new ValueWalk();
        }

        @Override
        public boolean contains(final Object o) {
            return containsValue(o);
        }

        @Override
        public void forEach(final Consumer<? super V> action) {
            if (action == null) throw new NullPointerException();
            final Node<K, V>[] tab = table;
            if (size == 0 || tab == null) return;
            final int mc = modCount;
            for (Node<K, V> e : tab) for (; e != null; e = e.next) action.accept(e.value);
            if (modCount != mc) throw new ConcurrentModificationException();
        }
    }

    final class EntryView extends AbstractSet<Map.Entry<K, V>> {

        @Override
        public int size() {
            return size;
        }

        @Override
        public void clear() {
            JavaHashMap.this.clear();
        }

        @Override
        public Iterator<Map.Entry<K, V>> iterator() {
            return new EntryWalk();
        }

        @Override
        public boolean contains(final Object o) {
            if (!(o instanceof Map.Entry<?, ?> e)) return false;
            final Node<K, V> candidate = node(e.getKey());
            return candidate != null && candidate.equals(e);
        }

        @Override
        public boolean remove(final Object o) {
            if (!(o instanceof Map.Entry<?, ?> e)) return false;
            final Object key = e.getKey();
            return removeNode(spread(key), key, e.getValue(), true, true) != null;
        }

        @Override
        public void forEach(final Consumer<? super Map.Entry<K, V>> action) {
            if (action == null) throw new NullPointerException();
            final Node<K, V>[] tab = table;
            if (size == 0 || tab == null) return;
            final int mc = modCount;
            for (Node<K, V> e : tab) for (; e != null; e = e.next) action.accept(e);
            if (modCount != mc) throw new ConcurrentModificationException();
        }
    }
}
