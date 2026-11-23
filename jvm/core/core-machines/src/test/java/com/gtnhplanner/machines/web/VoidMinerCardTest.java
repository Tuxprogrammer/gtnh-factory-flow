package com.gtnhplanner.machines.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.machines.VoidMiner;

/**
 * A Void Miner card in game: its recipe built from handlers.json as WebCards builds it (the game's recipe says 1 tick
 * and 0 EU/t), enriched as WebCards enriches it, then the shared machine maths. Without the map in handlers.json the
 * card skips all of this and runs at the game recipe's 1 tick and 0 EU/t.
 */
class VoidMinerCardTest {

    @Test
    void handlersJsonCarriesTheVoidMinerMap() {
        final HandlerData.MapEntry entry = HandlerData.map("gt.recipe.voidminer");
        assertNotNull(entry, "gt.recipe.voidminer in handlers.json");
        assertEquals(VoidMiner.MAP, entry.name);
        assertTrue(entry.handlersOnRecipes);
        final List<String> ids = entry.handlers.stream()
            .map(t -> t.id)
            .sorted()
            .toList();
        assertEquals(List.of("void-miner-i", "void-miner-ii", "void-miner-iii"), ids);
        for (final HandlerData.Template t : entry.handlers) {
            assertEquals("multiblock", t.kind, t.id);
            assertEquals(1, t.items.size(), t.id);
        }
    }

    @Test
    void aVoidMinerICardDraws30720EuOnATenTickCycle() {
        final Web.Recipe recipe = VoidMiner.enrich(MapRecipes.recipe("gt.recipe.voidminer", 0, 1, 0));
        assertEquals(3, recipe.machineHandlers.size());
        final NodeMath.Result r = NodeMath.compute(recipe, MapRecipes.node("void-miner-i", "LuV", Map.of()));
        assertEquals(10, r.overclock()
            .durationTicks());
        assertEquals(30720, r.overclock()
            .eut());
        final NodeMath.Result iii = NodeMath.compute(recipe, MapRecipes.node("void-miner-iii", "UV", Map.of()));
        assertEquals(10, iii.overclock()
            .durationTicks());
        assertEquals(491520, iii.overclock()
            .eut());
    }
}
