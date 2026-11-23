package com.gtnhplanner.core.machines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.machines.web.NodeMath;
import com.gtnhplanner.machines.web.Web;

/** The Void Miners as the website's void-miner.ts has them (MTEVoidMinerBase): 10-tick cycles, tier x gas ores. */
class VoidMinerTest {

    private static Web.Handler handler(final String id, final String label) {
        final Web.Handler h = new Web.Handler();
        h.id = id;
        h.label = label;
        h.kind = "multiblock";
        h.machineType = label;
        h.minimumTier = "ULV";
        return h;
    }

    private static Web.Resource ore(final String id, final double chance) {
        final Web.Resource r = new Web.Resource();
        r.kind = "item";
        r.id = id;
        r.amount = 1;
        r.chance = chance;
        return r;
    }

    /** One dimension as the dataset carries it: 1 tick, 0 EU, handlers III, I, II, shares summing to 0.993. */
    private static Web.Recipe dimension() {
        final Web.Recipe r = new Web.Recipe();
        r.id = "void-miner-test";
        r.name = "Void Miner: Fayalite Ore";
        r.machineType = VoidMiner.MAP;
        r.minimumTier = "ULV";
        r.durationTicks = 1;
        r.eut = 0;
        r.source = new Web.Source();
        r.source.recipeMap = VoidMiner.MAP;
        final Web.Resource marker = new Web.Resource();
        marker.kind = "item";
        marker.id = "gtneioreplugin:blockdimensiondisplay_rb";
        marker.amount = 1;
        marker.consumed = false;
        r.inputs = List.of(marker);
        r.outputs = List.of(ore("ore:a", 0.5), ore("ore:b", 0.293), ore("ore:c", 0.2));
        r.machineHandlers = new ArrayList<>(
            List.of(
                handler("void-miner-iii", "Void Miner III"),
                handler("void-miner-i", "Void Miner I"),
                handler("void-miner-ii", "Void Miner II")));
        return r;
    }

    private static Web.Node card(final String handler, final String tier, final String gas) {
        final Web.Node n = new Web.Node();
        n.machineHandlerId = handler;
        n.overclockTier = tier;
        n.hatchVoltageTier = tier;
        n.hatchAmps = 1.0;
        n.machineConfigTiers = gas == null ? Map.of() : Map.of(VoidMiner.NOBLE_GAS, gas);
        return n;
    }

    /** Ores a second over the whole pool. */
    private static double ores(final Web.Recipe recipe, final NodeMath.Result r) {
        double total = 0;
        for (int i = 0; i < recipe.outputs.size(); i++) total += recipe.outputs.get(i).chance * r.outputMultipliers()
            .get(i);
        return total * 20 / r.overclock()
            .durationTicks();
    }

    @Test
    void eachControllerDrawsItsOwnPowerOnATenTickCycle() {
        final Web.Recipe recipe = VoidMiner.enrich(dimension());
        assertEquals("void-miner-i", recipe.machineHandlers.get(0).id);
        assertEquals("LuV", recipe.minimumTier);
        assertEquals(30720, recipe.eut);
        assertNull(recipe.runtimeCalculation);
        final String[][] cases = { { "void-miner-i", "LuV", "30720" }, { "void-miner-ii", "ZPM", "122880" },
            { "void-miner-iii", "UV", "491520" } };
        for (final String[] c : cases) {
            final NodeMath.Result r = NodeMath.compute(recipe, card(c[0], c[1], null));
            assertEquals(10, r.overclock()
                .durationTicks(), c[0]);
            assertEquals(Double.parseDouble(c[2]), r.overclock()
                .eut(), c[0]);
        }
        // A stronger hatch buys nothing.
        assertEquals(30720, NodeMath.compute(recipe, card("void-miner-i", "UHV", null))
            .overclock()
            .eut());
    }

    @Test
    void makesTierTimesGasOresACycle() {
        final Web.Recipe recipe = VoidMiner.enrich(dimension());
        assertEquals(0.5 / 0.993, recipe.outputs.get(0).chance, 1e-12);
        assertEquals(2, ores(recipe, NodeMath.compute(recipe, card("void-miner-i", "LuV", null))), 1e-9);
        assertEquals(6, ores(recipe, NodeMath.compute(recipe, card("void-miner-iii", "UV", null))), 1e-9);
        assertEquals(8, ores(recipe, NodeMath.compute(recipe, card("void-miner-i", "LuV", "neon"))), 1e-9);
        assertEquals(384, ores(recipe, NodeMath.compute(recipe, card("void-miner-iii", "UV", "oganesson"))), 1e-9);
    }

    @Test
    void aBoostedCycleUsesOneLitreOfGas() {
        final Web.Recipe recipe = VoidMiner.enrich(dimension());
        final Web.Recipe fed = VoidMiner.withInputs(recipe, Map.of(VoidMiner.NOBLE_GAS, "xenon"));
        assertEquals(2, fed.inputs.size());
        assertEquals("xenon", fed.inputs.get(1).id);
        assertEquals(1, fed.inputs.get(1).amount);
        assertEquals(2, VoidMiner.withInputs(fed, Map.of(VoidMiner.NOBLE_GAS, "xenon")).inputs.size());
        assertEquals(1, VoidMiner.withInputs(recipe, Map.of()).inputs.size());
    }
}
