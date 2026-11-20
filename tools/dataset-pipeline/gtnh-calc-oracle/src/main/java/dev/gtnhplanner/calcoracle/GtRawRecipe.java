package dev.gtnhplanner.calcoracle;

import com.gtnhplanner.core.model.read.GtRecipeRecord;
import dev.gtnhplanner.calcoracle.icons.FluidStackIconExporter;
import dev.gtnhplanner.calcoracle.icons.ItemStackIconExporter;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A GregTech recipe record (read by the mod's GtRecipeReader, the reader the planner mod uses in game) written as the
 * raw export's recipe object, which normalize-oracle-export.mjs reads. Only the writing lives here: the stack icons,
 * which need the export run's renderer, and the export run's own provenance (recipe id, time).
 */
final class GtRawRecipe {

    private GtRawRecipe() {
    }

    static Map<String, Object> recipe(GtRecipeRecord r, String id, String generatedAt) {
        Map<String, Object> out = map();
        out.put("id", id);
        out.put("enabled", Boolean.TRUE);
        out.put("durationTicks", Integer.valueOf(r.durationTicks));
        out.put("eut", Long.valueOf(r.eut));
        out.put("specialValue", Integer.valueOf(r.specialValue));
        if (r.fusionStartupEu != null) {
            out.put("fusionStartupEu", r.fusionStartupEu);
        }
        out.put("itemInputs", stacks(r.itemInputs));
        out.put("itemOutputs", stacks(r.itemOutputs));
        out.put("fluidInputs", stacks(r.fluidInputs));
        out.put("fluidOutputs", stacks(r.fluidOutputs));
        if (r.spargeFluidOutputs != null) {
            List<Map<String, Object>> fluids = new ArrayList<Map<String, Object>>();
            for (GtRecipeRecord.Stack fluid : r.spargeFluidOutputs) {
                fluids.add(stack(fluid));
            }
            out.put("spargeFluidOutputs", fluids);
            if (r.spargeGasAmount != null) {
                out.put("spargeGasAmount", r.spargeGasAmount);
            }
            if (r.spargeMaxByproduct != null) {
                out.put("spargeMaxByproduct", r.spargeMaxByproduct);
            }
            if (r.spargeError != null) {
                out.put("spargeError", r.spargeError);
            }
        }
        if (r.wearError != null) {
            out.put("wearError", r.wearError);
        } else if (r.wearingInputs != null) {
            List<Map<String, Object>> wearing = new ArrayList<Map<String, Object>>();
            for (GtRecipeRecord.Wearing w : r.wearingInputs) {
                Map<String, Object> entry = map();
                entry.put("durability", w.durability);
                if (w.spent != null) {
                    entry.put("spent", stack(w.spent));
                }
                entry.put("id", w.id);
                wearing.add(entry);
            }
            out.put("wearingInputs", wearing);
        }
        out.put("nonConsumedInputs", stacks(r.nonConsumedInputs));
        out.put("runtimeCalculation", runtime(r, generatedAt));
        Map<String, Object> metadata = map();
        if (r.coilHeat != 0) metadata.put("coilHeat", Integer.valueOf(r.coilHeat));
        if (r.glassTier != 3) metadata.put("glassTier", Integer.valueOf(r.glassTier));
        if (r.sievert != 0) metadata.put("sievert", Integer.valueOf(r.sievert));
        if (r.sievertExact) metadata.put("sievertExact", Boolean.TRUE);
        if (r.mass != 0) metadata.put("mass", Integer.valueOf(r.mass));
        if (!metadata.isEmpty()) {
            out.put("metadata", metadata);
        }
        return out;
    }

    private static Map<String, Object> runtime(GtRecipeRecord r, String generatedAt) {
        GtRecipeRecord.Runtime runtime = r.runtime;
        Map<String, Object> out = map();
        out.put("sourceKind", "gregtech-overclock-calculator");
        out.put("sourceClass", "gregtech.api.util.OverclockCalculator");
        out.put("recipeMap", r.mapName);
        out.put("status", runtime.status);
        out.put("oracleEligible", Boolean.valueOf(runtime.oracleEligible));
        out.put("strict", Boolean.valueOf(runtime.strict));
        out.put("generatedAt", generatedAt);
        List<List<Object>> variants = new ArrayList<List<Object>>();
        for (GtRecipeRecord.Variant v : runtime.variants) {
            List<Object> compact = new ArrayList<Object>(Arrays.<Object>asList(
                Integer.valueOf(v.tier),
                Integer.valueOf(v.durationTicks),
                Long.valueOf(v.eut)
            ));
            if (v.profile != null) {
                compact.add(v.profile);
                if (v.configKey != null) {
                    compact.add(v.configKey);
                }
            }
            variants.add(compact);
        }
        out.put("variantFormat", "tierIndex,durationTicks,eut[,profile,configKey]");
        out.put("compactVariants", variants);
        if (!"standard".equals(runtime.profile)) {
            out.put("profile", runtime.profile);
        }
        if (runtime.warnings != null) {
            out.put("warnings", runtime.warnings);
        }
        return out;
    }

    private static List<Map<String, Object>> stacks(List<GtRecipeRecord.Stack> stacks) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (GtRecipeRecord.Stack s : stacks) {
            out.add(stack(s));
        }
        return out;
    }

    static Map<String, Object> stack(GtRecipeRecord.Stack s) {
        Map<String, Object> out = map();
        out.put("kind", s.kind);
        out.put("id", s.id);
        if (s.isItem()) {
            out.put("registryId", s.registryId);
            out.put("meta", Integer.valueOf(s.meta));
        }
        out.put("amount", Integer.valueOf(s.amount));
        out.put("displayName", s.displayName);
        if (s.modId != null) out.put("modId", s.modId);
        if (s.nbt != null) out.put("nbt", s.nbt);
        String icon = icon(s);
        if (icon != null && icon.length() > 0) out.put("icon", icon);
        if (s.consumed != null) out.put("consumed", s.consumed);
        if (s.inputChance != null) out.put("inputChance", s.inputChance);
        if (s.chance != null) out.put("chance", s.chance);
        if (s.alternatives != null) out.put("alternatives", stacks(s.alternatives));
        return out;
    }

    private static String icon(GtRecipeRecord.Stack s) {
        if (s.host instanceof ItemStack) {
            ItemStack stack = (ItemStack) s.host;
            if (stack.stackSize > 0) return ItemStackIconExporter.captureIcon(stack);
            // A kept slot (a programmed circuit) is a 0-sized stack: its picture is the one-item stack's.
            ItemStack shown = stack.copy();
            shown.stackSize = 1;
            return ItemStackIconExporter.captureIcon(shown);
        }
        if (s.host instanceof FluidStack) {
            // A sparge byproduct registers at 0 L; its picture is the litre's.
            FluidStack shown = ((FluidStack) s.host).copy();
            shown.amount = Math.max(1, shown.amount);
            return FluidStackIconExporter.captureIcon(shown);
        }
        return null;
    }

    private static Map<String, Object> map() {
        return new LinkedHashMap<String, Object>();
    }
}
