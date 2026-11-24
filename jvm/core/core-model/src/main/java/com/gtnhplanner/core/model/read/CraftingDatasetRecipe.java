package com.gtnhplanner.core.model.read;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.gtnhplanner.machines.web.Web;

/**
 * A crafting record as the dataset's recipe: {@code normalize-oracle-export.mjs}'s {@code normalizeCrafting} (with its
 * {@code resourceAmount} for ore dictionary and choice cells), written once more in Java so the mod's crafting card
 * hands its maths the record the website's dataset holds. A crafting recipe is one tick at no EU on the shaped or
 * shapeless crafting map, with no special value. Proof: the shared cases in {@code conformance/data/crafting-records.json}.
 *
 * <p>
 * Left to the caller, as for {@link GtDatasetRecipe}: the id, the machines, what only the dataset carries (icons,
 * colours, mod ids, NEI cell positions, provenance), and the recipe classes the dataset drops by name because their
 * ingredients exist only inside {@code matches()} ({@code scripts/unreadable-crafting.mjs}).
 */
public final class CraftingDatasetRecipe {

    private CraftingDatasetRecipe() {}

    public static final String SHAPED = "Shaped Crafting", SHAPELESS = "Shapeless Crafting";

    /** How many items an "Accepts:" line names before it counts the rest. */
    private static final int ACCEPTS_SHOWN = 12;

    /**
     * The record as the dataset's recipe; null when the dataset has none for it: a cell nothing can fill (the game can
     * never craft it) or no output.
     */
    @Nullable
    public static Web.Recipe of(final CraftingRecord r) {
        for (final CraftingRecord.Slot slot : r.inputs)
            if (CraftingRecord.Slot.MISSING.equals(slot.kind)) return null;
        final Web.Resource output = r.output == null ? null : GtDatasetRecipe.resource(r.output, false);
        if (output == null) return null;
        final String machineType = "shapeless".equals(r.type) ? SHAPELESS : SHAPED;
        final List<Web.Resource> inputs = new ArrayList<>();
        for (final CraftingRecord.Slot slot : r.inputs) {
            final Web.Resource input = input(slot);
            if (input != null) inputs.add(input);
        }
        final Web.Recipe out = new Web.Recipe();
        out.name = machineType + ": " + GtDatasetRecipe.label(output);
        out.kind = "gregtech_machine";
        out.category = "crafting";
        out.machineType = machineType;
        out.minimumTier = "NONE";
        out.durationTicks = 1;
        out.eut = 0;
        out.inputs = inputs;
        final List<Web.Resource> outputs = new ArrayList<>();
        outputs.add(output);
        out.outputs = outputs;
        out.source = new Web.Source();
        out.source.recipeMap = machineType;
        return out;
    }

    @Nullable
    private static Web.Resource input(final CraftingRecord.Slot slot) {
        if (CraftingRecord.Slot.ORE_DICTIONARY.equals(slot.kind)) return oreDictionary(slot);
        if (CraftingRecord.Slot.CHOICE.equals(slot.kind)) return choice(slot);
        return slot.stack == null ? null : GtDatasetRecipe.resource(slot.stack, false);
    }

    /**
     * The normalizer's {@code oreDictionaryResource}: the cell is the name ({@code oredict:<first name>}), taking one
     * of what the names hold.
     */
    @Nullable
    private static Web.Resource oreDictionary(final CraftingRecord.Slot slot) {
        final List<String> names = new ArrayList<>();
        if (slot.oreNames != null) for (final String name : slot.oreNames)
            if (name != null && !name.isEmpty()) names.add(name);
        // The reader always names an ore dictionary cell; a nameless one would be an id the dataset hashes.
        if (names.isEmpty()) return null;
        final List<Web.Resource> accepts = resources(slot.alternatives);
        final String joined = String.join(", ", names);
        final Web.Resource out = new Web.Resource();
        out.kind = "item";
        out.id = "oredict:" + names.get(0);
        out.amount = 1;
        out.displayName = "Ore Dictionary: " + joined;
        out.tooltip = new ArrayList<>();
        out.tooltip.add("Ore dictionary: " + joined);
        if (!accepts.isEmpty()) out.tooltip.add(accepts(accepts));
        return out;
    }

    /** The normalizer's {@code choiceResource}: a cell taking one of a list of items, under the list's own id. */
    @Nullable
    private static Web.Resource choice(final CraftingRecord.Slot slot) {
        final List<Web.Resource> accepts = resources(slot.alternatives);
        if (accepts.isEmpty() || slot.choiceId == null || slot.choiceId.isEmpty()) return null;
        final Web.Resource out = new Web.Resource();
        out.kind = "item";
        out.id = slot.choiceId;
        out.amount = 1;
        out.displayName = "Item Choice";
        out.tooltip = new ArrayList<>();
        out.tooltip.add(accepts(accepts));
        return out;
    }

    private static List<Web.Resource> resources(@Nullable final List<GtRecipeRecord.Stack> stacks) {
        final List<Web.Resource> out = new ArrayList<>();
        if (stacks == null) return out;
        for (final GtRecipeRecord.Stack s : stacks) {
            final Web.Resource resource = GtDatasetRecipe.resource(s, false);
            if (resource != null) out.add(resource);
        }
        return out;
    }

    private static String accepts(final List<Web.Resource> accepts) {
        final StringBuilder line = new StringBuilder("Accepts: ");
        for (int i = 0; i < Math.min(ACCEPTS_SHOWN, accepts.size()); i++) {
            if (i > 0) line.append(", ");
            line.append(GtDatasetRecipe.label(accepts.get(i)));
        }
        if (accepts.size() > ACCEPTS_SHOWN) line.append(", +")
            .append(accepts.size() - ACCEPTS_SHOWN)
            .append(" more");
        return line.toString();
    }
}
