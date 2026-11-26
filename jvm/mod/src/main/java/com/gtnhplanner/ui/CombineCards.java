package com.gtnhplanner.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.SettingDef;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.machines.game.WebCards;
import com.gtnhplanner.machines.web.CombineMachine;
import com.gtnhplanner.machines.web.Web;

/**
 * The machine settings a Combine gives its card, the website's rule ({@code machines.web.CombineMachine}): taken from
 * every card, never from whichever card hosts, so the same cards combine to the same machine in any order and
 * wherever they sit. A GregTech card's tier, hatches, coil and machine options go through the website's maths; every
 * other setting is the highest any card had.
 */
final class CombineCards {

    private CombineCards() {}

    /** The machine (an item key) most cards name, ties to the shared order of the machines that run them all. */
    static String machine(final List<List<String>> runs, final List<String> named) {
        return CombineMachine.pick(named, CombineMachine.sharedOrder(runs));
    }

    /**
     * Puts the combined machine's settings on {@code host}, which already runs {@code machine}: the cards'
     * ({@code cards}, each with its recipes from {@code sectionsOf}) settings as they ran before the Combine.
     */
    static void settle(final Graph graph, final Node host, final List<Uuid> cards,
        final Function<Uuid, List<Uuid>> sectionsOf, final String machine) {
        final List<CombineMachine.Card> web = new ArrayList<>();
        final List<Map<String, Object>> raw = new ArrayList<>();
        boolean gregtech = true;
        for (final Uuid id : cards) {
            final Node card = graph.nodes.get(id);
            raw.add(new HashMap<>(card.machineConfig.settings));
            if (!gregtech) continue;
            final List<Web.Recipe> recipes = new ArrayList<>();
            for (final Uuid s : sectionsOf.apply(id)) {
                final Node n = graph.nodes.get(s);
                final Web.Recipe r = n == null ? null : WebCards.recipe(n);
                if (r == null) {
                    gregtech = false;
                    break;
                }
                recipes.add(r);
            }
            if (!gregtech) continue;
            final Web.Node ran = WebCards.node(card, card.machineConfig, recipes.get(0));
            // The handler the combined machine is for this card's recipe.
            final String was = card.machineName;
            card.machineName = machine;
            final String target = WebCards.handlerId(card, recipes.get(0));
            card.machineName = was;
            web.add(new CombineMachine.Card(ran, recipes, target));
        }
        final Map<String, Object> settings = host.machineConfig.settings;
        final Set<String> skip = new HashSet<>(CardRecipes.RECIPE_SETTINGS);
        CombineMachine.Combined combined = null;
        if (gregtech && !web.isEmpty()) {
            combined = CombineMachine.combine(web);
            skip.add(WebCards.COIL);
            if (combined.tiered()) skip.add(WebCards.VOLTAGE);
            if (combined.hatches()) {
                skip.add(WebCards.AMPS);
                skip.add(WebCards.HATCH_TYPE);
            }
            for (final Map<String, Object> m : raw) m.keySet()
                .removeIf(k -> k.startsWith(WebCards.CONTROL_PREFIX));
        }
        final Map<String, SettingDef<?>> defs = new HashMap<>();
        for (final SettingDef<?> def : host.machineConfig.getProfile()
            .settings()) defs.put(def.key, def);
        settings.putAll(
            CombineMachine.highest(
                raw,
                skip,
                key -> defs.containsKey(key) && defs.get(key)
                    .hasOptions() ? defs.get(key).options : null));
        if (combined == null) return;
        final Web.Node n = combined.node();
        settings.keySet()
            .removeIf(k -> k.startsWith(WebCards.CONTROL_PREFIX));
        if (n.machineConfigTiers != null) for (final Map.Entry<String, String> e : n.machineConfigTiers.entrySet())
            settings.put(WebCards.CONTROL_PREFIX + e.getKey(), e.getValue());
        if (n.coilTier != null) settings.put(WebCards.COIL, n.coilTier);
        else settings.remove(WebCards.COIL);
        if (combined.tiered())
            settings.put(WebCards.VOLTAGE, combined.hatches() ? n.hatchVoltageTier : n.overclockTier);
        if (combined.hatches()) {
            settings.put(WebCards.AMPS, (int) Math.min(Integer.MAX_VALUE, n.hatchAmps));
            if (n.energyHatchType != null) settings.put(WebCards.HATCH_TYPE, n.energyHatchType);
            else settings.remove(WebCards.HATCH_TYPE);
        }
    }
}
