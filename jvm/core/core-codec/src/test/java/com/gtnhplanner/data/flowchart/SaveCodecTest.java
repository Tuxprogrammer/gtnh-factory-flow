package com.gtnhplanner.data.flowchart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.base.JsonTree;
import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.flowchart.balancer.ChoiceKey;
import com.gtnhplanner.data.flowchart.balancer.PortRef;

/** The save's JSON of notes, groups, board groups and drawers, written and read back (the mod checks it against Gson). */
class SaveCodecTest {

    private static final Uuid A = Uuid.fromString("00000000-0000-4000-8000-00000000000a"),
        B = Uuid.fromString("00000000-0000-4000-8000-00000000000b");

    /** Through text and back, as a save does. */
    private static Map<String, Object> trip(final Map<String, Object> tree) {
        return JsonTree.object(JsonTree.parse(JsonTree.write(tree)));
    }

    @Test
    void aNoteKeepsEveryField() {
        final Note n = new Note(A);
        n.text = new ArrayList<>(List.of("one", "two"));
        n.kind = Note.ARROW;
        n.points = new ArrayList<>(List.of(new int[] { 1, 2 }, new int[] { 3, 4 }));
        n.arrowWidth = 4;
        n.fillColor = "lime";
        n.group = B;
        n.x = 10;
        n.y = -20;
        final Map<String, Object> saved = SaveCodec.note(n);
        assertEquals(
            List.of(
                "text",
                "width",
                "height",
                "color",
                "fontSize",
                "kind",
                "points",
                "arrowWidth",
                "fillColor",
                "group",
                "id",
                "x",
                "y",
                "header",
                "type"),
            new ArrayList<>(saved.keySet()),
            "its own fields first, then the board thing's, nulls left out");
        final Note back = SaveCodec.readNote(trip(saved));
        assertEquals(JsonTree.write(saved), JsonTree.write(SaveCodec.note(back)));
        assertEquals(A, back.getId());
    }

    @Test
    void aMachineGroupKeepsItsCountRuleAndSections() {
        final MachineGroup m = new MachineGroup(A);
        m.setMachineCount(2.5);
        m.setPinned(true);
        m.setExact(true);
        m.addSection(A);
        m.addSection(B);
        final Map<String, Object> saved = SaveCodec.group(m);
        assertEquals("machineCapacity", saved.keySet()
            .iterator()
            .next());
        final Group back = SaveCodec.readGroup(trip(saved));
        assertTrue(back instanceof MachineGroup);
        assertEquals(2.5, ((MachineGroup) back).machineCount());
        assertTrue(((MachineGroup) back).isExact());
        assertEquals(JsonTree.write(saved), JsonTree.write(SaveCodec.group(back)));
        assertThrows(IllegalArgumentException.class, () -> SaveCodec.readGroup(trip(SaveCodec.note(new Note()))));
    }

    @Test
    void aDrawerKeepsItsLinksAndTheirDots() {
        final Drawer d = new Drawer(A, DrawerTypes.Kind.BUFFER, "item:minecraft:iron_ingot:0");
        d.links = new ArrayList<>(List.of(new Drawer.Link(B, 1).withWaypoints(List.of(new int[] { 10, 20 }))));
        d.takers = new ArrayList<>(List.of(new Drawer.Link(B, 0)));
        final Map<String, Object> saved = SaveCodec.drawer(d);
        final Drawer back = SaveCodec.readDrawer(trip(saved));
        assertEquals(JsonTree.write(saved), JsonTree.write(SaveCodec.drawer(back)));
        assertEquals(
            20,
            back.getLinks()
                .getFirst()
                .waypoints()
                .getFirst()[1]);
        assertNull(SaveCodec.readDrawer(JsonTree.object(JsonTree.parse("{\"id\":null}"))), "a null id: no drawer");
        final Drawer odd = SaveCodec.readDrawer(JsonTree.object(JsonTree.parse("{\"id\":\"" + A + "\",\"kind\":\"NEW\"}")));
        assertEquals(DrawerTypes.Kind.PRODUCT, odd.getKind(), "an unknown kind is repaired to a product");
    }

    @Test
    void aBoardGroupAndAChoiceComeBack() {
        final BoardGroup b = new BoardGroup(A);
        b.parent = B;
        b.theme = "kraft";
        final Map<String, Object> saved = SaveCodec.boardGroup(b);
        assertEquals(JsonTree.write(saved), JsonTree.write(SaveCodec.boardGroup(SaveCodec.readBoardGroup(trip(saved)))));
        final ChoiceKey k = ChoiceKey.of(List.of(new PortRef(A, 2, true), new PortRef(B, 0, false)));
        assertEquals(k, SaveCodec.readChoiceKey(trip(SaveCodec.choiceKey(k))));
    }
}
