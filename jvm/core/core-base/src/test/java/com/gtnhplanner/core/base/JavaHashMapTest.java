package com.gtnhplanner.core.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

/**
 * JavaHashMap and JavaHashSet iterate exactly as this JDK's HashMap and HashSet do: the same random operations on both,
 * every answer and the whole iteration order (entries, keys, values, forEach) compared after every step. The keys
 * include ones made to collide, so buckets become trees, split, shrink back to chains and lose nodes through the map and
 * through iterators; the test counts that each of those happened. The JDK is the one the tests run on (25), whose
 * HashMap is the reference: JDK 19 and later size copies alike.
 */
class JavaHashMapTest {

    /** A Comparable key with a chosen hash code: many share a bucket, some share a hash and are told apart by id. */
    record Key(int hash, int id) implements Comparable<Key> {

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(final Object o) {
            return o instanceof Key k && k.id == id && k.hash == hash;
        }

        @Override
        public int compareTo(final Key o) {
            return Integer.compare(id, o.id);
        }
    }

    /** A key that does not compare, each with its own hash (equal hashes would let the JDK order by identity). */
    record Plain(int id) {

        @Override
        public int hashCode() {
            return id * 4096 + 7;
        }
    }

    private static Object key(final Random r, final int kind) {
        return switch (kind) {
            case 0 -> r.nextInt(200);
            case 1 -> r.nextInt();
            case 2 -> "port_" + r.nextInt(400);
            case 3 -> new Key((r.nextInt(4)) << 10, r.nextInt(300)); // four buckets at every table length up to 1024
            case 4 -> new Key(r.nextInt(3) * 64, r.nextInt(60)); // equal hashes, ordered by compareTo
            case 5 -> r.nextInt(8) == 0 ? null : new Plain(r.nextInt(40) * 16);
            default -> r.nextInt(6) == 0 ? new Plain(r.nextInt(30)) : (Object) (r.nextInt(64) * 1024);
        };
    }

    private static List<String> order(final Map<Object, Integer> m) {
        final List<String> out = new ArrayList<>();
        for (final Map.Entry<Object, Integer> e : m.entrySet()) out.add(e.getKey() + "=" + e.getValue());
        out.add("|");
        for (final Object k : m.keySet()) out.add(String.valueOf(k));
        out.add("|");
        for (final Integer v : m.values()) out.add(String.valueOf(v));
        out.add("|");
        m.forEach((k, v) -> out.add(k + ":" + v));
        return out;
    }

    private int trees, splits, treeRemovals;

    /** Every tree bucket is a valid red-black tree whose chain holds exactly its nodes, root first after a movable change. */
    private void checkTrees(final JavaHashMap<Object, Integer> m) {
        if (m.table == null) return;
        for (int i = 0; i < m.table.length; i++) {
            if (!(m.table[i] instanceof JavaHashMap.TreeNode<Object, Integer> first)) continue;
            trees++;
            int chain = 0;
            JavaHashMap.TreeNode<Object, Integer> prev = null;
            for (JavaHashMap.Node<Object, Integer> e = first; e != null; e = e.next) {
                final JavaHashMap.TreeNode<Object, Integer> t = (JavaHashMap.TreeNode<Object, Integer>) e;
                assertTrue(t.prev == prev, "chain prev link");
                assertEquals(i, t.hash & (m.table.length - 1), "node in its bucket");
                prev = t;
                chain++;
            }
            final JavaHashMap.TreeNode<Object, Integer> root = first.root();
            assertTrue(!root.red, "black root");
            assertEquals(chain, count(root), "tree and chain hold the same nodes");
        }
    }

    private static int count(final JavaHashMap.TreeNode<Object, Integer> t) {
        if (t == null) return 0;
        if (t.left != null) assertTrue(t.left.parent == t);
        if (t.right != null) assertTrue(t.right.parent == t);
        if (t.red) assertTrue((t.left == null || !t.left.red) && (t.right == null || !t.right.red), "red with red child");
        return 1 + count(t.left) + count(t.right);
    }

    private static int treeBuckets(final JavaHashMap<Object, Integer> m) {
        int n = 0;
        if (m.table != null) for (final Object e : m.table) if (e instanceof JavaHashMap.TreeNode) n++;
        return n;
    }

