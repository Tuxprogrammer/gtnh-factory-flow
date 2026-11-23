package com.gtnhplanner.core.machines;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.gtnhplanner.machines.web.Tiers;
import com.gtnhplanner.machines.web.Web;

/**
 * The Void Miners (GT5U bwcrossmod.galacticgreg.MTEVoidMinerBase, tiers I to III in MTEVoidMiners), as the website
 * models them (src/lib/machines/void-miner.ts).
 *
 * <p>
 * The "Void Miner" map has one NEI recipe per dimension: the planet marker (not consumed) in, the dimension's ore pool
 * out with each ore's share as its chance, registered at 1 tick and 0 EU (VoidMinerRecipes). The machine ignores both
 * numbers. setElectricityStats runs a fixed RECIPE_DURATION_TICKS = 10 cycle at VP[tier + MIN_TIER_OFFSET 5] EU/t, and
 * each cycle outputs ONE ore drawn by weight from the pool, a stack of TIER_MULTIPLIER (1, 2, 3) ores, times the noble
 * gas boost when a hatch holds one (Neon 4, Krypton 8, Xenon 16, Oganesson 64, VoidMinerUtility.NOBLE_GASSES_MULTIPLIER),
 * which costs 1 L a cycle (consumeNobleGas). Batch mode runs 16 cycles' worth in one 160-tick cycle: the same rates. No
 * overclocks and no parallels. The rates mod does not touch this machine. A filtered ore is voided, not rerolled.
 */
public final class VoidMiner {

    private VoidMiner() {}

    public static final String MAP = "Void Miner";
    public static final int CYCLE_TICKS = 10;
    public static final String NOBLE_GAS = "voidMinerNobleGas";
    /** MTEVoidMinerBase.MIN_TIER_OFFSET. */
    private static final int MIN_TIER_OFFSET = 5;
    private static final String[] MACHINE_TYPES = { "Void Miner I", "Void Miner II", "Void Miner III" };
    private static final String[] GAS_KEYS = { "neon", "krypton", "xenon", "oganesson" };
    private static final String[] GAS_LABELS = { "Neon", "Krypton", "Xenon", "Oganesson" };
    private static final int[] GAS_BOOSTS = { 4, 8, 16, 64 };

    /** VOID_MINER_TIERS: TIER_MULTIPLIER of the controller this machine type names, or null. */
    @Nullable
    public static Integer tier(@Nullable final String machineType) {
        for (int i = 0; i < MACHINE_TYPES.length; i++) if (MACHINE_TYPES[i].equals(machineType)) return i + 1;
        return null;
    }

    /** isVoidMinerRecipe. */
    public static boolean isRecipe(@Nullable final Web.Recipe recipe) {
        if (recipe == null) return false;
        return MAP.equals(recipe.machineType) || recipe.source != null && MAP.equals(recipe.source.recipeMap)
            || tier(recipe.machineType) != null;
    }

    /** voidMinerEuPerTick: VP[tier + 5], GTValues.VP being V x 30 / 32. */
    public static double euPerTick(final int tier) {
        return Math.floor(Tiers.MAX_EUT[tier + MIN_TIER_OFFSET] * 30 / 32);
    }

    /** voidMinerMinimumTier: the energy hatch tier the controller needs. */
    public static String minimumTier(final int tier) {
        return Tiers.NAMES[tier + MIN_TIER_OFFSET];
    }

    @Nullable
    private static Integer handlerTier(final Web.Handler handler) {
        final Integer byType = tier(handler.machineType);
        return byType != null ? byType : tier(handler.label);
    }

