package com.gtnhplanner.read.postea;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The item renames the running game applies to old saves, read from Postea's registries. When a pack moves an item
 * to a new id (IC2's plantball became a GregTech meta item), the mod that moved it registers a Postea replacement so
 * worlds convert old stacks; the same table tells the planner that an old plan's resource is the new one.
 *
 * <p>
 * Postea keeps simple 1:1 replacements (item, optionally per meta, to item, optionally with a fixed meta) in a table
 * of plain data, and custom replacements as code. Only the table is read: a custom handler cannot be turned into
 * pairs, so its original id is listed with the handler's class and nothing is guessed for it. Postea has no public
 * getter for its table, so the private fields are read by name; a Postea without them gives an empty result.
 *
 * <p>
 * No Minecraft types: the caller names items ({@code itemName}, normally the item registry's name for an Item).
 */
public final class PosteaRenames {

    /** Forge's {@code OreDictionary.WILDCARD_VALUE}: any meta on the old side, the stack's own meta on the new. */
    public static final int WILDCARD = 32767;

    public static final String SIMPLE_REGISTRY = "com.gtnewhorizons.postea.utility.SimpleTransformationRegistry";
    public static final String TRANSFORMER_REGISTRY = "com.gtnewhorizons.postea.utility.TransformerRegistry";

    /** One simple replacement: {@code from} (as registered, any case) at {@code fromMeta} becomes {@code to}. */
    public static final class Pair {

        public final String from;
        public final int fromMeta;
        public final String to;
        public final int toMeta;

        public Pair(final String from, final int fromMeta, final String to, final int toMeta) {
            this.from = from;
            this.fromMeta = fromMeta;
            this.to = to;
            this.toMeta = toMeta;
        }
    }

    /** An original id handled by code rather than the table. */
    public static final class Handler {

        public final String from;
        public final String handler;

        public Handler(final String from, final String handler) {
            this.from = from;
            this.handler = handler;
        }
    }

    public final List<Pair> items;
    public final List<Handler> handlers;

    private PosteaRenames(final List<Pair> items, final List<Handler> handlers) {
        this.items = Collections.unmodifiableList(items);
        this.handlers = Collections.unmodifiableList(handlers);
    }

    /** Postea's own registries, or empty when Postea is not loaded. */
    public static PosteaRenames read(final Function<Object, String> itemName) {
        return read(SIMPLE_REGISTRY, TRANSFORMER_REGISTRY, itemName);
    }

    /** The registries under these class names (tests pass stand-ins shaped like Postea's). */
    public static PosteaRenames read(final String simpleRegistry, final String transformerRegistry,
        final Function<Object, String> itemName) {
        final List<Pair> items = new ArrayList<>();
        final List<Handler> handlers = new ArrayList<>();
        try {
            final Object table = staticField(Class.forName(simpleRegistry), "SIMPLE_ITEM_TRANSFORMATION_MAP");
            final Map<?, ?> byId = (Map<?, ?>) field(table, "registry");
            for (final Map.Entry<?, ?> id : byId.entrySet()) {
                for (final Map.Entry<?, ?> meta : ((Map<?, ?>) id.getValue()).entrySet()) {
                    final Object value = meta.getValue();
                    final String target = itemName.apply(field(value, "target"));
                    if (target == null || target.isEmpty()) continue;
                    items.add(
                        new Pair(
                            String.valueOf(id.getKey()),
                            ((Number) meta.getKey()).intValue(),
                            target,
                            ((Number) field(value, "targetMeta")).intValue()));
                }
            }
        } catch (ReflectiveOperationException | ClassCastException | NullPointerException e) {
            items.clear();
        }
        try {
            final Object multimap = staticField(Class.forName(transformerRegistry), "ITEM_REPLACEMENT_MAP");
            final Method entries = multimap.getClass()
                .getMethod("entries");
            entries.setAccessible(true);
            for (final Object o : (Collection<?>) entries.invoke(multimap)) {
                final Map.Entry<?, ?> entry = (Map.Entry<?, ?>) o;
                final String handler = entry.getValue()
                    .getClass()
                    .getName();
                // The table's own entries, compiled into one handler per id at load: already read above.
                if (handler.endsWith("$SimpleItemTransformationHandler")) continue;
                handlers.add(new Handler(String.valueOf(entry.getKey()), handler));
            }
        } catch (ReflectiveOperationException | ClassCastException | NullPointerException e) {
            handlers.clear();
        }
        items.sort(
            Comparator.comparing((Pair p) -> p.from)
                .thenComparingInt(p -> p.fromMeta));
        handlers.sort(
            Comparator.comparing((Handler h) -> h.from)
                .thenComparing(h -> h.handler));
        return new PosteaRenames(items, handlers);
    }

    /** The export's {@code resourceRenames} object: raw, as registered; the pipeline turns it into resource ids. */
    public Map<String, Object> toExport() {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "postea");
        final List<Map<String, Object>> rows = new ArrayList<>();
        for (final Pair p : items) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("from", p.from);
            row.put("fromMeta", p.fromMeta);
            row.put("to", p.to);
            row.put("toMeta", p.toMeta);
            rows.add(row);
        }
        out.put("items", rows);
        final List<Map<String, Object>> code = new ArrayList<>();
        for (final Handler h : handlers) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("from", h.from);
            row.put("handler", h.handler);
            code.add(row);
        }
        out.put("handlers", code);
        return out;
    }

    /**
     * The simple pairs as resource ids, old id to new: the rule of the dataset's {@code resource-renames.json}
     * (tools/dataset-pipeline/scripts/resource-renames.mjs) and the website's
     * {@code src/lib/datasets/resource-renames.ts},
     * for the mod, which reads the table live. Names are lowercased; a meta of 0 is left off; a wildcard meta is
     * {@code @*} (on {@code from} every meta of the item, on {@code to} the stack's own meta). The first row for an id
     * wins, and a row that renames an id to itself is left out.
     */
    public Map<String, String> table() {
        return table(items);
    }

    /** {@link #table()} for these pairs. */
    public static Map<String, String> table(final List<Pair> pairs) {
        final Map<String, String> out = new LinkedHashMap<>();
        for (final Pair p : pairs) {
            final String fromName = canonical(p.from), toName = canonical(p.to);
            if (fromName.isEmpty() || toName.isEmpty()) continue;
            final String from = withMeta(fromName, p.fromMeta), to = withMeta(toName, p.toMeta);
            if (from.equals(to) || out.containsKey(from)) continue;
            out.put(from, to);
        }
        return out;
    }

    /**
     * The id the game turns an item id into, or the id itself: its own meta's row first, then the item's {@code @*}
     * row.
     * Only items are renamed; the caller passes item ids only.
     */
    public static String rename(final Map<String, String> table, final String id) {
        if (table.isEmpty()) return id;
        final int at = id.lastIndexOf('@');
        boolean hasMeta = at > 0 && at < id.length() - 1 && id.length() - at - 1 <= 18;
        for (int i = at + 1; hasMeta && i < id.length(); i++) hasMeta = Character.isDigit(id.charAt(i));
        final String name = hasMeta ? id.substring(0, at) : id;
        String to = table.get(id);
        if (to == null) to = table.get(name + "@*");
        if (to == null) return id;
        if (!to.endsWith("@*")) return to;
        final String toName = to.substring(0, to.length() - 2);
        if (!hasMeta) return toName;
        final long meta = Long.parseLong(id.substring(at + 1));
        return meta == WILDCARD ? toName + "@*" : meta == 0 ? toName : toName + "@" + meta;
    }

    private static String canonical(final String registryName) {
        final String name = registryName == null ? "" : registryName.trim();
        return name.toLowerCase(Locale.ROOT);
    }

    private static String withMeta(final String name, final int meta) {
        if (meta == WILDCARD) return name + "@*";
        return meta == 0 ? name : name + "@" + meta;
    }

    private static Object staticField(final Class<?> owner, final String name) throws ReflectiveOperationException {
        final Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private static Object field(final Object owner, final String name) throws ReflectiveOperationException {
        Class<?> type = owner.getClass();
        while (type != null) {
            try {
                final Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(owner);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