    @Test
    void mapsIterateAsTheJdks() {
        final Random r = new Random(11);
        for (int round = 0; round < 400; round++) {
            final int kind = round % 7;
            final int mixed = (round / 7) % 3; // 0: one kind of key, 1: two kinds, 2: every kind
            final Supplier<Object> keys = () -> key(r, mixed == 0 ? kind : mixed == 1 ? (r.nextBoolean() ? kind : 3) : r.nextInt(7));
            HashMap<Object, Integer> jdk;
            JavaHashMap<Object, Integer> ours;
            switch (round % 4) {
                case 0 -> {
                    jdk = new HashMap<>();
                    ours = new JavaHashMap<>();
                }
                case 1 -> {
                    final int c = r.nextInt(100);
                    jdk = new HashMap<>(c);
                    ours = new JavaHashMap<>(c);
                }
                case 2 -> {
                    final int c = r.nextInt(70);
                    final float lf = new float[] { 0.5f, 0.75f, 1f, 2f, 0.6f, 3.3f }[r.nextInt(6)];
                    jdk = new HashMap<>(c, lf);
                    ours = new JavaHashMap<>(c, lf);
                }
                default -> {
                    final Map<Object, Integer> seed = new LinkedHashMap<>();
                    for (int i = r.nextInt(80); i > 0; i--) seed.put(keys.get(), r.nextInt(1000));
                    jdk = new HashMap<>(seed);
                    ours = new JavaHashMap<>(seed);
                }
            }
            final int steps = 200 + r.nextInt(3000);
            for (int step = 0; step < steps; step++) {
                final Object k = keys.get();
                final int v = r.nextInt(1000);
                final String what = "round " + round + " step " + step;
                final int op = r.nextInt(100);
                final int treesBefore = treeBuckets(ours);
                if (op < 40) assertEquals(jdk.put(k, v), ours.put(k, v), what);
                else if (op < 45) assertEquals(jdk.putIfAbsent(k, v), ours.putIfAbsent(k, v), what);
                else if (op < 55) assertEquals(jdk.remove(k), ours.remove(k), what);
                else if (op < 57) assertEquals(jdk.remove(k, v % 3), ours.remove(k, v % 3), what);
                else if (op < 63) {
                    final Function<Object, Integer> f = x -> v % 5 == 0 ? null : v;
                    assertEquals(jdk.computeIfAbsent(k, f), ours.computeIfAbsent(k, f), what);
                } else if (op < 67) {
                    final BiFunction<Object, Integer, Integer> f = (x, old) -> v % 4 == 0 ? null : v;
                    assertEquals(jdk.compute(k, f), ours.compute(k, f), what);
                } else if (op < 69) {
                    final BiFunction<Object, Integer, Integer> f = (x, old) -> v % 3 == 0 ? null : old + v;
                    assertEquals(jdk.computeIfPresent(k, f), ours.computeIfPresent(k, f), what);
                } else if (op < 73) {
                    final BiFunction<Integer, Integer, Integer> f = (a, b) -> v % 4 == 0 ? null : a + b;
                    assertEquals(jdk.merge(k, v, f), ours.merge(k, v, f), what);
                } else if (op < 75) {
                    assertEquals(jdk.replace(k, v), ours.replace(k, v), what);
                } else if (op < 77) {
                    assertEquals(jdk.get(k), ours.get(k), what);
                    assertEquals(jdk.containsKey(k), ours.containsKey(k), what);
                    assertEquals(jdk.getOrDefault(k, -1), ours.getOrDefault(k, -1), what);
                } else if (op < 80) {
                    // Removal through iterators: no root moved, no tree shrunk.
                    final int every = 2 + r.nextInt(5);
                    final Iterator<?> a = (r.nextBoolean() ? jdk.keySet() : jdk.entrySet()).iterator();
                    final Iterator<?> b = (r.nextBoolean() ? ours.keySet() : ours.entrySet()).iterator();
                    for (int i = 0; a.hasNext(); i++) {
                        assertTrue(b.hasNext(), what);
                        a.next();
                        b.next();
                        if (i % every == 0) {
                            a.remove();
                            b.remove();
                        }
                    }
                } else if (op < 82) {
                    assertEquals(jdk.keySet().remove(k), ours.keySet().remove(k), what);
                } else if (op < 83) {
                    assertEquals(jdk.entrySet().remove(Map.entry(k == null ? "x" : k, v % 7)),
                        ours.entrySet().remove(Map.entry(k == null ? "x" : k, v % 7)), what);
                } else if (op < 84) {
                    assertEquals(jdk.values().remove(v % 50), ours.values().remove(v % 50), what);
                } else if (op < 85) {
                    final Map<Object, Integer> more = new LinkedHashMap<>();
                    for (int i = r.nextInt(60); i > 0; i--) more.put(keys.get(), r.nextInt(1000));
                    jdk.putAll(more);
                    ours.putAll(more);
                } else if (op < 86) {
                    jdk.replaceAll((x, old) -> old + 1);
                    ours.replaceAll((x, old) -> old + 1);
                } else if (op < 87) {
                    final int m = 2 + r.nextInt(4);
                    jdk.keySet().removeIf(x -> Objects.hashCode(x) % m == 0);
                    ours.keySet().removeIf(x -> Objects.hashCode(x) % m == 0);
                } else if (op < 88) {
                    final Set<Object> keep = new HashSet<>();
                    for (int i = 0; i < 30; i++) keep.add(keys.get());
                    assertEquals(jdk.keySet().retainAll(keep), ours.keySet().retainAll(keep), what);
                } else if (op < 89) {
                    assertEquals(order(new HashMap<>(jdk)), order(new JavaHashMap<>(ours)), what + " copy");
                    @SuppressWarnings("unchecked")
                    final Map<Object, Integer> a = (Map<Object, Integer>) jdk.clone();
                    @SuppressWarnings("unchecked")
                    final Map<Object, Integer> b = (Map<Object, Integer>) ours.clone();
                    assertEquals(order(a), order(b), what + " clone");
                } else if (op == 89 && r.nextInt(8) == 0) {
                    jdk.clear();
                    ours.clear();
                } else {
                    assertEquals(jdk.put(k, v), ours.put(k, v), what);
                }
                if (treeBuckets(ours) < treesBefore) treeRemovals++;
                assertEquals(jdk.size(), ours.size(), what);
                assertEquals(order(jdk), order(ours), what);
                assertEquals(jdk, ours, what);
                assertEquals(jdk.hashCode(), ours.hashCode(), what);
                checkTrees(ours);
            }
            final int before = treeBuckets(ours);
            if (before > 0 && ours.table.length < JavaHashMap.MAXIMUM_CAPACITY) {
                // Grow it: every tree bucket splits.
                final int length = ours.table.length;
                for (int i = 0; i < length; i++) {
                    final Object k = new Key(-1 - i, i);
                    jdk.put(k, i);
                    ours.put(k, i);
                }
                splits++;
                assertEquals(order(jdk), order(ours), "round " + round + " after growing");
                checkTrees(ours);
            }
        }
        // The rules were exercised, not only the plain chains.
        assertTrue(trees > 10000, "tree buckets seen: " + trees);
        assertTrue(splits > 50, "grown with tree buckets: " + splits);
        assertTrue(treeRemovals > 100, "tree buckets gone back to chains or emptied: " + treeRemovals);
    }

