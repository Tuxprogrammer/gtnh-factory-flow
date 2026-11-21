package com.gtnhplanner.data.flowchart;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.flowchart.balancer.ChoiceKey;
import com.gtnhplanner.data.flowchart.balancer.PortRef;

/**
 * The save's JSON of the plan model's notes, groups, board groups and drawers, written and read field by field: no
 * reflection, so it runs where Gson's binding cannot (the core's purity gate, the web compile).
 *
 * <p>
 * The shape is the one Gson's reflective binding gave every save so far, so old saves read and new saves are the same
 * bytes: a class's own fields first in declaration order, then its superclass's ({@link GraphData}: id, x, y, header,
 * type); a null field left out; an id as its text; an enum by its name. Numbers keep their Java type in the tree
 * ({@code Integer} for an int field, {@code Double} for a double), so a host that prints the tree with Gson prints them
 * as Gson printed the fields. Reading starts from the class's own no-argument constructor, as Gson did (a new group
 * still draws its colour from the shared sequence), and sets only what the save holds; a malformed value throws, and
 * the caller loses that one thing or that one chart, as before.
 *
 * <p>
 * Lives in the model's package (split across core-model and core-codec, as the mod's own classes of this package are)
 * so it can set the fields the classes keep package-private for it.
 */
public final class SaveCodec {

    private SaveCodec() {}

    // region Writing

    /** A note as the save holds it. */
    public static Map<String, Object> note(final Note n) {
        final Map<String, Object> o = new LinkedHashMap<>();
        put(o, "text", n.text == null ? null : new ArrayList<Object>(n.text));
        o.put("width", n.width);
        o.put("height", n.height);
        put(o, "color", n.color);
        o.put("fontSize", n.fontSize);
        put(o, "kind", n.kind);
        put(o, "arrowDirection", n.arrowDirection);
        put(o, "points", points(n.points));
        put(o, "arrowHeads", n.arrowHeads);
        o.put("arrowWidth", n.arrowWidth);
        put(o, "imageUrl", n.imageUrl);
        put(o, "border", n.border);
        put(o, "borderColor", n.borderColor);
        put(o, "fillColor", n.fillColor);
        put(o, "fill", n.fill);
        put(o, "marks", n.marks);
        put(o, "group", id(n.group));
        graphData(o, n);
        return o;
    }

    /** A group (a plain one or a machine group) as the save holds it. */
    public static Map<String, Object> group(final Group g) {
        final Map<String, Object> o = new LinkedHashMap<>();
        if (g instanceof final MachineGroup m) {
            o.put("machineCapacity", m.machineCapacity);
            put(o, "machineFraction", m.machineFraction);
            o.put("pinned", m.pinned);
            put(o, "exact", m.exact);
            put(o, "sections", ids(m.sections));
        }
        o.put("width", g.width);
        o.put("height", g.height);
        o.put("color", g.color);
        o.put("collapsed", g.collapsed);
        o.put("clampNodes", g.clampNodes);
        o.put("coverChildren", g.coverChildren);
        if (g.children != null) {
            // Nothing puts a child in a group; were one there, it is written as the thing it is at its base.
            final Map<String, Object> children = new LinkedHashMap<>();
            for (final Map.Entry<Uuid, GraphData> c : g.children.entrySet()) {
                final Map<String, Object> child = new LinkedHashMap<>();
                if (c.getValue() != null) graphData(child, c.getValue());
                children.put(String.valueOf(c.getKey()), c.getValue() == null ? null : child);
            }
            o.put("children", children);
        }
        put(o, "nodeIds", ids(g.nodeIds));
        graphData(o, g);
        return o;
    }

    /** A board group as the save holds it. */
    public static Map<String, Object> boardGroup(final BoardGroup b) {
        final Map<String, Object> o = new LinkedHashMap<>();
        put(o, "id", id(b.id));
        put(o, "ffId", b.ffId);
        put(o, "name", b.name);
        put(o, "color", b.color);
        put(o, "parent", id(b.parent));
        o.put("x", b.x);
        o.put("y", b.y);
        o.put("width", b.width);
        o.put("height", b.height);
        o.put("collapsed", b.collapsed);
        put(o, "theme", b.theme);
        put(o, "pattern", b.pattern);
        return o;
    }

