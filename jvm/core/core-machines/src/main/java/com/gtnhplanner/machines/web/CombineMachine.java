package com.gtnhplanner.machines.web;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * "Combine N into one machine" as the website does it (store/combine-machine.ts): the result never depends on which
 * card hosts or on the order the cards come in. The machine is the one most cards name, ties to a fixed order; its
 * tier, supply, coil and settings are the highest any card had (numbers by value, voltage tiers by tier order, a
 * control's options by the control's order, anything else by text), then raised until every recipe starts and gets
 * one parallel.
 */
public final class CombineMachine {

    private CombineMachine() {}

    /**
     * sharedCombineHandlers: the machines in every list, in one order whatever order the lists come in: by their summed
     * place in the lists, then by key.
     */
    public static List<String> sharedOrder(final List<List<String>> lists) {
        if (lists.isEmpty()) return List.of();
        final List<String> out = new ArrayList<>();
        for (final String key : lists.get(0)) if (!out.contains(key) && lists.stream()
            .allMatch(list -> list.contains(key))) out.add(key);
        out.sort(
            Comparator.comparingInt(
                (String key) -> lists.stream()
                    .mapToInt(list -> list.indexOf(key))
                    .sum())
                .thenComparing(Comparator.naturalOrder()));
        return out;
    }

    /**
     * pickCombineHost's machine: a card naming a machine other than the shared list's first is the player's choice;
     * the one most cards name wins, ties to the shared order; none named, the first.
     */
    public static String pick(final List<String> named, final List<String> shared) {
        final String fallback = shared.get(0);
        final Map<String, Integer> votes = new com.gtnhplanner.core.base.JavaHashMap<>();
        for (final String key : named)
            if (key != null && !key.equals(fallback) && shared.contains(key)) votes.merge(key, 1, Integer::sum);
        String best = fallback;
        int most = 0;
        for (final String key : shared) {
            final int count = votes.getOrDefault(key, 0);
            if (count > most) {
                most = count;
                best = key;
            }
        }
        return best;
    }

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");

    /**
     * compareStoredSetting: two stored settings no control reads, highest last: numbers by value (10 above 9), voltage
     * tier names by tier (IV above EV), anything else by its text; numbers rank below tier names, and those below the
     * rest, so the pick never follows card order.
     */
    public static int compareStored(final String a, final String b) {
        final int ka = kind(a), kb = kind(b);
        if (ka != kb) return ka - kb;
        if (ka == 0) {
            final int byValue = Double.compare(com.gtnhplanner.core.base.NumberText.parse(a), com.gtnhplanner.core.base.NumberText.parse(b));
            return byValue != 0 ? byValue : a.compareTo(b);
        }
        if (ka == 1) return Tiers.index(a) - Tiers.index(b);
        return a.compareTo(b);
    }

    private static int kind(final String key) {
        return NUMBER.matcher(key)
            .matches() ? 0 : Tiers.isName(key) ? 1 : 2;
    }

    /**
     * Two setting values of any kind, highest last: an option of a listed setting ({@code options}, the setting's own
     * order) by its place there, false below true, numbers by value, text as {@link #compareStored}.
     */
    public static int compareValue(@Nullable final List<String> options, final Object a, final Object b) {
        if (options != null && a instanceof String && b instanceof String) {
            final int ia = options.indexOf(a), ib = options.indexOf(b);
            if (ia >= 0 && ib >= 0) return ia - ib;
        }
        if (a instanceof final Boolean x && b instanceof final Boolean y) return Boolean.compare(x, y);
        if (a instanceof final Number x && b instanceof final Number y)
            return Double.compare(x.doubleValue(), y.doubleValue());
        return compareStored(text(a), text(b));
    }

    private static String text(final Object value) {
        if (value instanceof final Number n && n.doubleValue() == com.gtnhplanner.core.base.ExactMath.rint(n.doubleValue()))
            return Long.toString(n.longValue());
        return String.valueOf(value);
    }

