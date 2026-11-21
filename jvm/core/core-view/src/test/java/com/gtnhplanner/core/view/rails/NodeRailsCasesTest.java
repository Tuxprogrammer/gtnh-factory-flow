package com.gtnhplanner.core.view.rails;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.view.corpus.CorpusTest;

/** The card's rail rows and verdict against the website's: {@code conformance/view/rails/cases.json}. */
class NodeRailsCasesTest extends CorpusTest<NodeRailsInput> {

    NodeRailsCasesTest() {
        super("rails", "cases", NodeRailsInput.class, 19);
    }

    @Override
    protected Class<?> viewRecord() {
        return NodeRailsView.class;
    }

    @Override
    protected String render(final NodeRailsInput in) {
        return NodeRails.toJson(NodeRails.build(in));
    }

    private static NodeRailsInput.Side side(final List<NodeRailsInput.Slot> slots, final List<NodeRailsInput.Flow> flows,
        final List<NodeRailsInput.Wire> wires) {
        return new NodeRailsInput.Side(slots, flows, wires);
    }

    private static NodeRailsInput.Slot slot(final String id, final boolean consumed) {
        return new NodeRailsInput.Slot("item:" + id, "item", id, id, null, "input:item:" + id, consumed, null, null, 0);
    }

    private static NodeRailsInput.Flow flow(final String id, final double rate) {
        return new NodeRailsInput.Flow("item:" + id, "item", id, id, null, "input:item:" + id, rate);
    }

    private static NodeRailsInput of(final boolean solveMode, final NodeRailsInput.Side inputs, final NodeRailsInput.Side outputs) {
        return new NodeRailsInput(true, true, false, solveMode, true, 1, 0, inputs, outputs);
    }

    @Test
    void anInputTheRecipeKeepsIsNoRowEvenWhenTheSolveListsIt() {
        final NodeRailsView v = NodeRails.build(
            of(true, side(List.of(slot("circuit", false), slot("plate", true)),
                List.of(flow("circuit", 1), flow("plate", 2)), List.of(new NodeRailsInput.Wire("item:plate", 2))),
                side(List.of(), List.of(), List.of())));
        Assertions.assertEquals(1, v.inputs().size());
        Assertions.assertEquals("item:plate", v.inputs().get(0).key());
    }

    @Test
    void aWiredFlowAtNothingKeepsItsRowAndAnUnwiredOneLosesIt() {
        final NodeRailsView v = NodeRails.build(
            new NodeRailsInput(true, true, false, true, true, 0, 0,
                side(List.of(slot("a", true), slot("b", true)), List.of(flow("a", 0), flow("b", 0)),
                    List.of(new NodeRailsInput.Wire("item:a", 0))),
                side(List.of(), List.of(), List.of())));
        Assertions.assertEquals("demand-set", v.verdict());
        Assertions.assertEquals(1, v.inputs().size());
        Assertions.assertEquals("calm", v.inputs().get(0).tone());
    }

    @Test
    void theLavaIsAnInertRowOnlyOnceThereAreBooks() {
        final NodeRailsInput.Slot lava = slot("gregtech:gt.metaitem.02@32765", true);
        final NodeRailsInput.Side withBooks = side(List.of(lava), List.of(), List.of());
        final NodeRailsView solved = NodeRails.build(new NodeRailsInput(true, true, false, true, true, 1, 0, withBooks,
            side(List.of(), List.of(), List.of())));
        Assertions.assertEquals(1, solved.inputs().size());
        Assertions.assertTrue(solved.inputs().get(0).free());
        final NodeRailsView first = NodeRails.build(new NodeRailsInput(true, false, false, true, true, 0, 0, withBooks,
            side(List.of(), List.of(), List.of())));
        Assertions.assertEquals("no-recipe", first.verdict());
        Assertions.assertEquals(0, first.inputs().size());
    }

    @Test
    void aPlanNotSolvedLeavesTheRowsToTheHost() {
        final NodeRailsView v = NodeRails.build(
            of(false, side(List.of(slot("a", true)), List.of(flow("a", 1)), List.of(new NodeRailsInput.Wire("item:a", 1))),
                side(List.of(), List.of(), List.of())));
        Assertions.assertEquals("legacy", v.verdict());
        Assertions.assertTrue(v.inputs().isEmpty());
    }

    @Test
    void energyPerUnitIsNothingWhereNothingFlows() {
        Assertions.assertNull(NodeRails.energyPerUnit(30, 0));
        Assertions.assertNull(NodeRails.energyPerUnit(Double.NaN, 1));
        Assertions.assertEquals(1200.0, NodeRails.energyPerUnit(120, 2));
        Assertions.assertEquals(0.0, NodeRails.energyPerUnit(-5, 2));
    }
}