    /**
     * enrichVoidMinerRecipe: each controller's draw, minimum hatch and 10-tick cycle on its handler, the recipe itself
     * at Void Miner I's (handlers ordered I, II, III), the chances scaled to sum to one (every cycle draws exactly one
     * ore; NEI rounds each share to 0.01%), and no exported runtime ladder.
     */
    public static Web.Recipe enrich(final Web.Recipe recipe) {
        final Web.Recipe out = recipe.copy();
        if (recipe.machineHandlers != null && !recipe.machineHandlers.isEmpty()) {
            final List<Web.Handler> handlers = new ArrayList<>();
            for (final Web.Handler handler : recipe.machineHandlers) {
                final Integer tier = handlerTier(handler);
                if (tier == null) {
                    handlers.add(handler);
                    continue;
                }
                final Web.Handler h = handler.copy();
                h.minimumTier = minimumTier(tier);
                h.durationTicks = (double) CYCLE_TICKS;
                h.eut = euPerTick(tier);
                handlers.add(h);
            }
            handlers.sort(Comparator.comparingInt(h -> {
                final Integer tier = handlerTier(h);
                return tier != null ? tier : 99;
            }));
            out.machineHandlers = handlers;
        }
        double sum = 0;
        if (recipe.outputs != null) for (final Web.Resource output : recipe.outputs)
            sum += output.chance != null ? output.chance : 1;
        if (recipe.outputs != null && sum > 0 && Math.abs(sum - 1) > 1e-9) {
            final List<Web.Resource> outputs = new ArrayList<>(recipe.outputs.size());
            for (final Web.Resource output : recipe.outputs) {
                final Web.Resource o = new Web.Resource();
                o.kind = output.kind;
                o.id = output.id;
                o.amount = output.amount;
                o.chance = (output.chance != null ? output.chance : 1) / sum;
                o.consumed = output.consumed;
                o.optional = output.optional;
                o.displayName = output.displayName;
                o.tooltip = output.tooltip;
                outputs.add(o);
            }
            out.outputs = outputs;
        }
        out.runtimeCalculation = null;
        out.minimumTier = minimumTier(1);
        out.durationTicks = CYCLE_TICKS;
        out.eut = euPerTick(1);
        return out;
    }

    /** getVoidMinerControls: the noble gas knob, each option multiplying the ores a cycle by tier x the gas boost. */
    public static List<Web.Control> controls(final int tier) {
        final Web.Control control = new Web.Control();
        control.id = NOBLE_GAS;
        control.label = "Noble gas";
        control.minimumKey = "none";
        control.defaultKey = "none";
        control.tiers = new ArrayList<>();
        control.tiers.add(
            option(
                "none",
                "No gas",
                tier,
                "item",
                "factoryflow:machine_config/" + NOBLE_GAS + "_none",
                tier + " ores a cycle."));
        for (int i = 0; i < GAS_KEYS.length; i++) control.tiers.add(
            option(
                GAS_KEYS[i],
                GAS_LABELS[i],
                tier * GAS_BOOSTS[i],
                "fluid",
                GAS_KEYS[i],
                GAS_BOOSTS[i] + "x ores for 1 L of "
                    + GAS_LABELS[i]
                    + " a cycle: "
                    + tier * GAS_BOOSTS[i]
                    + " ores a cycle."));
        return List.of(control);
    }

    private static Web.TierOption option(final String key, final String label, final int multiplier,
        final String kind, final String id, final String detail) {
        final Web.TierOption option = new Web.TierOption();
        option.key = key;
        option.label = label;
        option.outputMultiplier = (double) multiplier;
        final Web.Resource resource = new Web.Resource();
        resource.kind = kind;
        resource.id = id;
        resource.amount = 1;
        resource.displayName = label;
        resource.tooltip = List.of(detail);
        resource.consumed = false;
        option.resource = resource;
        return option;
    }

    /** applyVoidMinerInputs: the noble gas a boosted cycle uses up, 1 L, added as an input. */
    public static Web.Recipe withInputs(final Web.Recipe recipe, @Nullable final Map<String, String> settings) {
        final String key = settings != null ? settings.get(NOBLE_GAS) : null;
        int gas = -1;
        for (int i = 0; i < GAS_KEYS.length; i++) if (GAS_KEYS[i].equals(key)) gas = i;
        if (gas < 0) return recipe;
        if (recipe.inputs != null) for (final Web.Resource input : recipe.inputs)
            if ("fluid".equals(input.kind) && GAS_KEYS[gas].equals(input.id)) return recipe;
        final Web.Resource input = new Web.Resource();
        input.kind = "fluid";
        input.id = GAS_KEYS[gas];
        input.amount = 1;
        input.displayName = GAS_LABELS[gas];
        final Web.Recipe out = recipe.copy();
        out.inputs = new ArrayList<>(recipe.inputs != null ? recipe.inputs : List.of());
        out.inputs.add(input);
        return out;
    }
}
