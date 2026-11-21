package com.gtnhplanner.cloud.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.conformance.Conformance;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.importer.FakeGame;
import com.gtnhplanner.importer.FfConverter;
import com.gtnhplanner.importer.FfImport;
import com.gtnhplanner.importer.FfPlan;
import com.gtnhplanner.library.PlanExport;
import com.gtnhplanner.power.CustomRate;
import com.gtnhplanner.power.FluidTank;

/**
 * A website plan's setting pins read from a file (import, paste, Open in the mod, a library download) as a shared plan
 * reads them (one reader, PlanPins.readPins), and written back exactly; and the empty custom rate card and fluid tank
 * coming back as the empty holders they are.
 */
class FilePinsTest {

    private static final String GENERATOR = "power:large-gas-turbine";

    /** A game that builds generators and names each recipe card's website machine by its machine. */
    private static TestGame game() throws ReflectiveOperationException {
        final TestGame game = new TestGame();
        final Field f = FakeGame.class.getDeclaredField("generators");
        f.setAccessible(true);
        f.setBoolean(game.fake, true);
        game.handlers = n -> n.isPower() || n.shown != null ? null : "handler:" + game.machine(n);
        return game;
    }

    /**
     * The biodiesel demo with a gas turbine, an empty custom rate card and an empty fluid tank added, and pins on one
     * of its machines (a website option and the coil), the turbine (two settings and a reading) and a machine the
     * board has no card of.
     */
    private static JsonObject plan(final TestGame game) throws IOException {
        final JsonObject p = new JsonParser().parse(Conformance.text("plans/website/biodiesel-demo.json"))
            .getAsJsonObject();
        p.getAsJsonArray("recipes")
            .add(
                new JsonParser().parse(
                    "{\"id\":\"r-gen\",\"name\":\"Large Gas Turbine\",\"kind\":\"custom\",\"category\":\"power-source\","
                        + "\"machineType\":\"Large Gas Turbine\",\"minimumTier\":\"NONE\",\"durationTicks\":20,\"eut\":0,"
                        + "\"inputs\":[],\"outputs\":[],\"power\":{\"sourceId\":\"large-gas-turbine\",\"euPerTick\":0,"
                        + "\"stats\":[]}}"));
        p.getAsJsonArray("recipes")
            .add(
                new JsonParser().parse(
                    "{\"id\":\"r-rate\",\"name\":\"Custom Rate\",\"kind\":\"custom\",\"category\":\"custom-rate\","
                        + "\"machineType\":\""
                        + FfPlan.CUSTOM_RATE_MACHINE
                        + "\",\"minimumTier\":\"NONE\","
                        + "\"durationTicks\":20,\"eut\":0,\"inputs\":[],\"outputs\":[]}"));
        p.getAsJsonArray("recipes")
            .add(
                new JsonParser().parse(
                    "{\"id\":\"r-tank\",\"name\":\"Tank\",\"kind\":\"custom\",\"machineType\":\"Tank\","
                        + "\"minimumTier\":\"NONE\",\"durationTicks\":1,\"eut\":0,\"inputs\":[],\"outputs\":[],"
                        + "\"metadata\":{\"recipeMapId\":\""
                        + FfPlan.TANK_MAP
                        + "\"}}"));
        for (final String n : new String[] {
            "{\"id\":\"n-gen\",\"recipeId\":\"r-gen\",\"machineCount\":2,\"parallel\":1,\"overclockTier\":\"LV\","
                + "\"enabled\":true,\"position\":{\"x\":-900,\"y\":400}}",
            "{\"id\":\"n-rate\",\"recipeId\":\"r-rate\",\"machineCount\":1,\"parallel\":1,\"overclockTier\":\"NONE\","
                + "\"enabled\":true,\"position\":{\"x\":-600,\"y\":400},\"customRate\":{\"perSecond\":2.5,"
                + "\"mode\":\"request\"}}",
            "{\"id\":\"n-tank\",\"recipeId\":\"r-tank\",\"machineCount\":0.05,\"parallel\":1,\"overclockTier\":\"NONE\","
                + "\"enabled\":true,\"position\":{\"x\":-300,\"y\":400}}" })
            p.getAsJsonArray("nodes")
                .add(new JsonParser().parse(n));
        // A machine of the plan, by the handler the game names its cards with.
        final Graph g = FfImport.importFromText(p.toString(), game.fake, game.fake)
            .graph();
        String handler = null;
        for (final Node n : g.getNodes()) if (handler == null) handler = game.handlers.apply(n);
        assertNotNull(handler, "a recipe card to pin");
        p.add(
            "settingPins",
            new JsonParser().parse(
                "{\"" + handler
                    + "\":[\"circuit\",\"heatingCoil\"],\""
                    + GENERATOR
                    + "\":[\"fuel\",\"customFlow\",\"reading:Efficiency\"],"
                    + "\"handler:elsewhere\":[\"mode\"]}"));
        return p;
    }