    @Test
    void setsIterateAsTheJdks() {
        final Random r = new Random(12);
        for (int round = 0; round < 300; round++) {
            final int kind = round % 7;
            final Supplier<Object> keys = () -> key(r, kind);
            final List<Object> source = new ArrayList<>();
            for (int i = r.nextInt(100); i > 0; i--) source.add(keys.get());
            final Set<Object> jdk;
            final Set<Object> ours;
            switch (round % 4) {
                case 0 -> {
                    jdk = new HashSet<>(source);
                    ours = new JavaHashSet<>(source);
                }
                case 1 -> {
                    final int n = r.nextInt(50);
                    jdk = HashSet.newHashSet(n);
                    ours = JavaHashSet.newHashSet(n);
                }
                case 2 -> {
                    final int c = r.nextInt(50);
                    jdk = new HashSet<>(c);
                    ours = new JavaHashSet<>(c);
                }
                default -> {
                    jdk = new HashSet<>();
                    ours = new JavaHashSet<>();
                }
            }
            for (int step = 0; step < 1500; step++) {
                final Object k = keys.get();
                final int op = r.nextInt(20);
                final String what = "round " + round + " step " + step;
                if (op < 10) assertEquals(jdk.add(k), ours.add(k), what);
                else if (op < 15) assertEquals(jdk.remove(k), ours.remove(k), what);
                else if (op == 15) {
                    final Collection<Object> some = new ArrayList<>();
                    for (int i = r.nextInt(20); i > 0; i--) some.add(keys.get());
                    assertEquals(jdk.removeAll(some), ours.removeAll(some), what);
                } else if (op == 16) {
                    final Collection<Object> some = new ArrayList<>();
                    for (int i = r.nextInt(20); i > 0; i--) some.add(keys.get());
                    assertEquals(jdk.addAll(some), ours.addAll(some), what);
                } else if (op == 17) {
                    final Iterator<Object> a = jdk.iterator(), b = ours.iterator();
                    for (int i = 0; a.hasNext(); i++) {
                        assertEquals(a.next(), b.next(), what);
                        if (i % 3 == 0) {
                            a.remove();
                            b.remove();
                        }
                    }
                } else if (op == 18) {
                    assertEquals(new ArrayList<>(new HashSet<>(jdk)), new ArrayList<>(new JavaHashSet<>(ours)), what + " copy");
                    @SuppressWarnings("unchecked")
                    final Set<Object> a = (Set<Object>) ((HashSet<Object>) jdk).clone();
                    @SuppressWarnings("unchecked")
                    final Set<Object> b = (Set<Object>) ((JavaHashSet<Object>) ours).clone();
                    assertEquals(new ArrayList<>(a), new ArrayList<>(b), what + " clone");
                } else assertEquals(jdk.contains(k), ours.contains(k), what);
                assertEquals(new ArrayList<>(jdk), new ArrayList<>(ours), what);
                assertEquals(jdk, ours, what);
            }
        }
    }

    @Test
    void sizesTablesAsTheJdk() {
        for (int c = 0; c < 3000; c++) assertEquals(Integer.highestOneBit(Math.max(1, (c << 1) - 1)), JavaHashMap.tableSizeFor(c), "c " + c);
        for (int n = 0; n < 3000; n++) assertEquals((int) Math.ceil(n / 0.75), JavaHashMap.capacityFor(n));
    }
}
