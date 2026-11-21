package com.gtnhplanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.flowchart.Serializer;
import com.gtnhplanner.data.flowchart.balancer.ChoiceKey;
import com.gtnhplanner.data.flowchart.balancer.PortRef;

/**
 * The plan serializer writes and reads the core's id exactly as Gson wrote and read the {@code java.util.UUID} the
 * mod's plans were saved with: as a field, in a list and as a map key, so a save written before the model took the
 * core's id reads the same and writes back byte for byte.
 */
class CoreIdsTest {

    private static final String TEXT = "9a3f0c2e-51d4-4b7a-8e21-c0ffee000042";
    private static final Uuid NODE = Uuid.fromString(TEXT);
    private static final UUID OLD = UUID.fromString(TEXT);

    /** The plan serializer as it was before, with Gson's own UUID handling. */
    private static final Gson BEFORE = new GsonBuilder().setPrettyPrinting()
        .enableComplexMapKeySerialization()
        .create();

    @Test
    void anIdIsWrittenAsGsonWroteAJavaUuid() {
        assertEquals(BEFORE.toJson(OLD), Serializer.GSON.toJson(NODE));
        assertEquals(BEFORE.toJson(List.of(OLD)), Serializer.GSON.toJson(List.of(NODE)));
        final SortedMap<UUID, Integer> oldMap = new TreeMap<>(Map.of(OLD, 1));
        final SortedMap<Uuid, Integer> newMap = new TreeMap<>(Map.of(NODE, 1));
        assertEquals(BEFORE.toJson(oldMap), Serializer.GSON.toJson(newMap), "as a map key too");
        final SortedMap<Uuid, Integer> read = Serializer.GSON
            .fromJson(BEFORE.toJson(oldMap), new TypeToken<TreeMap<Uuid, Integer>>() {}.getType());
        assertEquals(newMap, read);
    }

    @Test
    void aSavedExcessChoiceKeepsItsShape() {
        final ChoiceKey choice = ChoiceKey.of(List.of(new PortRef(NODE, 3, true)));
        final String written = Serializer.GSON.toJson(choice);
        assertEquals(
            new JsonParser().parse("{\"gateAnchors\":[{\"nodeId\":\"" + TEXT + "\",\"portIndex\":3,\"input\":true}]}"),
            new JsonParser().parse(written));
        assertEquals(NODE, Serializer.GSON.fromJson("\"" + TEXT + "\"", Uuid.class));
        assertNull(Serializer.GSON.fromJson("null", Uuid.class));
    }
}
