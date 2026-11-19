package com.gtnhplanner.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.flowchart.BoardGroup;
import com.gtnhplanner.data.flowchart.Group;
import com.gtnhplanner.data.flowchart.MachineGroup;
import com.gtnhplanner.data.flowchart.Note;
import com.gtnhplanner.data.flowchart.PinRule;
import com.gtnhplanner.data.flowchart.balancer.ChoiceKey;
import com.gtnhplanner.data.flowchart.balancer.PortRef;

/** The plan model's pieces that live in the core, with no host installed. */
class ModelCoreTest {

    /** A card as the rule sees it: the mod's Node implements the same. */
    private static final class Card implements PinRule.Card {

        boolean fixed, exact, alwaysExact;
        int count;

        @Override
        public boolean isMachineCountFixed() {
            return fixed;
        }

        @Override
        public boolean isMachineCountExact() {
            return exact && fixed;
        }

        @Override
        public void setMachineCountFixed(final boolean fixed) {
            this.fixed = fixed;
            if (!fixed) exact = false;
        }

        @Override
        public void setMachineCountExact(final boolean exact) {
            this.exact = exact;
        }

        @Override
        public boolean countAlwaysExact() {
            return alwaysExact;
        }

        @Override
        public void setMachineCount(final int count) {
            this.count = count;
        }
    }

    @Test
    void aCardsRuleIsReadAndSetThroughTheSeam() {
        final Card c = new Card();
        assertEquals(PinRule.ANY, PinRule.of(c));
        PinRule.set(c, PinRule.UP_TO, 2.6);
        assertEquals(3, c.count, "a count is whole machines, rounded");
        assertEquals(PinRule.UP_TO, PinRule.of(c));
        PinRule.set(c, PinRule.EXACT);
        assertEquals(PinRule.EXACT, PinRule.of(c));
        PinRule.set(c, PinRule.ANY, 5);
        assertEquals(PinRule.ANY, PinRule.of(c));
        assertEquals(3, c.count, "Any lets go of the count, keeps the number");
        c.setMachineCountFixed(true);
        c.alwaysExact = true;
        assertEquals(PinRule.EXACT, PinRule.of(c), "a custom rate card's set count is Exactly");
        PinRule.set(c, PinRule.UP_TO, Double.NaN);
        assertEquals(PinRule.ANY, PinRule.of(c), "a count that is not a positive number means Any");
    }

    @Test
    void aSharedMachinesRuleLivesOnItsGroup() {
        final MachineGroup g = new MachineGroup(Uuid.fromString("00000000-0000-0000-0000-000000000001"));
        assertEquals(PinRule.ANY, PinRule.of(g));
        PinRule.set(g, PinRule.EXACT, 4);
        assertEquals(4, g.getMachineCapacity());
        assertEquals(PinRule.EXACT, PinRule.of(g));
        PinRule.set(g, PinRule.ANY, 4);
        assertEquals(0, g.getMachineCapacity());
        assertFalse(g.isPinned());
        assertFalse(g.isExact());
    }

    @Test
    void boardThingsAreNamedAfterTheirType() {
        assertEquals("Note", new Note().getHeader());
        assertEquals("Group", new Group().getHeader());
        assertEquals("Machine Group", new MachineGroup().getHeader());
        assertEquals("machine_group", new MachineGroup().getType());
        final MachineGroup shared = new MachineGroup();
        final Uuid a = Uuid.nameUUIDFromBytes(new byte[] { 1 }), b = Uuid.nameUUIDFromBytes(new byte[] { 2 });
        shared.addSection(a);
        assertFalse(shared.isShared());
        shared.addSection(b);
        assertTrue(shared.isShared());
        assertEquals(List.of(a, b), shared.getSections());
        assertTrue(shared.getNodeIds().contains(b));
    }

    @Test
    void aGroupMadeWithAnIdIsColouredByIt() {
        final Uuid id = Uuid.fromString("123e4567-e89b-12d3-a456-426614174000");
        assertEquals(new MachineGroup(id).getColor(), new MachineGroup(id).getColor());
        assertEquals(0x7F000000, new MachineGroup(id).getColor() & 0xFF000000, "half opaque");
    }

    @Test
    void aBoardGroupsPaperAndColourFollowTheWebsite() {
        final BoardGroup g = new BoardGroup(Uuid.fromString("00000000-0000-0000-0000-000000000002"));
        assertEquals("pocket-00000000-0000-0000-0000-000000000002", g.webId());
        assertEquals(BoardGroup.themeColor(BoardGroup.paperForId(g.webId())), g.colorTag());
        g.setColor("lime");
        assertEquals("lime", g.colorTag());
        assertEquals("orange", BoardGroup.newColor(List.of("light_blue", "lime")));
    }

    @Test
    void withNoHostAnEditRunsAndOnePickedAnswerIsKept() {
        final PlanHost<String> host = PlanHost.get();
        assertSame(PlanHost.NONE, host);
        final boolean[] ran = { false };
        host.recordEdit("plan", () -> ran[0] = true);
        assertTrue(ran[0]);
        assertNull(host.excessChoice("plan"));
        final ChoiceKey pick = ChoiceKey.of(List.of(new PortRef(Uuid.nameUUIDFromBytes(new byte[] { 3 }), 0, false)));
        host.setExcessChoice("plan", pick);
        assertEquals(pick, host.excessChoice("other plan"), "one pick for every plan, as the mod keeps it");
        host.setExcessChoice("plan", null);
        host.save();
    }
}
