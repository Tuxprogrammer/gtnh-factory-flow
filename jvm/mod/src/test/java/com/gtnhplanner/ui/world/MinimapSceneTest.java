package com.gtnhplanner.ui.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.core.view.minimap.MinimapView;
import com.gtnhplanner.ui.theme.Fmt;

/**
 * The map in the world draws from the core's scene (conformance/view/minimap): the groups first, then the drawers and
 * cards, and each wire lifted a fifth towards white as the website's map draws it.
 */
class MinimapSceneTest {

    private static PlanSnapshot snapshot(final List<PlanSnapshot.Line> wires,
        final List<PlanSnapshot.GroupBox> groups) {
        return new PlanSnapshot(
            null,
            "Plan",
            1,
            List.of(),
            List.of(),
            wires,
            0,
            0,
            Fmt.RateUnit.SECOND,
            List.of(),
            groups);
    }

    @Test
    void wiresAreLiftedAndKeepTheirPlace() {
        final PlanSnapshot.Line flowing = new PlanSnapshot.Line(
            List.of(new int[] { 0, 0 }, new int[] { 40, 0 }),
            0xFF102030,
            3,
            true,
            "water",
            null,
            null);
        final PlanSnapshot.Line idle = new PlanSnapshot.Line(
            List.of(new int[] { 0, 9 }, new int[] { 40, 9 }),
            0xFFFFFFFF,
            5,
            false,
            "water",
            null,
            null);
        final MinimapView scene = snapshot(List.of(flowing, idle), List.of()).scene();
        assertEquals(
            2,
            scene.wires()
                .size());
        assertEquals(
            "0",
            scene.wires()
                .get(0)
                .id());
        assertEquals(
            "#404d59",
            scene.wires()
                .get(0)
                .color());
        assertEquals(
            "#ffffff",
            scene.wires()
                .get(1)
                .color());
        assertEquals(
            5,
            scene.wires()
                .get(1)
                .width());
        assertEquals(
            false,
            scene.wires()
                .get(1)
                .flowing());
        assertNull(scene.bounds());
    }

    @Test
    void groupsAreTilesInTheirColour() {
        final MinimapView scene = snapshot(
            List.of(),
            List.of(
                new PlanSnapshot.GroupBox(Uuid.randomUUID(), 10, 20, 900, 500, 0xFF3B82F6, false),
                new PlanSnapshot.GroupBox(Uuid.randomUUID(), 1000, 20, 320, 120, 0xFF10B981, true))).scene();
        assertEquals(
            List.of("board", "group"),
            scene.tiles()
                .stream()
                .map(MinimapView.Tile::kind)
                .toList());
        assertEquals(
            "#3B82F6",
            scene.tiles()
                .get(0)
                .tint());
        assertEquals(
            "#10B981",
            scene.tiles()
                .get(1)
                .tint());
        assertEquals(
            10,
            scene.bounds()
                .x0());
        assertEquals(
            1320,
            scene.bounds()
                .x1());
    }
}