    @Test
    void aPlanFileShowsTheSamePinsAsTheSamePlanThroughTheCloud() throws IOException, ReflectiveOperationException {
        final TestGame game = game();
        final JsonObject plan = plan(game);
        final Graph file = FfImport.importFromText(plan.toString(), game.fake, game.fake, game)
            .graph();
        final Graph cloud = CloudPlans.read(plan, game)
            .graph();
        assertEquals(List.of("power:fuel", "power:customFlow", "reading:Efficiency"), file.settingPins.get(GENERATOR));
        assertEquals(List.of("mode"), file.settingPins.get("site:handler:elsewhere"));
        assertEquals(3, file.settingPins.size(), "the machine's pins too: " + file.settingPins);
        assertEquals(cloud.settingPins, file.settingPins, "the file and the cloud read the pins alike");
    }

    @Test
    void aPlanFilesPinsGoBackOutAsTheyCameAndAsTheBoardChangesThem() throws IOException, ReflectiveOperationException {
        final TestGame game = game();
        final JsonObject plan = plan(game);
        final FfConverter.Result r = FfImport.importFromText(plan.toString(), game.fake, game.fake, game);
        final Graph g = r.graph();
        game.knowing(g);
        final JsonObject in = plan.getAsJsonObject("settingPins");
        assertEquals(in, export(g, game).getAsJsonObject("settingPins"), "field for field");

        // Again from the export: still the same.
        final String again = export(g, game).toString();
        final Graph back = FfImport.importFromText(again, game.fake, game.fake, game)
            .graph();
        assertEquals(g.settingPins, back.settingPins);
        game.knowing(back);
        assertEquals(in, export(back, game).getAsJsonObject("settingPins"));

        // The player unpins the turbine's flow and every pin of the machine: they go out so; the machine the board
        // has no card of keeps its pins.
        g.settingPins.put(GENERATOR, new ArrayList<>(List.of("power:fuel", "reading:Efficiency")));
        String machine = null;
        for (final Map.Entry<String, ?> e : in.entrySet()) if (!e.getKey()
            .equals(GENERATOR)
            && !e.getKey()
                .equals("handler:elsewhere"))
            machine = e.getKey();
        g.settingPins.keySet()
            .removeIf(k -> k.startsWith("machine:"));
        final JsonObject out = export(g, game).getAsJsonObject("settingPins");
        assertEquals(
            "[\"fuel\",\"reading:Efficiency\"]",
            out.get(GENERATOR)
                .toString());
        assertEquals(
            "[\"mode\"]",
            out.get("handler:elsewhere")
                .toString());
        assertFalse(out.has(machine), "unpinned in game: " + out);
    }

    @Test
    void anEmptyCustomRateCardAndAnEmptyTankComeBackAsTheEmptyHolders()
        throws IOException, ReflectiveOperationException {
        final TestGame game = game();
        final JsonObject plan = plan(game);
        Graph g = FfImport.importFromText(plan.toString(), game.fake, game.fake, game)
            .graph();
        for (int trip = 0; trip < 2; trip++) {
            Node rate = null, tank = null;
            for (final Node n : g.getNodes()) {
                if (n.shown != null) continue; // a view-only card is not the holder
                if (CustomRate.is(n)) rate = n;
                if (FluidTank.is(n)) tank = n;
            }
            assertNotNull(rate, "the empty custom rate card, trip " + trip);
            assertNull(rate.powerSettings.get(CustomRate.RESOURCE), "holding nothing");
            assertFalse(CustomRate.supply(rate), "requesting, as it was set");
            assertEquals(2.5, CustomRate.perSecond(rate), 1e-9);
            assertTrue(rate.isMachineCountFixed(), "at its count");
            assertNotNull(tank, "the empty fluid tank, trip " + trip);
            assertEquals(Map.of(), tank.powerSettings, "holding nothing");
            game.knowing(g);
            g = FfImport.importFromText(export(g, game).toString(), game.fake, game.fake, game)
                .graph();
        }
    }

    private static JsonObject export(final Graph g, final TestGame game) {
        return PlanExport.project(g, "pins", game);
    }
}