    /** A drawer as the save holds it. */
    public static Map<String, Object> drawer(final Drawer d) {
        final Map<String, Object> o = new LinkedHashMap<>();
        put(o, "kind", d.kind == null ? null : d.kind.name());
        put(o, "rule", d.rule == null ? null : d.rule.name());
        o.put("rate", d.rate);
        put(o, "links", links(d.links));
        put(o, "takers", links(d.takers));
        put(o, "resourceKey", d.resourceKey);
        put(o, "label", d.label);
        put(o, "group", id(d.group));
        graphData(o, d);
        return o;
    }

    /** A chosen excess (the summary's {@code excessChoice}) as the save holds it. */
    public static Map<String, Object> choiceKey(final ChoiceKey k) {
        final Map<String, Object> o = new LinkedHashMap<>();
        if (k.gateAnchors() != null) {
            final List<Object> anchors = new ArrayList<>();
            for (final PortRef p : k.gateAnchors()) {
                if (p == null) {
                    anchors.add(null);
                    continue;
                }
                final Map<String, Object> a = new LinkedHashMap<>();
                put(a, "nodeId", id(p.nodeId()));
                a.put("portIndex", p.portIndex());
                a.put("input", p.input());
                anchors.add(a);
            }
            o.put("gateAnchors", anchors);
        }
        return o;
    }

    /** What every board thing writes after its own fields: id, x, y, header, type. */
    public static void graphData(final Map<String, Object> o, final GraphData g) {
        put(o, "id", id(g.id));
        o.put("x", g.x);
        o.put("y", g.y);
        put(o, "header", g.header);
        put(o, "type", g.type);
    }

    private static void put(final Map<String, Object> o, final String key, @Nullable final Object value) {
        if (value != null) o.put(key, value);
    }

    @Nullable
    private static String id(@Nullable final Uuid id) {
        return id == null ? null : id.toString();
    }

    @Nullable
    private static List<Object> ids(@Nullable final Iterable<Uuid> ids) {
        if (ids == null) return null;
        final List<Object> out = new ArrayList<>();
        for (final Uuid id : ids) out.add(id(id));
        return out;
    }

    @Nullable
    private static List<Object> points(@Nullable final List<int[]> points) {
        if (points == null) return null;
        final List<Object> out = new ArrayList<>();
        for (final int[] p : points) {
            if (p == null) {
                out.add(null);
                continue;
            }
            final List<Object> xy = new ArrayList<>();
            for (final int v : p) xy.add(v);
            out.add(xy);
        }
        return out;
    }

    @Nullable
    private static List<Object> links(@Nullable final List<Drawer.Link> links) {
        if (links == null) return null;
        final List<Object> out = new ArrayList<>();
        for (final Drawer.Link l : links) {
            if (l == null) {
                out.add(null);
                continue;
            }
            final Map<String, Object> o = new LinkedHashMap<>();
            put(o, "nodeId", id(l.nodeId()));
            o.put("portIndex", l.portIndex());
            put(o, "waypoints", points(l.rawWaypoints()));
            out.add(o);
        }
        return out;
    }

    // endregion

    // region Reading

    /** A note from the save. */
    public static Note readNote(final Map<String, Object> o) {
        final Note n = new Note();
        if (has(o, "text")) n.text = strings(o.get("text"));
        if (has(o, "width")) n.width = integer(o.get("width"));
        if (has(o, "height")) n.height = integer(o.get("height"));
        if (o.containsKey("color")) n.color = str(o.get("color"));
        if (has(o, "fontSize")) n.fontSize = integer(o.get("fontSize"));
        if (o.containsKey("kind")) n.kind = str(o.get("kind"));
        if (o.containsKey("arrowDirection")) n.arrowDirection = str(o.get("arrowDirection"));
        if (o.containsKey("points")) n.points = readPoints(o.get("points"));
        if (o.containsKey("arrowHeads")) n.arrowHeads = str(o.get("arrowHeads"));
        if (has(o, "arrowWidth")) n.arrowWidth = integer(o.get("arrowWidth"));
        if (o.containsKey("imageUrl")) n.imageUrl = str(o.get("imageUrl"));
        if (o.containsKey("border")) n.border = str(o.get("border"));
        if (o.containsKey("borderColor")) n.borderColor = str(o.get("borderColor"));
        if (o.containsKey("fillColor")) n.fillColor = str(o.get("fillColor"));
        if (o.containsKey("fill")) n.fill = str(o.get("fill"));
        if (o.containsKey("marks")) n.marks = str(o.get("marks"));
        if (o.containsKey("group")) n.group = uuid(o.get("group"));
        readGraphData(o, n);
        return n;
    }

