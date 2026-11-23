package com.gtnhplanner.core.model.read;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.gtnhplanner.machines.web.Web;

/**
 * A GregTech record as the dataset's recipe: the rules {@code normalize-oracle-export.mjs} applies to an exported
 * GregTech map recipe ({@code normalizeGregtech}), written once more in Java so a host that reads the game itself (the
 * mod) hands its maths the record the website's dataset holds for the same recipe. Proof: the shared cases in
 * {@code conformance/data/gt-records/} (raw records and the normalizer's output for them).
 *
 * <p>
 * Left to the caller: the recipe's id (each host's own identity), the machines and their settings
 * ({@code machineHandlers}, {@code machineConfigControls}: the curated machine table), and what only the dataset carries
 * (icons, colours, mod ids, ore dictionary, the slot's alternatives and use-up chance, which {@link Web.Resource} has
 * no field for; provenance; the Sparge Tower's rolled byproducts, which the mod's card does not show yet).
 */
public final class GtDatasetRecipe {

    private GtDatasetRecipe() {}

    /** The programmed circuit's item: its damage is the circuit setting. */
    public static final String PROGRAMMED_CIRCUIT = "gregtech:gt.integrated_circuit";

    /** The record as the dataset's recipe on the map named {@code machineType} (the dataset's name for the map). */
    public static Web.Recipe of(final GtRecipeRecord r, final String machineType) {
        final Web.Recipe out = new Web.Recipe();
        final List<Web.Resource> inputs = new ArrayList<>();
        for (final GtRecipeRecord.Stack s : r.itemInputs) add(inputs, withInputChance(resource(s, false), s));
        for (final GtRecipeRecord.Stack s : r.nonConsumedInputs) add(inputs, resource(s, true));
        for (final GtRecipeRecord.Stack s : r.fluidInputs) add(inputs, withInputChance(resource(s, false), s));
        final List<Web.Resource> outputs = new ArrayList<>();
        for (final GtRecipeRecord.Stack s : r.itemOutputs) add(outputs, resource(s, false));
        for (final GtRecipeRecord.Stack s : r.fluidOutputs) add(outputs, resource(s, false));

        out.name = machineType + ": " + (outputs.isEmpty() ? "unknown" : label(outputs.get(0)));
        out.kind = "gregtech_machine";
        out.category = "gregtech";
        out.machineType = machineType;
        // A map that runs on several amps names its tier by EU/t per amp, as NEI does: 48 EU/t at 2 amps is LV.
        out.minimumTier = GtRecipeRecord.TIER_NAMES[GtRecipeRecord
            .tierForEu(Math.floorDiv(r.eut, (long) Math.max(1, r.amperage)))];
        out.durationTicks = Math.max(1, r.durationTicks);
        out.eut = Math.max(0L, r.eut);
        out.inputs = inputs;
        out.outputs = outputs;
        out.runtimeCalculation = runtime(r, outputs);
        out.programmedCircuit = programmedCircuit(inputs);
        out.specialValue = (double) r.specialValue;
        out.source = new Web.Source();
        out.source.recipeMap = machineType;
        out.nei = new Web.Nei();
        out.nei.additionalInfo = new ArrayList<>(Collections.singletonList("Special value: " + r.specialValue));
        out.metadata = new LinkedHashMap<>();
        out.metadata.put("recipeMapId", r.mapId);
        out.metadata.put("specialValue", (double) r.specialValue);
        if (GtRecipeRecord.FUSION_MAP.equals(r.mapId) && r.fusionStartupEu != null)
            out.metadata.put("fusionStartupEu", (double) r.fusionStartupEu.longValue());
        return out;
    }

    private static void add(final List<Web.Resource> list, @Nullable final Web.Resource resource) {
        if (resource != null) list.add(resource);
    }

    /**
     * The normalizer's {@code resourceAmount} for an item or fluid: kind, id, amount, name, NBT line, kept flag,
     * chance. The other dataset shapes of this package read their stacks with it too.
     */
    @Nullable
    static Web.Resource resource(final GtRecipeRecord.Stack s, final boolean kept) {
        if (!"item".equals(s.kind) && !"fluid".equals(s.kind)) return null;
        final String id = s.id == null ? "" : s.id.trim();
        if (id.isEmpty()) return null;
        final Web.Resource out = new Web.Resource();
        out.kind = s.kind;
        out.id = "item".equals(s.kind) ? id.toLowerCase(Locale.ROOT) : id;
        out.amount = s.amount > 0 ? s.amount : 1;
        final String name = s.displayName == null ? "" : s.displayName.trim();
        out.displayName = name.isEmpty() ? out.id : name;
        if (s.nbt != null && !s.nbt.isEmpty()) out.tooltip = new ArrayList<>(Collections.singletonList("NBT: " + s.nbt));
        if (kept || Boolean.FALSE.equals(s.consumed)) out.consumed = Boolean.FALSE;
        if (s.chance != null && s.chance >= 0) out.chance = Math.max(0, Math.min(1, s.chance));
        return out;
    }

