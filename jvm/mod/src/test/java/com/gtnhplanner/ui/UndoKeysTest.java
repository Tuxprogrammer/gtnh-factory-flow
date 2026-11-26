package com.gtnhplanner.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

import org.junit.jupiter.api.Test;
import org.lwjgl.input.Keyboard;

import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.UndoHistories;
import com.gtnhplanner.data.flowchart.UndoHistory;
import com.gtnhplanner.harness.GtnhFlowLoader;
import com.gtnhplanner.nei.NEIPlanConfig;
import com.gtnhplanner.ui.group.GroupEdits;

import codechicken.nei.NEIClientUtils;

/** Ctrl+Z undoes and Ctrl+Shift+Z or Ctrl+Y redo, read the way NEI keeps the bindings. */
class UndoKeysTest {

    /** The keys the shipped NEI binds (modifiers dropped, as Controls shows them: Z, Z, Y). */
    private static final Map<String, Integer> BOUND = Map.of(
        NEIPlanConfig.ConfigUndoKey.KEY,
        Keyboard.KEY_Z,
        NEIPlanConfig.ConfigRedoKey.KEY,
        Keyboard.KEY_Z,
        NEIPlanConfig.ConfigRedoAltKey.KEY,
        Keyboard.KEY_Y);

    /** NEI's {@code KeyManager.isHashDown(name, modifiers)}: the bound key down, and exactly those modifiers held. */
    private static BiPredicate<String, Integer> held(final int key, final int modifiers) {
        return (name, wanted) -> BOUND.get(name) == key && wanted == modifiers;
    }

    private static final int CTRL = NEIClientUtils.CTRL_HASH, SHIFT = NEIClientUtils.SHIFT_HASH;

    @Test
    void ctrlZUndoes() {
        assertEquals(UndoKeys.Act.UNDO, UndoKeys.read(held(Keyboard.KEY_Z, CTRL)));
    }

    @Test
    void ctrlShiftZAndCtrlYRedo() {
        assertEquals(UndoKeys.Act.REDO, UndoKeys.read(held(Keyboard.KEY_Z, CTRL | SHIFT)));
        assertEquals(UndoKeys.Act.REDO, UndoKeys.read(held(Keyboard.KEY_Y, CTRL)));
    }

    @Test
    void bareKeysDoNothing() {
        assertEquals(UndoKeys.Act.NONE, UndoKeys.read(held(Keyboard.KEY_Z, 0)), "Z alone is typing");
        assertEquals(UndoKeys.Act.NONE, UndoKeys.read(held(Keyboard.KEY_Y, 0)));
        assertEquals(UndoKeys.Act.NONE, UndoKeys.read(held(Keyboard.KEY_Z, SHIFT)));
        assertEquals(UndoKeys.Act.NONE, UndoKeys.read(held(Keyboard.KEY_A, CTRL)));
    }

    /** A plan's every card selected and deleted in one step comes back whole with one undo. */
    @Test
    void deletingEverythingUndoesInOneStep() {
        final UndoHistories histories = new UndoHistories();
        final Graph plan = GtnhFlowLoader.load("jet_fuel")
            .graph();
        final int cards = plan.getNodes()
            .size();
        final int wires = plan.getEdges()
            .size();
        final List<Uuid> all = new ArrayList<>();
        for (final Node n : plan.getNodes()) all.add(n.id);
        all.addAll(plan.drawers.keySet());

        final UndoHistory history = histories.of(plan);
        final String before = history.beginEdit(plan);
        GroupEdits.removeAll(plan, DeleteAsk.takes(plan, all, List::of));
        history.commitEdit(before, plan);
        assertEquals(
            0,
            plan.getNodes()
                .size());

        final Graph back = histories.of(plan)
            .undo(plan);
        assertEquals(
            cards,
            back.getNodes()
                .size());
        assertEquals(
            wires,
            back.getEdges()
                .size());
    }

    @Test
    void theKeyComesFromThePressNotFromWhatIsDown() {
        final int ctrl = NEIClientUtils.CTRL_HASH, shift = NEIClientUtils.SHIFT_HASH;
        // Z pressed with Ctrl held: undo, also when NEI kept Ctrl hashed into the binding.
        assertTrue(UndoKeys.pressed(Keyboard.KEY_Z, Keyboard.KEY_Z, ctrl, true, false, false));
        assertTrue(UndoKeys.pressed(Keyboard.KEY_Z, Keyboard.KEY_Z | ctrl, ctrl, true, false, false));
        // Ctrl's own press, read in the frame Z went down too: not a press of Z, so it does not undo a second time.
        assertFalse(UndoKeys.pressed(Keyboard.KEY_LCONTROL, Keyboard.KEY_Z, ctrl, true, false, false));
        // A bare Z, and Ctrl+Shift+Z for the undo binding: no.
        assertFalse(UndoKeys.pressed(Keyboard.KEY_Z, Keyboard.KEY_Z, ctrl, false, false, false));
        assertFalse(UndoKeys.pressed(Keyboard.KEY_Z, Keyboard.KEY_Z, ctrl, true, true, false));
        // Ctrl+Shift+Z for the redo binding; a key event with no key never matches.
        assertTrue(UndoKeys.pressed(Keyboard.KEY_Z, Keyboard.KEY_Z, ctrl | shift, true, true, false));
        assertFalse(UndoKeys.pressed(0, 0, 0, false, false, false));
    }
}