    /**
     * The highest value any card's settings had for each key (keys in {@code skip} left out), in key order:
     * {@code options} gives a listed setting's options in its own order (null for none).
     */
    public static Map<String, Object> highest(final List<Map<String, Object>> cards, final Set<String> skip,
        final Function<String, List<String>> options) {
        final Map<String, Object> out = new java.util.TreeMap<>();
        for (final Map<String, Object> card : cards) for (final Map.Entry<String, Object> e : card.entrySet()) {
            if (skip.contains(e.getKey()) || e.getValue() == null) continue;
            final Object held = out.get(e.getKey());
            if (held == null || compareValue(options.apply(e.getKey()), e.getValue(), held) > 0)
                out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /**
     * One card as the website's maths see it: its settings ({@code node}, on the machine it runs now), its recipes
     * (its own first, then its shared machine's others) and the combined machine's handler for its recipe (null: the
     * map's primary machine).
     */
    public record Card(Web.Node node, List<Web.Recipe> sections, @Nullable String target) {}

    /**
     * The combined machine's settings: {@code node} as the website's fields; {@code tiered} false where no tier was
     * picked (a fusion reactor's is its own); {@code hatches} true when every card is a multiblock, so the hatches'
     * tier, amps and type were picked.
     */
    public record Combined(Web.Node node, boolean tiered, boolean hatches) {}

    /**
     * combinedMachineSettings then fitCombinedMachine: the highest tier and the largest supply any card ran on, the
     * highest coil and machine setting any card ran each recipe at (settings no control reads by
     * {@link #compareStored}), then a tier and whole amps every recipe starts on with one parallel. Only raises.
     */
    public static Combined combine(final List<Card> cards) {
        record OnMachine(Card card, Web.Node node, Web.Recipe effective, List<Web.Recipe> sections) {}
        final List<OnMachine> on = new ArrayList<>();
        for (final Card card : cards) {
            if (card.sections()
                .isEmpty()) continue;
            final Web.Recipe primary = card.sections()
                .get(0);
            final Web.Node node = copy(card.node());
            node.machineHandlerId = card.target();
            if (!Objects.equals(
                RecipeRules.selectedHandler(primary, card.node()).id,
                RecipeRules.selectedHandler(primary, node).id)) {
                final HatchInput.Hatches carried = HatchInput.carry(primary, card.node(), card.target());
                if (carried != null && carried.amps() == 0) node.overclockTier = carried.tier();
                else if (carried != null) {
                    node.hatchVoltageTier = carried.tier();
                    node.hatchAmps = 1.0;
                    node.powerInputMode = "amps";
                    node.powerEuT = Tiers.maxEuT(carried.tier());
                }
            }
            final List<Web.Recipe> sections = new ArrayList<>();
            for (final Web.Recipe r : card.sections()) sections.add(RecipeRules.applyHandler(r, node));
            on.add(new OnMachine(card, node, RecipeRules.applyHandler(primary, node), sections));
        }
        final Web.Node out = new Web.Node();
        out.powerInputMode = "amps";
        out.parallel = 1.0;
        out.machineCount = 1.0;
        if (on.isEmpty()) return new Combined(out, false, false);
        final OnMachine first = on.get(0);
        out.machineHandlerId = first.card()
            .target();
        boolean tiered = false, hatches = false;
        if (Fusion.machine(first.effective().machineType) == null) {
            String tier = Power.runTier(first.effective(), first.node());
            for (final OnMachine e : on) {
                final String t = Power.runTier(e.effective(), e.node());
                if (Tiers.index(t) > Tiers.index(tier)) tier = t;
            }
            out.overclockTier = tier;
            tiered = true;
            if (on.stream()
                .allMatch(e -> Power.isMultiblock(e.effective()))) {
                double supply = 0;
                for (final OnMachine e : on)
                    supply = Math.max(supply, Power.poolEuT(e.effective(), e.node(), Power.runTier(e.effective(), e.node())));
                final double voltage = Tiers.maxEuT(tier);
                final double amps = Math.max(1, Math.ceil(supply / voltage));
                out.hatchVoltageTier = tier;
                out.hatchAmps = amps;
                out.powerEuT = voltage * amps;
                final String type = first.node().energyHatchType;
                out.energyHatchType = on.stream()
                    .allMatch(e -> Objects.equals(e.node().energyHatchType, type)) ? type : null;
                hatches = true;
            }
        }
        // Coils and machine settings: the highest any card ran a recipe at, at least every recipe's own minimum.
        RecipeRules.TierControl coil = null;
        double coilRank = 0;
        final Map<String, String> config = new com.gtnhplanner.core.base.JavaHashMap<>();
        final Map<String, Double> configRank = new com.gtnhplanner.core.base.JavaHashMap<>();
        for (final OnMachine e : on) for (final Web.Recipe recipe : e.sections()) {
            final RecipeRules.TierControl c = RecipeRules.coilTierControl(recipe, e.node().coilTier);
            if (c != null && (coil == null || rank(c) > coilRank)) {
                coil = c;
                coilRank = rank(c);
            }
            for (final RecipeRules.TierControl option : RecipeRules.configTierControls(recipe, e.node().machineConfigTiers)) {
                final Double held = configRank.get(option.id());
                if (held == null || rank(option) > held) {
                    configRank.put(option.id(), rank(option));
                    config.put(option.id(), option.current().key);
                }
            }
        }
        out.coilTier = coil != null && on.stream()
            .anyMatch(e -> e.card()
                .node().coilTier != null) ? coil.current().key : null;
        final Map<String, String> stored = new java.util.TreeMap<>();
        for (final OnMachine e : on) {
            final Map<String, String> tiers = e.card()
                .node().machineConfigTiers;
            if (tiers == null) continue;
            for (final Map.Entry<String, String> s : tiers.entrySet()) {
                final String held = stored.get(s.getKey());
                if (held == null || compareStored(s.getValue(), held) > 0) stored.put(s.getKey(), s.getValue());
            }
        }
        final Map<String, String> tiers = new LinkedHashMap<>();
        for (final Map.Entry<String, String> s : stored.entrySet())
            tiers.put(s.getKey(), config.getOrDefault(s.getKey(), s.getValue()));
        out.machineConfigTiers = tiers;
        final List<Web.Recipe> recipes = new ArrayList<>();
        for (final Card card : cards) recipes.addAll(card.sections());
        fit(out, first.card()
            .sections()
            .get(0), recipes);
        return new Combined(out, tiered, hatches);
    }

    /** A config control's setting as a number that grows with the option. */
    private static double rank(final RecipeRules.TierControl control) {
        if (control.numeric() != null) return com.gtnhplanner.core.base.NumberText.parse(control.current().key);
        int at = -1;
        for (int i = 0; i < control.tiers()
            .size(); i++)
            if (control.tiers()
                .get(i).key.equals(control.current().key)) {
                at = i;
                break;
            }
        return control.minimumIndex() + at;
    }

    /**
     * fitCombinedMachine on a node already on the machine: a singleblock at the highest tier any recipe runs at, a
     * multiblock's hatches at a tier every recipe starts on and whole amps for one parallel of each.
     */
    static void fit(final Web.Node node, final Web.Recipe primary, final List<Web.Recipe> recipes) {
        final Web.Recipe effective = RecipeRules.applyHandler(primary, node);
        if (Fusion.machine(effective.machineType) != null) return;
        if (!Power.isMultiblock(effective)) {
            String top = Power.runTier(effective, node);
            for (final Web.Recipe recipe : recipes) {
                final String tier = Power.runTier(RecipeRules.applyHandler(recipe, node), node);
                if (Tiers.index(tier) > Tiers.index(top)) top = tier;
            }
            node.overclockTier = top;
            return;
        }
        if (node.hatchVoltageTier == null || node.hatchAmps == null || "eut".equals(node.powerInputMode)) return;
        String tier = node.hatchVoltageTier;
        double amps = Math.max(1, Math.ceil(node.hatchAmps));
        node.hatchAmps = amps;
        for (final Web.Recipe recipe : recipes) {
            final PowerReport.Report report = PowerReport.report(recipe, node);
            if (PowerReport.OVER_TIER.equals(report.state()) && report.recipeGateReason() == null
                && Tiers.index(report.minimumTier()) > Tiers.index(tier)) tier = report.minimumTier();
        }
        node.hatchVoltageTier = tier;
        for (final Web.Recipe recipe : recipes) {
            final PowerReport.Report report = PowerReport.report(recipe, node);
            if (PowerReport.UNDER_POWERED.equals(report.state()))
                amps = Math.max(amps, Math.ceil(report.singleDrawEuT() / Tiers.maxEuT(tier)));
        }
        node.hatchAmps = amps;
        node.powerEuT = Tiers.maxEuT(tier) * amps;
        node.overclockTier = tier;
    }

    private static Web.Node copy(final Web.Node n) {
        final Web.Node c = new Web.Node();
        c.overclockTier = n.overclockTier;
        c.hatchVoltageTier = n.hatchVoltageTier;
        c.hatchAmps = n.hatchAmps;
        c.energyHatches = n.energyHatches;
        c.energyHatchType = n.energyHatchType;
        c.powerInputMode = n.powerInputMode;
        c.powerEuT = n.powerEuT;
        c.coilTier = n.coilTier;
        c.machineConfigTiers = n.machineConfigTiers;
        c.machineHandlerId = n.machineHandlerId;
        c.parallel = n.parallel;
        c.machineCount = n.machineCount;
        return c;
    }
}
