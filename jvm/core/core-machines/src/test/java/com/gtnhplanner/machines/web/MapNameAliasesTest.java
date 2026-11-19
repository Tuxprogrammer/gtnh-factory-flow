package com.gtnhplanner.machines.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Four multiblocks are each the only machine on their map, so the data lists no handler for them and their recipes
 * arrive under the map's name. The table answers to the map's name, and to the controller's name as the mod reads it
 * off the NEI catalyst (WebCards.handlerId matches a handler whose machine is the catalyst's table entry), so both
 * paths reach the same curated entry.
 */
class MapNameAliasesTest {

    @ParameterizedTest
    @CsvSource({ "gt.recipe.oremill, IsaMill Grinding Machine", "gt.recipe.flotationcell, Flotation Cell Regulator",
        "gt.recipe.cable, L.A.T.E.X.", "gt.recipe.industrialchisel, Industrial 3D Copying Machine" })
    void mapNameAndCatalystNameReachOneEntry(final String mapId, final String controller) {
        final Web.Recipe recipe = MapRecipes.recipe(mapId, 120, 200, 0);
        final MachineTable.Behaviour byController = MachineTable.behaviour(controller);
        assertNotNull(byController, controller);
        assertSame(byController, MachineTable.behaviour(recipe.machineType), recipe.machineType);
        // The card's one handler (the map's fallback) is the catalyst's entry: what WebCards.handlerId matches.
        final Web.Handler handler = RecipeRules.machineHandlers(recipe)
            .get(0);
        assertSame(byController, MachineTable.behaviour(handler.machineType));
        assertTrue(Power.isMultiblock(RecipeRules.applyHandler(recipe, MapRecipes.node(null, "EV", Map.of()))));
    }

    @ParameterizedTest
    @CsvSource({ "gt.recipe.oremill", "gt.recipe.flotationcell" })
    void isaMillAndFlotationOverclockPerfectly(final String mapId) {
        // MTEIsaMill and MTEFrothFlotationCell enablePerfectOverclock: an MV recipe on an EV hatch takes two perfect
        // steps, each a quarter of the time.
        final Web.Recipe recipe = MapRecipes.recipe(mapId, 120, 1600, 0);
        final Overclock.Stats s = Overclock.stats(recipe, MapRecipes.node(null, "EV", Map.of()));
        assertEquals(2, s.perfectOverclockSteps());
        assertEquals(100, s.durationTicks());
    }
}
