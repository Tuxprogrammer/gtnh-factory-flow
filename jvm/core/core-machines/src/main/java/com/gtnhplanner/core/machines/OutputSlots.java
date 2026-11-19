package com.gtnhplanner.core.machines;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.gtnhplanner.machines.web.Tiers;
import com.gtnhplanner.machines.web.Web;

/**
 * Item outputs a machine has room for (the website's src/lib/machines/singleblock-output-slots.ts): the outputs past a
 * machine's item output slots are never made.
 */
public final class OutputSlots {

    private OutputSlots() {}

    /** One step of a family's slots: from {@code tier} up, {@code slots} item output slots. */
    private record Step(String tier, int slots) {}

    private static Step from(final String tier, final int slots) {
        return new Step(tier, slots);
    }

    /**
     * ITEM_OUTPUT_SLOTS: item output slots of the single-block machines whose recipe maps carry more item outputs than
     * the machine has slots. MTEBasicMachine.checkRecipe rolls output i only for i below mOutputItems.length (one entry
     * per output slot), and canOutput checks only those slots, so the outputs past them are never made and never block
     * the machine. The steam macerators go further and roll output 0 alone (MTESteamMacerator*.checkRecipe). Counts are
     * setSlotsCount in GT5-Unofficial's LoaderMetaTileEntities and the steam constructors. Families whose slots cover
     * every recipe on their map are left out.
     */
    private static final Map<String, List<Step>> ITEM_OUTPUT_SLOTS = Map.ofEntries(
        Map.entry("Macerator", List.of(from("LV", 1), from("HV", 2), from("EV", 3), from("IV", 4))),
        Map.entry("Steam Macerator", List.of(from("ULV", 1))),
        Map.entry("High Pressure Steam Macerator", List.of(from("ULV", 1))),
        Map.entry("Arc Furnace", List.of(from("LV", 4), from("EV", 9))),
        Map.entry("Cutting Machine", List.of(from("LV", 2), from("HV", 4))),
        Map.entry("Mixer", List.of(from("LV", 1), from("HV", 4))),
        Map.entry("Chemical Bath", List.of(from("LV", 3), from("HV", 6))),
        Map.entry("Autoclave", List.of(from("LV", 2), from("HV", 3), from("EV", 4))),
        Map.entry("Centrifuge", List.of(from("LV", 6))),
        Map.entry("Forge Hammer", List.of(from("LV", 1))),
        Map.entry("Steam Forge Hammer", List.of(from("ULV", 1))),
        Map.entry("High Pressure Steam Forge Hammer", List.of(from("ULV", 1))),
        Map.entry("Precision Laser Engraver", List.of(from("LV", 1))));

    /**
     * MULTIBLOCK_ITEM_OUTPUT_LIMIT: multiblocks that override IVoidable.getItemOutputLimit. ParallelHelper hands only
     * mOutputs[0..limit) (its truncatedItemOutputs) to calculateItemOutputs, so later outputs are voided at every
     * parallel and every tier. The Steam Grinder (MTESteamMacerator) and bartworks' MTEWindmill both return 1.
     */
    private static final Map<String, Integer> MULTIBLOCK_ITEM_OUTPUT_LIMIT = Map.of("Steam Grinder", 1, "Windmill", 1);

    /**
     * itemOutputSlots: the machine's item output slots at this tier (a multiblock's output limit), or null when it has
     * room for every recipe.
     */
    @Nullable
    public static Integer itemOutputSlots(final Web.Recipe recipe, final String tier) {
        final Integer limit = recipe.machineType != null ? MULTIBLOCK_ITEM_OUTPUT_LIMIT.get(recipe.machineType) : null;
        if (limit != null) return limit;
        if (recipe.machineProfile != null && "multiblock".equals(recipe.machineProfile.kind)) return null;
        final List<Step> steps = recipe.machineType != null ? ITEM_OUTPUT_SLOTS.get(recipe.machineType) : null;
        if (steps == null) return null;
        final int index = Tiers.index(tier);
        int slots = steps.get(0)
            .slots();
        for (final Step step : steps) if (Tiers.index(step.tier()) <= index) slots = step.slots();
        return slots;
    }

    /**
     * hasItemOutputSlot: whether this output has a slot to land in. The recipe's outputs are in the game's order: items
     * first, then fluids, as exported; {@code output} is one of them (found by identity, as the website's indexOf).
     */
    public static boolean hasItemOutputSlot(final Web.Recipe recipe, final Web.Resource output, final String tier) {
        if (!"item".equals(output.kind)) return true;
        final Integer slots = itemOutputSlots(recipe, tier);
        if (slots == null || recipe.outputs == null) return true;
        int itemIndex = 0;
        for (final Web.Resource entry : recipe.outputs) {
            if (!"item".equals(entry.kind)) continue;
            if (entry == output) return itemIndex < slots;
            itemIndex++;
        }
        return true;
    }

    /** zeroUnslottedOutputs: the outputs past the machine's item slots set to zero, in place; ports stay. */
    public static void zeroUnslotted(final Web.Recipe recipe, final List<Web.Resource> outputs, final String tier) {
        final Integer slots = itemOutputSlots(recipe, tier);
        if (slots == null) return;
        int itemIndex = 0;
        for (final Web.Resource output : outputs) if ("item".equals(output.kind) && itemIndex++ >= slots) output.amount = 0;
    }
}