    /** The normalizer's {@code withInputChance}: an input used up only so often counts as that share of its amount. */
    @Nullable
    private static Web.Resource withInputChance(@Nullable final Web.Resource resource, final GtRecipeRecord.Stack s) {
        if (resource == null || Boolean.FALSE.equals(resource.consumed) || s.inputChance == null) return resource;
        final int chance = s.inputChance;
        if (chance <= 0 || chance >= 10000) return resource;
        resource.amount = resource.amount * (chance / 10000.0);
        return resource;
    }

    static String label(final Web.Resource resource) {
        return resource.displayName != null ? resource.displayName : resource.id;
    }

    /** The circuit setting the recipe needs, from its programmed circuit's damage; null when it takes none. */
    @Nullable
    public static String programmedCircuit(final List<Web.Resource> inputs) {
        for (final Web.Resource input : inputs) {
            if (!"item".equals(input.kind) || input.id == null) continue;
            final int at = input.id.lastIndexOf('@');
            final String base = at >= 0 && digits(input.id, at + 1) ? input.id.substring(0, at) : input.id;
            if (!PROGRAMMED_CIRCUIT.equals(base)) continue;
            if (base.equals(input.id)) return null;
            final String setting = input.id.substring(at + 1);
            // 32767 is "any damage", a wildcard rather than a setting.
            return "32767".equals(setting) ? null : Integer.toString(Integer.parseInt(setting));
        }
        return null;
    }

    private static boolean digits(final String text, final int from) {
        if (from >= text.length()) return false;
        for (int i = from; i < text.length(); i++) if (text.charAt(i) < '0' || text.charAt(i) > '9') return false;
        return true;
    }

    /**
     * The normalizer's {@code normalizeRuntimeCalculation}: one variant per rung, named by tier and coil, each making
     * the recipe's outputs.
     */
    private static Web.RuntimeCalculation runtime(final GtRecipeRecord r, final List<Web.Resource> outputs) {
        if (r.runtime == null) return null;
        final Web.RuntimeCalculation out = new Web.RuntimeCalculation();
        out.variants = new ArrayList<>();
        for (final GtRecipeRecord.Variant v : r.runtime.variants) {
            if (v.durationTicks <= 0 || v.eut < 0) continue;
            out.variants.add(variant(v, outputs));
        }
        out.status = out.variants.isEmpty() ? "missing" : r.runtime.status == null ? "computed" : r.runtime.status;
        out.oracleEligible = r.runtime.oracleEligible;
        out.strict = r.runtime.strict;
        return out;
    }

    private static Web.RuntimeVariant variant(final GtRecipeRecord.Variant v, final List<Web.Resource> outputs) {
        final String tier = v.tier >= 0 && v.tier < GtRecipeRecord.TIER_NAMES.length ? GtRecipeRecord.TIER_NAMES[v.tier]
            : "tier-" + v.tier;
        final String lower = tier.toLowerCase(Locale.ROOT);
        final Web.RuntimeVariant out = new Web.RuntimeVariant();
        out.id = "tier-" + lower;
        out.label = tier;
        out.overclockTier = tier;
        out.durationTicks = v.durationTicks;
        out.eut = v.eut;
        out.parallel = 1.0;
        final List<Web.Resource> made = new ArrayList<>();
        for (final Web.Resource o : outputs) {
            final Web.Resource m = new Web.Resource();
            m.kind = o.kind;
            m.id = o.id;
            m.amount = o.amount;
            m.chance = o.chance;
            made.add(m);
        }
        out.outputs = made;
        final String profile = v.profile == null ? "" : v.profile;
        final String key = v.configKey == null ? "" : v.configKey;
        if (profile.isEmpty()) return out;
        if ("ebf-heat".equals(profile) || "pyrolyse-coil".equals(profile) || "oil-cracker-coil".equals(profile)) {
            out.id = "tier-" + lower + "-coil-" + key;
            out.label = tier + " / " + coilName(key);
            out.coilTier = key;
            return out;
        }
        if ("perfect-oc".equals(profile)) {
            out.id = "tier-" + lower + "-perfect-oc";
            out.label = tier + " Perfect OC";
            return out;
        }
        out.id = "tier-" + lower + "-" + slug(profile) + (key.isEmpty() ? "" : "-" + slug(key));
        out.label = tier + " " + profile + (key.isEmpty() ? "" : " " + key);
        return out;
    }

    private static String coilName(final String key) {
        for (int i = 0; i < GtRecipeRecord.COIL_KEYS.length; i++)
            if (GtRecipeRecord.COIL_KEYS[i].equals(key)) return GtRecipeRecord.COIL_NAMES[i];
        return key;
    }

    private static String slug(final String value) {
        final StringBuilder out = new StringBuilder();
        boolean dash = false;
        for (final char c : value.toLowerCase(Locale.ROOT)
            .toCharArray()) {
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
                if (dash && out.length() > 0) out.append('-');
                out.append(c);
                dash = false;
            } else dash = true;
        }
        return out.toString();
    }
}
