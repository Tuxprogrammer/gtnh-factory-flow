package com.gtnhplanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;
import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.flowchart.Serializer;
import com.gtnhplanner.data.flowchart.Summary;
import com.gtnhplanner.data.flowchart.balancer.ChoiceKey;
import com.gtnhplanner.data.flowchart.balancer.PortRef;

/**
 * The mod's ids and the core's cross with nothing lost, and the plan serializer writes a core id exactly as it wrote
 * the mod's: a saved excess choice (its ports name their nodes) reads and writes as before the solver moved.
 */
class CoreIdsTest {

    private static final UUID NODE = UUID.fromString("9a3f0c2e-51d4-4b7a-8e21-c0ffee000042");

    @Test
    void idsCrossBothWaysUnchanged() {
        final Uuid core = CoreIds.core(NODE);
        assertEquals(NODE.toString(), core.toString());
        assertEquals(NODE.hashCode(), core.hashCode());
        assertEquals(NODE, CoreIds.mod(core));
        assertEquals(List.of(NODE), CoreIds.modList(CoreIds.coreList(List.of(NODE))));
        assertNull(CoreIds.core(null));
        assertNull(CoreIds.mod(null));
    }

    @Test
    void aSavedExcessChoiceKeepsItsShape() {
        final ChoiceKey choice = ChoiceKey.of(List.of(new PortRef(CoreIds.core(NODE), 3, true)));
        final String written = Serializer.GSON.toJson(choice);
        assertEquals(
            new JsonParser().parse("{\"gateAnchors\":[{\"nodeId\":\"" + NODE + "\",\"portIndex\":3,\"input\":true}]}"),
            new JsonParser().parse(written));
        assertEquals(choice, Serializer.GSON.fromJson(written, ChoiceKey.class));
        final Summary summary = new Summary();
        summary.setExcessChoice(choice);
        assertEquals(
            choice,
            Serializer.GSON.fromJson(Serializer.GSON.toJson(summary), Summary.class)
                .getExcessChoice());
    }
}
