package com.gtnhplanner.machines.web;

import static com.gtnhplanner.machines.web.MapRecipes.recipe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * Combine gives the same machine whichever card hosts and whatever order the cards come in (the website's
 * combine-machine.test.ts, "Combine is the same whichever card hosts"): every test combines its cards in every order.
 */
class CombineMachineTest {

    private static <T> List<List<T>> orders(final List<T> items) {
        if (items.size() <= 1) return List.of(items);
        final List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            final List<T> rest = new ArrayList<>(items);
            final T item = rest.remove(i);
            for (final List<T> tail : orders(rest)) {
                final List<T> order = new ArrayList<>();
                order.add(item);
                order.addAll(tail);
                out.add(order);
            }
        }
        return out;
    }

    /** What a combined machine is, as text, so two results compare field by field. */
    private static String read(final CombineMachine.Combined c) {
        final Web.Node n = c.node();
        return "handler=" + n.machineHandlerId + " tier=" + n.overclockTier + " hatch=" + n.hatchVoltageTier + " amps="
            + n.hatchAmps + " eu=" + n.powerEuT + " type=" + n.energyHatchType + " coil=" + n.coilTier + " config="
            + n.machineConfigTiers + " tiered=" + c.tiered() + " hatches=" + c.hatches();
    }

    /** Combines the cards in every order and checks every order gives the same machine; returns it. */
    private static CombineMachine.Combined everyOrder(final List<CombineMachine.Card> cards) {
        final List<List<CombineMachine.Card>> orders = orders(cards);
        final CombineMachine.Combined first = CombineMachine.combine(orders.get(0));
        for (final List<CombineMachine.Card> order : orders)
            assertEquals(read(first), read(CombineMachine.combine(order)), "order " + orders.indexOf(order));
        return first;
    }

    private static CombineMachine.Card ebf(final String tier, final double amps, final String coil,
        final Map<String, String> settings) {
        final Web.Node n = MapRecipes.node("electric-blast-furnace", tier, settings);
        n.hatchAmps = amps;
        n.coilTier = coil;
        return new CombineMachine.Card(n, List.of(recipe("gt.recipe.blastfurnace", 120, 1000, 1800)),
            "electric-blast-furnace");
    }

    @Test
    void takesTheHighestTierAndTheLargestSupplyAnyCardHad() {
        final CombineMachine.Combined c = everyOrder(
            List.of(ebf("EV", 1, null, Map.of()), ebf("LuV", 1, null, Map.of()), ebf("IV", 8, null, Map.of())));
        assertEquals("LuV", c.node().hatchVoltageTier);
        assertEquals("LuV", c.node().overclockTier);
        // 8 A of IV is 65,536 EU/t: two amps of LuV.
        assertEquals(2.0, c.node().hatchAmps);
        assertEquals(2 * 32768.0, c.node().powerEuT);
        assertTrue(c.hatches());
    }

    @Test
    void takesTheHighestCoil() {
        final CombineMachine.Combined c = everyOrder(
            List.of(ebf("EV", 1, "kanthal", Map.of()), ebf("EV", 1, "hss_g", Map.of()), ebf("EV", 1, null, Map.of())));
        assertEquals("hss_g", c.node().coilTier);
    }

    @Test
    void raisesTheTierForARecipeThatNeedsMoreInEveryOrder() {
        final Web.Node n = MapRecipes.node("electric-blast-furnace", "HV", Map.of());
        n.hatchAmps = 1.0;
        final CombineMachine.Card iv = new CombineMachine.Card(n,
            List.of(recipe("gt.recipe.blastfurnace", 7680, 1000, 1800)), "electric-blast-furnace");
        final CombineMachine.Combined c = everyOrder(List.of(ebf("HV", 1, null, Map.of()), ebf("HV", 1, null, Map.of()), iv));
        assertEquals("IV", c.node().hatchVoltageTier);
        assertEquals(1.0, c.node().hatchAmps);
    }

    @Test
    void addsWholeAmpsForOneParallelOfEveryRecipe() {
        // 1,920 EU/t on EV hatches (2,048 at one amp) starts; 4,000 EU/t on EV (one tier above the recipe's) wants two.
        final Web.Node n = MapRecipes.node("electric-blast-furnace", "EV", Map.of());
        n.hatchAmps = 1.0;
        final CombineMachine.Card big = new CombineMachine.Card(n,
            List.of(recipe("gt.recipe.blastfurnace", 4000, 1000, 1800)), "electric-blast-furnace");
        final CombineMachine.Combined c = everyOrder(List.of(ebf("EV", 1, null, Map.of()), big));
        assertEquals("EV", c.node().hatchVoltageTier);
        assertEquals(2.0, c.node().hatchAmps);
    }

    @Test
    void ranksStoredSettingsNumbersByValueAndTierNamesByTier() {
        final CombineMachine.Combined c = everyOrder(
            List.of(
                ebf("EV", 1, null, Map.of("rings", "9", "hatch", "MV", "top", "ZPM")),
                ebf("EV", 1, null, Map.of("rings", "10", "hatch", "HV", "top", "UV")),
                ebf("EV", 1, null, Map.of("rings", "2", "hatch", "LV"))));
        assertEquals(Map.of("rings", "10", "hatch", "HV", "top", "UV"), c.node().machineConfigTiers);
    }

    @Test
    void takesTheHighestMachineSettingAControlReadsInTheControlsOrder() {
        // The Industrial Arc Furnace's electrodes: graphene is above tungsten on the control, below it by text.
        final Function<String, CombineMachine.Card> arc = electrode -> {
            final Map<String, String> s = electrode == null ? Map.of() : Map.of("arcElectrode", electrode);
            return new CombineMachine.Card(MapRecipes.node("industrial-arc-furnace", "EV", s),
                List.of(recipe("gt.recipe.blastfurnace", 120, 1000, 1800)), "industrial-arc-furnace");
        };
        final CombineMachine.Combined c = everyOrder(List.of(arc.apply("tungsten"), arc.apply("graphene"), arc.apply(null)));
        assertEquals("industrial-arc-furnace", c.node().machineHandlerId);
        assertEquals("graphene", c.node().machineConfigTiers.get("arcElectrode"));
    }

    @Test
    void runsASingleBlockAtTheHighestTierAnyCardRanAt() {
        final Function<String, CombineMachine.Card> wiremill = tier -> new CombineMachine.Card(
            MapRecipes.node(null, tier, Map.of()), List.of(recipe("gt.recipe.wiremill", 30, 100, 0)), null);
        final CombineMachine.Combined c = everyOrder(List.of(wiremill.apply("LV"), wiremill.apply("HV"), wiremill.apply("MV")));
        assertEquals("HV", c.node().overclockTier);
        assertTrue(!c.hatches());
    }

    @Test
    void runsTheMachineMostCardsNameTiesToTheSharedOrder() {
        final List<String> machines = List.of("ebf", "hearth", "forge", "arc", "volcanus");
        for (final List<String> named : orders(List.of("volcanus", "hearth", "hearth")))
            assertEquals("hearth", CombineMachine.pick(named, machines));
        for (final List<String> named : orders(List.of("volcanus", "hearth", "ebf")))
            assertEquals("hearth", CombineMachine.pick(named, machines), "a tie goes to the shared order");
        assertEquals("ebf", CombineMachine.pick(List.of("ebf", "ebf"), machines));
    }

    @Test
    void theSharedOrderIsTheSameWhateverOrderTheCardsComeIn() {
        final List<List<String>> lists = List.of(List.of("a", "b", "c"), List.of("b", "a", "c", "d"), List.of("c", "a", "b"));
        final List<String> first = CombineMachine.sharedOrder(lists);
        for (final List<List<String>> order : orders(lists)) assertEquals(first, CombineMachine.sharedOrder(order));
        assertEquals(List.of("a", "b", "c"), first);
    }

    @Test
    void ranksAnySettingByItsKindWhateverTheOrder() {
        final List<String> voltage = List.of("OFF", "ULV", "LV", "MV", "HV", "EV", "IV", "LuV");
        final Function<String, List<String>> options = key -> key.equals("voltage") ? voltage : null;
        final List<Map<String, Object>> cards = List.of(
            map("voltage", "OFF", "parallels", 9, "text", "9", "perfect_oc", true, "tier", "EV"),
            map("voltage", "LuV", "parallels", 10, "text", "10", "perfect_oc", false, "tier", "IV"),
            map("voltage", "HV", "parallels", 2, "text", "2", "machines", 7));
        Map<String, Object> first = null;
        for (final List<Map<String, Object>> order : orders(cards)) {
            final Map<String, Object> got = CombineMachine.highest(order, Set.of("machines"), options);
            if (first == null) first = got;
            assertEquals(first, got);
        }
        assertEquals(map("voltage", "LuV", "parallels", 10, "text", "10", "perfect_oc", true, "tier", "IV"), first);
    }

    private static Map<String, Object> map(final Object... kv) {
        final Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
