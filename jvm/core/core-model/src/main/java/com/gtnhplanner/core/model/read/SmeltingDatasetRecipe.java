package com.gtnhplanner.core.model.read;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.gtnhplanner.machines.web.Web;

/**
 * A furnace record as the dataset's recipe: {@code normalize-oracle-export.mjs}'s {@code normalizeSmelting}, written
 * once more in Java so the mod's furnace card hands its maths the record the website's dataset holds. Vanilla
 * smelting is its own map, "Furnace" in category {@code furnace} (not a GregTech map): 200 ticks at no EU, which
 * GregTech's furnaces run at their own fixed recipe (RecipeRules.gtFurnaceDurationTicks). Proof: the shared cases in
 * {@code conformance/data/smelting-records.json}.
 *
 * <p>
 * Left to the caller, as for {@link GtDatasetRecipe}: the id, the machines ({@code machineHandlers}) and what only the
 * dataset carries (icons, colours, mod ids, provenance).
 */
public final class SmeltingDatasetRecipe {

    private SmeltingDatasetRecipe() {}

    /** The dataset's name for the vanilla furnace's map. */
    public static final String MACHINE_TYPE = "Furnace";

    /** The record as the dataset's recipe; null when either stack is missing, as the normalizer skips it. */
    @Nullable
    public static Web.Recipe of(final SmeltingRecord r) {
        final Web.Resource input = r.input == null ? null : GtDatasetRecipe.resource(r.input, false);
        final Web.Resource output = r.output == null ? null : GtDatasetRecipe.resource(r.output, false);
        if (input == null || output == null) return null;
        final Web.Recipe out = new Web.Recipe();
        out.name = MACHINE_TYPE + ": " + GtDatasetRecipe.label(output);
        out.kind = "gregtech_machine";
        out.category = "furnace";
        out.machineType = MACHINE_TYPE;
        out.minimumTier = "NONE";
        out.durationTicks = SmeltingRecord.FURNACE_COOK_TICKS;
        out.eut = 0;
        final List<Web.Resource> inputs = new ArrayList<>();
        inputs.add(input);
        final List<Web.Resource> outputs = new ArrayList<>();
        outputs.add(output);
        out.inputs = inputs;
        out.outputs = outputs;
        out.source = new Web.Source();
        out.source.recipeMap = MACHINE_TYPE;
        return out;
    }
}
