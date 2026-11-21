package com.gtnhplanner.data.flowchart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.harness.GtnhFlowLoader;

/**
 * Wire steering dots (the website's edge waypoints): kept on an edge and on a drawer's link, saved and read back, on
 * the router's lines and on the website's grid crossing over, and what an arrange and a group's collapse do to them.
 */
class WaypointsTest {

    private static List<int[]> pts(final int... xy) {
        final List<int[]> out = new ArrayList<>();
        for (int i = 0; i + 1 < xy.length; i += 2) out.add(new int[] { xy[i], xy[i + 1] });
        return out;
    }

    private static String text(final List<int[]> points) {
        final StringBuilder s = new StringBuilder();
        for (final int[] p : points) s.append('(')
            .append(p[0])
            .append(',')
            .append(p[1])
            .append(')');
        return s.toString();
    }

    @Test
    void dotsSitOnTheRoutersLinesAndCrossToTheWebsitesGrid() {
        // The router's grid is the website's: a dot sits on the nearest 20 px corner.
        assertEquals(580, Waypoints.onLine(580));
        assertEquals(580, Waypoints.onLine(589));
        assertEquals(600, Waypoints.onLine(590));
        assertEquals(-220, Waypoints.onLine(-220));
        assertEquals(-220, Waypoints.onLine(-211));
        // The website's grid corners come over as they are and go back to the same corner.
        for (final int site : new int[] { -220, -200, 0, 20, 580, 600, 1240 })
            assertEquals(site, Waypoints.toSite(Waypoints.onLine(site)), "corner " + site);
        final List<int[]> read = Waypoints
            .fromSite(new JsonParser().parse("[{\"x\":100,\"y\":-220},{\"x\":\"bad\"},{\"x\":580.4,\"y\":20}]"));
        assertEquals("(100,-220)(580,20)", text(read));
        assertEquals(
            "[{\"x\":100,\"y\":-220},{\"x\":580,\"y\":20}]",
            Waypoints.toSite(read)
                .toString());
    }

    @Test
    void anEdgeKeepsItsDotsThroughTheSave() {
        final Edge e = new Edge(Uuid.randomUUID(), Uuid.randomUUID(), Uuid.randomUUID(), 1, 2);
        final JsonObject plain = Serializer.edgeToJson(e);
        assertFalse(plain.has("waypoints"), "an unsteered wire saves as it always did");
        e.waypoints = pts(105, 215, 405, 15);
        final Edge back = Serializer.jsonToEdge(Serializer.edgeToJson(e));
        assertEquals("(105,215)(405,15)", text(back.waypoints));
        assertEquals("", text(Serializer.jsonToEdge(plain).waypoints));
    }

    @Test
    void aDrawersLinkKeepsItsDotsAndIsStillTheSameLink() {
        final Uuid node = Uuid.randomUUID();
        final Drawer d = new Drawer(Drawer.Kind.PRODUCT, "item:x");
        final Drawer.Link link = new Drawer.Link(node, 0);
        d.addLink(link);
        assertTrue(d.setWaypoints(link, false, pts(55, 65)));
        final Drawer.Link steered = d.getLinks()
            .getFirst();
        assertEquals(link, steered, "dots are not part of what a link is");
        assertEquals("(55,65)", text(steered.waypoints()));
        assertTrue(d.isLinked(node, 0));
        // Saved and read back.
        final Drawer back = Serializer.readDrawer(Serializer.GSON.toJsonTree(d, Drawer.class));
        assertEquals(
            "(55,65)",
            text(
                back.getLinks()
                    .getFirst()
                    .waypoints()));
        // A link without dots saves without the field.
        assertFalse(
            Serializer.GSON.toJsonTree(new Drawer.Link(node, 1))
                .getAsJsonObject()
                .has("waypoints"));
        // Taken off, the link holds none; unlinked, they go with it.
        assertTrue(d.setWaypoints(link, false, List.of()));
        assertEquals(
            "",
            text(
                d.getLinks()
                    .getFirst()
                    .waypoints()));
        assertFalse(d.setWaypoints(new Drawer.Link(node, 5), false, pts(1, 1)), "a port it does not hold");
    }

    @Test
    void aDamagedSaveLosesOnlyTheDotsItCannotRead() {
        final JsonObject saved = new JsonParser()
            .parse(
                "{\"id\":\"" + Uuid.randomUUID()
                    + "\",\"kind\":\"PRODUCT\",\"links\":[{\"nodeId\":\""
                    + Uuid.randomUUID()
                    + "\",\"portIndex\":0,\"waypoints\":[[5,15],null,[7],[25,35]]}]}")
            .getAsJsonObject();
        final Drawer d = Serializer.readDrawer(saved);
        assertEquals(
            "(5,15)(25,35)",
            text(
                d.getLinks()
                    .getFirst()
                    .waypoints()));
    }

    @Test
    void anArrangeTakesDotsOffWhatItLaidOutAndMovesThoseInAGroupWithIt() {
        GtnhFlowLoader.ensureDefaultMachineProfile();
        final Graph g = new Graph("arranged");
        final Node a = new Node(Uuid.randomUUID(), 0, 0), b = new Node(Uuid.randomUUID(), 500, 0),
            c = new Node(Uuid.randomUUID(), 1000, 0);
        for (final Node n : List.of(a, b, c)) g.nodes.put(n.id, n);
        final BoardGroup group = new BoardGroup();
        g.boardGroups.put(group.getId(), group);
        a.group = group.getId();
        b.group = group.getId();
        final Edge inside = new Edge(Uuid.randomUUID(), a.id, b.id, 0, 0),
            across = new Edge(Uuid.randomUUID(), b.id, c.id, 0, 0);
        inside.waypoints = pts(305, 205);
        across.waypoints = pts(805, 405);
        g.edges.put(inside.id, inside);
        g.edges.put(across.id, across);
        final Drawer d = new Drawer(Drawer.Kind.PRODUCT, "item:x");
        g.drawers.put(d.getId(), d);
        d.addLink(new Drawer.Link(c.id, 0).withWaypoints(pts(1205, 5)));

        Waypoints.afterArrange(g, Map.of(group.getId(), new int[] { 40, -20 }));
        assertEquals("(345,185)", text(inside.waypoints), "inside the group: moved with it");
        assertEquals("", text(across.waypoints), "laid out again: gone");
        assertEquals(
            "",
            text(
                d.getLinks()
                    .getFirst()
                    .waypoints()));
    }

    @Test
    void aGroupOpenedOrShutTakesTheDotsOffWiresAtWhatItHolds() {
        GtnhFlowLoader.ensureDefaultMachineProfile();
        final Graph g = new Graph("groups");
        final Node a = new Node(Uuid.randomUUID(), 0, 0), b = new Node(Uuid.randomUUID(), 500, 0),
            c = new Node(Uuid.randomUUID(), 1000, 0);
        for (final Node n : List.of(a, b, c)) g.nodes.put(n.id, n);
        final Edge ab = new Edge(Uuid.randomUUID(), a.id, b.id, 0, 0),
            bc = new Edge(Uuid.randomUUID(), b.id, c.id, 0, 0);
        ab.waypoints = pts(305, 205);
        bc.waypoints = pts(805, 405);
        g.edges.put(ab.id, ab);
        g.edges.put(bc.id, bc);
        Waypoints.dropAt(g, Set.of(a.id));
        assertEquals("", text(ab.waypoints));
        assertNotEquals("", text(bc.waypoints), "a wire nowhere near it keeps them");
    }
}
