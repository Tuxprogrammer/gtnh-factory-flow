package com.gtnhplanner.data.flowchart.balancer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.core.model.PortType;
import com.gtnhplanner.core.model.ResourceKey;
import com.gtnhplanner.data.MachineProfile;
import com.gtnhplanner.data.MachineProfileRegistry;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.data.properties.RecipeProperty;

/** The solver's input from a plan, built in the core with no host: ports numbered by resource, wires as edges. */
class GraphSolveCoreTest {

    /** A port value that is its resource key. */
    private static final PortType<ResourceKey> KEY = new PortType<>() {

        @Override
        public String getKey() {
            return "key";
        }

        @Override
        public ResourceKey resource(final ResourceKey value) {
            return value;
        }

        @Override
        public String key(final ResourceKey value) {
            return value.toString();
        }

        @Override
        public int extractAmount(final ResourceKey value) {
            return 1;
        }

        @Override
        public void setAmount(final ResourceKey value, final int amount) {}

        @Override
        public ResourceKey copy(final ResourceKey value) {
            return value;
        }

        @Override
        public String formatDisplayName(final ResourceKey value) {
            return value.id();
        }

        @Override
        public String formatAmount(final float perSecond) {
            return "";
        }

        @Override
        public int color(final ResourceKey value) {
            return -1;
        }

        @Override
        public int getPinInputColor() {
            return 0;
        }

        @Override
        public int getPinOutputColor() {
            return 0;
        }

        @Override
        public int getArrowColor() {
            return 0;
        }

        @Override
        public boolean same(final ResourceKey a, final ResourceKey b) {
            return a.equals(b);
        }

        @Override
        public boolean folds(final ResourceKey a, final ResourceKey b) {
            return a.equals(b);
        }
    };

    @BeforeAll
    static void defaultProfile() {
        if (MachineProfileRegistry.get(MachineProfileRegistry.defaultId()) == null) MachineProfileRegistry
            .register(MachineProfile.builder(MachineProfileRegistry.defaultId(), "Default").build());
    }

    private static Node card(final long n, final String in, final String out) {
        final Node node = new Node(new Uuid(0, n), 0, 0);
        node.machineName = "card " + n;
        if (in != null) node.inputs.add(new Port<>(KEY, ResourceKey.parse(in), 1f));
        if (out != null) node.outputs.add(new Port<>(KEY, ResourceKey.parse(out), 1f));
        node.properties.put(RecipeProperty.DURATION_TICKS, 20);
        return node;
    }

    @Test
    void wiredPortsShareAResourceNumber() {
        final Graph g = new Graph("t");
        final Node a = card(1, null, "item:minecraft:stick"), b = card(2, "item:minecraft:stick", "item:minecraft:torch"),
            c = card(3, "fluid:water", null);
        g.addNode(a);
        g.addNode(b);
        g.addNode(c);
        g.addEdge(new Edge(new Uuid(1, 1), a.id, b.id, 0, 0));
        final SolveInput input = GraphSolve.input(g);
        assertEquals(3, input.machines().size());
        assertEquals(1, input.edges().size());
        final SolveInput.Machine ma = input.machines().get(0), mb = input.machines().get(1), mc = input.machines().get(2);
        assertEquals(a.id, ma.id(), "machines in plan (id) order");
        assertEquals(20, ma.durationTicks());
        assertEquals(ma.outputs().get(0).resource(), mb.inputs().get(0).resource());
        assertNotEquals(mb.outputs().get(0).resource(), mc.inputs().get(0).resource());
        assertNotEquals(ma.outputs().get(0).resource(), mc.inputs().get(0).resource());
    }
}