    /**
     * A group from the save, plain or a machine group by its {@code type}.
     *
     * @throws IllegalArgumentException for any other type: a save's group list holds groups only
     */
    public static Group readGroup(final Map<String, Object> o) {
        final Object type = o.get("type");
        final Group g;
        if ("group".equals(type)) g = new Group();
        else if (MachineGroup.TYPE.equals(type)) {
            final MachineGroup m = new MachineGroup();
            if (has(o, "machineCapacity")) m.machineCapacity = integer(o.get("machineCapacity"));
            if (o.containsKey("machineFraction")) m.machineFraction = boxed(o.get("machineFraction"));
            if (has(o, "pinned")) m.pinned = bool(o.get("pinned"));
            if (o.containsKey("exact")) m.exact = o.get("exact") == null ? null : bool(o.get("exact"));
            if (o.containsKey("sections")) m.sections = o.get("sections") == null ? null : new ArrayList<>(uuids(o.get("sections")));
            g = m;
        } else throw new IllegalArgumentException("Invalid type detected during GroupData deserialization");
        if (has(o, "width")) g.width = integer(o.get("width"));
        if (has(o, "height")) g.height = integer(o.get("height"));
        if (has(o, "color")) g.color = integer(o.get("color"));
        if (has(o, "collapsed")) g.collapsed = bool(o.get("collapsed"));
        if (has(o, "clampNodes")) g.clampNodes = bool(o.get("clampNodes"));
        if (has(o, "coverChildren")) g.coverChildren = bool(o.get("coverChildren"));
        // Nothing puts a child in a group, so no save holds one: a group reads with none.
        if (has(o, "children")) g.children = new TreeMap<>();
        if (o.containsKey("nodeIds")) g.nodeIds = o.get("nodeIds") == null ? null : new LinkedHashSet<>(uuids(o.get("nodeIds")));
        readGraphData(o, g);
        return g;
    }

    /** A board group from the save, or null when it has no id. */
    @Nullable
    public static BoardGroup readBoardGroup(final Map<String, Object> o) {
        final BoardGroup b = new BoardGroup();
        if (o.containsKey("id")) b.id = uuid(o.get("id"));
        if (o.containsKey("ffId")) b.ffId = str(o.get("ffId"));
        if (o.containsKey("name")) b.name = str(o.get("name"));
        if (o.containsKey("color")) b.color = str(o.get("color"));
        if (o.containsKey("parent")) b.parent = uuid(o.get("parent"));
        if (has(o, "x")) b.x = integer(o.get("x"));
        if (has(o, "y")) b.y = integer(o.get("y"));
        if (has(o, "width")) b.width = integer(o.get("width"));
        if (has(o, "height")) b.height = integer(o.get("height"));
        if (has(o, "collapsed")) b.collapsed = bool(o.get("collapsed"));
        if (o.containsKey("theme")) b.theme = str(o.get("theme"));
        if (o.containsKey("pattern")) b.pattern = str(o.get("pattern"));
        return b.id == null ? null : b;
    }

    /** A drawer from the save, repaired ({@link Drawer#sanitize}); null when it has no id. */
    @Nullable
    public static Drawer readDrawer(final Map<String, Object> o) {
        final Drawer d = new Drawer();
        if (o.containsKey("kind")) d.kind = kind(o.get("kind"));
        if (o.containsKey("rule")) d.rule = rule(o.get("rule"));
        if (has(o, "rate")) d.rate = num(o.get("rate"));
        if (o.containsKey("links")) d.links = readLinks(o.get("links"));
        if (o.containsKey("takers")) d.takers = readLinks(o.get("takers"));
        if (o.containsKey("resourceKey")) d.resourceKey = str(o.get("resourceKey"));
        if (o.containsKey("label")) d.label = str(o.get("label"));
        if (o.containsKey("group")) d.group = uuid(o.get("group"));
        readGraphData(o, d);
        if (d.id == null) return null;
        d.sanitize();
        return d;
    }

