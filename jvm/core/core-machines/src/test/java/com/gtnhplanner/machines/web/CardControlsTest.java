package com.gtnhplanner.machines.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * Every game card's settings, composed as the mod's card composes them (WebSettings: the coil, then
 * {@link RecipeRules#configTierControls}, the machine table's over the dataset's, the table's hidden ones dropped),
 * for every machine of every recipe map in handlers.json. A card never shows one setting twice: no two settings with
 * one label, and no setting the dataset added to a machine-table machine that no formula reads (set to each of its
 * options it changes no number: duration, EU/t, overclocks, structural parallels). The website's side is the vitest of the same
 * name and tools/audits/card-controls-audit.mjs over a dataset.
 */
class CardControlsTest {

    /** One card per map and machine: an LV recipe of 100 ticks, special value 0. */
    private record Card(String map, String handler, Web.Recipe recipe, Web.Node node, Web.Recipe effective,
        List<RecipeRules.TierControl> shown) {}

    private static List<Card> cards() {
        final List<Card> out = new ArrayList<>();
        for (final HandlerData.MapEntry entry : HandlerData.maps()
            .values()) {
            final Web.Recipe recipe = MapRecipes.recipe(entry.id, 30, 100, 0);
            final Set<String> handlerIds = new LinkedHashSet<>();
            if (recipe.machineHandlers != null) for (final Web.Handler h : recipe.machineHandlers) handlerIds.add(h.id);
            else handlerIds.add("");
            for (final String id : handlerIds) {
                final Web.Node node = MapRecipes.node(id, "LV", Map.of());
                final Web.Recipe effective = RecipeRules.applyHandler(recipe, node);
                out.add(new Card(entry.id, id, recipe, node, effective, shown(effective, Map.of())));
            }
        }
        return out;
    }

    private static List<RecipeRules.TierControl> shown(final Web.Recipe effective, final Map<String, String> tiers) {
        final List<RecipeRules.TierControl> out = new ArrayList<>();
        final RecipeRules.TierControl coil = RecipeRules.coilTierControl(effective, tiers.get("heatingCoil"));
        if (coil != null) out.add(coil);
        out.addAll(RecipeRules.configTierControls(effective, tiers));
        return out;
    }

    private static String norm(final String label) {
        return label == null ? ""
            : label.trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    /** The numbers a setting can change on the card. */
    private static String numbers(final Card card, final String controlId, final String key) {
        final Web.Node node = MapRecipes.node(card.handler(), "LV", Map.of(controlId, key));
        final NodeMath.Result r = NodeMath.compute(card.recipe(), node);
        return r.overclock()
            .durationTicks() + "/"
            + r.overclock()
                .eut()
            + "/"
            + r.overclock()
                .overclockSteps()
            + "/"
            + MachineEffects.structuralParallels(r.effectiveRecipe(), node);
    }

    @Test
    void noCardShowsTwoSettingsWithOneLabel() {
        final List<Card> cards = cards();
        final Set<String> doubled = new TreeSet<>();
        for (final Card card : cards) {
            final List<RecipeRules.TierControl> shown = card.shown();
            for (int i = 0; i < shown.size(); i++) for (int j = i + 1; j < shown.size(); j++)
                if (norm(shown.get(i)
                    .label())
                    .equals(
                        norm(
                            shown.get(j)
                                .label())))
                    doubled.add(
                        card.effective().machineType + ": "
                            + shown.get(i)
                                .id()
                            + " + "
                            + shown.get(j)
                                .id());
        }
        assertTrue(cards.size() > 300, "every map's machines are walked: " + cards.size());
        assertEquals(KNOWN_DOUBLED, doubled, "cards showing two settings with one label");
    }

    @Test
    void everyDatasetSettingOfATableMachineChangesANumber() {
        final Set<String> unread = new TreeSet<>();
        final Set<String> machines = new HashSet<>();
        for (final Card card : cards()) {
            final String machine = card.effective().machineType;
            if (MachineTable.behaviour(machine) == null || !machines.add(machine)) continue;
            final Set<String> tableIds = new HashSet<>();
            for (final Web.Control c : MachineTable.controls(machine, card.effective())) tableIds.add(c.id);
            for (final RecipeRules.TierControl c : card.shown()) {
                if (tableIds.contains(c.id()) || c.id()
                    .equals("heatingCoil") || c.numeric() != null
                    || c.tiers()
                        .size() < 2)
                    continue;
                final Set<String> prints = new HashSet<>();
                for (final Web.TierOption t : c.tiers()) prints.add(numbers(card, c.id(), t.key));
                if (prints.size() == 1) unread.add(machine + ": " + c.id());
            }
        }
        assertTrue(machines.size() > 100, "the table machines are walked: " + machines.size());
        assertEquals(KNOWN_UNREAD, unread, "dataset settings a table machine's card shows and no formula reads");
    }

    /** None: the machine table hides every dataset setting it replaces ({@code hidesControls}). */
    private static final Set<String> KNOWN_DOUBLED = Set.of();

    /** None. */
    private static final Set<String> KNOWN_UNREAD = Set.of();
}