    /** A chosen excess from the save, or null. */
    @Nullable
    public static ChoiceKey readChoiceKey(@Nullable final Object saved) {
        if (!(saved instanceof Map)) return null;
        final Object anchors = ((Map<?, ?>) saved).get("gateAnchors");
        if (!(anchors instanceof List)) return new ChoiceKey(null);
        final List<PortRef> out = new ArrayList<>();
        for (final Object a : (List<?>) anchors) {
            if (!(a instanceof Map)) {
                out.add(null);
                continue;
            }
            final Map<?, ?> m = (Map<?, ?>) a;
            out.add(
                new PortRef(
                    uuid(m.get("nodeId")),
                    m.get("portIndex") == null ? 0 : integer(m.get("portIndex")),
                    m.get("input") != null && bool(m.get("input"))));
        }
        return new ChoiceKey(out);
    }

    /** The fields every board thing has: id, x, y, header (the type is the class's own). */
    public static void readGraphData(final Map<String, Object> o, final GraphData g) {
        if (o.containsKey("id")) g.id = uuid(o.get("id"));
        if (has(o, "x")) g.x = integer(o.get("x"));
        if (has(o, "y")) g.y = integer(o.get("y"));
        if (o.containsKey("header")) g.header = str(o.get("header"));
    }

    /** Present and not null: a primitive field keeps its default for a null, as Gson leaves it. */
    private static boolean has(final Map<String, Object> o, final String key) {
        return o.get(key) != null;
    }

    @Nullable
    private static String str(@Nullable final Object v) {
        if (v == null) return null;
        if (v instanceof String) return (String) v;
        if (v instanceof Boolean) return v.toString();
        throw new IllegalArgumentException("Expected a string but was " + v);
    }

    private static double num(final Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        throw new IllegalArgumentException("Expected a number but was " + v);
    }

    @Nullable
    private static Double boxed(@Nullable final Object v) {
        return v == null ? null : num(v);
    }

    /** An int field: a whole number in range, as Gson's reader takes one. */
    private static int integer(final Object v) {
        final double d = num(v);
        final int i = (int) d;
        if (i != d) throw new IllegalArgumentException("Expected an int but was " + v);
        return i;
    }

    private static boolean bool(final Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        throw new IllegalArgumentException("Expected a boolean but was " + v);
    }

    @Nullable
    private static Uuid uuid(@Nullable final Object v) {
        return v == null ? null : Uuid.fromString(str(v));
    }

    private static List<Uuid> uuids(final Object v) {
        final List<Uuid> out = new ArrayList<>();
        for (final Object x : list(v)) out.add(uuid(x));
        return out;
    }

    @Nullable
    private static List<String> strings(@Nullable final Object v) {
        if (v == null) return null;
        final List<String> out = new ArrayList<>();
        for (final Object x : list(v)) out.add(str(x));
        return out;
    }

    private static List<?> list(final Object v) {
        if (v instanceof List) return (List<?>) v;
        throw new IllegalArgumentException("Expected a list but was " + v);
    }

    @Nullable
    private static List<int[]> readPoints(@Nullable final Object v) {
        if (v == null) return null;
        final List<int[]> out = new ArrayList<>();
        for (final Object p : list(v)) {
            if (p == null) {
                out.add(null);
                continue;
            }
            final List<?> xs = list(p);
            final int[] a = new int[xs.size()];
            for (int i = 0; i < a.length; i++) a[i] = integer(xs.get(i));
            out.add(a);
        }
        return out;
    }

    @Nullable
    private static List<Drawer.Link> readLinks(@Nullable final Object v) {
        if (v == null) return null;
        final List<Drawer.Link> out = new ArrayList<>();
        for (final Object x : list(v)) {
            if (!(x instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            final Map<String, Object> m = (Map<String, Object>) x;
            final Uuid node = uuid(m.get("nodeId"));
            // A link without a node is what the repair drops.
            if (node == null) continue;
            final int port = has(m, "portIndex") ? integer(m.get("portIndex")) : 0;
            out.add(new Drawer.Link(node, port, readPoints(m.get("waypoints"))));
        }
        return out;
    }

    /** A drawer kind by name; an unknown name is null, which the repair makes a product. */
    @Nullable
    private static DrawerTypes.Kind kind(@Nullable final Object v) {
        if (!(v instanceof String)) return null;
        for (final DrawerTypes.Kind k : DrawerTypes.Kind.values()) if (k.name()
            .equals(v)) return k;
        return null;
    }

    @Nullable
    private static DrawerTypes.Rule rule(@Nullable final Object v) {
        if (!(v instanceof String)) return null;
        for (final DrawerTypes.Rule r : DrawerTypes.Rule.values()) if (r.name()
            .equals(v)) return r;
        return null;
    }

    // endregion
}
