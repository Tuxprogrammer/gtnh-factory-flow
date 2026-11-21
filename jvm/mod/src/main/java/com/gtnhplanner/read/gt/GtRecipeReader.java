package com.gtnhplanner.read.gt;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.StatCollector;
import net.minecraftforge.fluids.FluidStack;

import com.gtnhplanner.core.model.read.GtRecipeRecord;
import com.gtnhplanner.core.model.read.GtRecipeRecord.Runtime;
import com.gtnhplanner.core.model.read.GtRecipeRecord.Stack;
import com.gtnhplanner.core.model.read.GtRecipeRecord.Variant;
import com.gtnhplanner.core.model.read.GtRecipeRecord.Wearing;

import gregtech.api.recipe.RecipeMap;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.GTRecipeConstants;
import gregtech.api.util.recipe.Sievert;
import gregtech.common.items.ItemFluidDisplay;

/**
 * The one reader from a GregTech recipe to {@link GtRecipeRecord}, for both hosts: the export run (the dataset
 * pipeline's {@code gtnh-calc-oracle}, which compiles this package into its own jar) reads every map with it, and the
 * mod reads a card's recipe with it (GTProvider, the importer's GtRecipeSource). What each field means is the
 * record's; how each is read is here, once.
 *
 * <p>
 * Written for Java 8's library (the export run compiles it with Jabel). Members that differ between GregTech builds
 * are reached by reflection, the way the exporter always reached them. Not thread-safe; one reader per thread.
 */
public final class GtRecipeReader {

    /** The Sparge Tower's map: its rolled byproducts are read beside the recipe. */
    public static final String SPARGE_MAP = "gtpp.recipe.lftr.sparging";

    private final RockBreakerDurations rockBreaker;
    private final ScannerDurations scanner;
    private boolean unifiedResolved;
    private Method unified;
    private boolean calculatorResolved;
    private Class<?> calculator;

    /** A reader with the Rock Breaker's and the Scanner's own lists read from the loaded game. */
    public GtRecipeReader() {
        rockBreaker = RockBreakerDurations.load();
        scanner = ScannerDurations.load();
    }

    /** The map's NEI name in the game's language; its id when it has none. */
    public static String mapName(final RecipeMap<?> map) {
        final String id = map.unlocalizedName;
        final String name = StatCollector.translateToLocal(id);
        return name == null || name.length() == 0 || name.equals(id) ? id : name;
    }

    /** One recipe of {@code map} as the game holds it. */
    public GtRecipeRecord read(final RecipeMap<?> map, final GTRecipe recipe) {
        final GtRecipeRecord r = new GtRecipeRecord();
        r.mapId = map.unlocalizedName == null ? "" : map.unlocalizedName;
        r.mapName = mapName(map);
        r.amperage = Math.max(1, map.getAmperage());
        int duration = recipe.mDuration;
        // The Rock Breaker and the Scanner run their own lists, not the display copies in their maps.
        if (RockBreakerDurations.MAP_ID.equals(r.mapId)) {
            final int machine = rockBreaker.durationFor(recipe);
            if (machine >= 0) duration = machine;
        }
        if (ScannerDurations.MAP_ID.equals(r.mapId)) {
            final int machine = scanner.durationFor(recipe);
            if (machine >= 0) duration = machine;
        }
        // BartWorks makes some recipes of 0 ticks (a werkstoff with no mass); the game runs them in one.
        r.durationTicks = Math.max(1, duration);
        r.eut = recipe.mEUt;
        r.specialValue = recipe.mSpecialValue;
        // The fusion map's recipes always carry their start-up EU; another map's only when it sets one.
        final Long startup = recipe.getMetadataOrDefault(GTRecipeConstants.FUSION_THRESHOLD, 0L);
        if (GtRecipeRecord.FUSION_MAP.equals(r.mapId) || startup != null && startup.longValue() != 0L)
            r.fusionStartupEu = startup;
        readMetadata(r, recipe);

        final List<Integer> itemSlots = new ArrayList<Integer>();
        final List<Integer> fluidSlots = new ArrayList<Integer>();
        readItemInputs(r.itemInputs, recipe, itemSlots);
        readFluids(r.fluidInputs, recipe.mFluidInputs, recipe.mFluidInputChances, true, fluidSlots);
        attachSlotAlternatives(r.itemInputs, itemSlots, readField(recipe, "mOreDictAlt"), false);
        attachSlotAlternatives(r.fluidInputs, fluidSlots, readField(recipe, "mAltFluidInputs"), true);
        attachUnifiedItemAlternatives(r.itemInputs, itemSlots, recipe.mInputs);
        readItemOutputs(r.itemOutputs, recipe);
        readFluids(r.fluidOutputs, recipe.mFluidOutputs, recipe.mFluidOutputChances, false, null);
        // Outputs GregTech lists from a hash map, in a new order every run, sorted so a read is the same every run:
        // the Eye of Harmony's plasmas (EyeOfHarmonyRecipe builds them from a HashMap), and the products of GregTech's
        // copies of Forestry's centrifuge recipes (GTForestryCompat.transferCentrifugeRecipes walks Forestry's
        // product map), most likely first.
        if (EYE_OF_HARMONY_MAP.equals(r.mapId)) {
            Collections.sort(r.fluidOutputs, BY_ID);
            sortEyeOfHarmonyItems(r.itemOutputs, recipe.mSpecialItems);
        }
        if (isForestryCentrifugeCopy(r.mapId, recipe)) Collections.sort(r.itemOutputs, BY_CHANCE_THEN_ID);
        // The Crop Breeder's catalyst cells list blocks CropsNH keeps in hash sets (SubSoilRequirement's MetaSet), and
        // GregTech shows the first: the cell's item is the first by id, the others follow by id.
        if (CROP_BREEDER_MAP.equals(r.mapId))
            for (int i = 0; i < r.itemInputs.size(); i++) r.itemInputs.set(i, firstById(r.itemInputs.get(i)));
        for (final Object value : iterable(recipe.mSpecialItems)) {
            if (!(value instanceof ItemStack)) continue;
            final Stack item = itemStack((ItemStack) value);
            if (item == null) continue;
            item.consumed = Boolean.FALSE;
            r.nonConsumedInputs.add(item);
        }
        if (SPARGE_MAP.equals(r.mapId)) readSparge(r, recipe);
        if ("gtpp.recipe.fluidchemicaleactor".equals(r.mapId) || "gtpp.recipe.oremill".equals(r.mapId))
            readWearing(r, recipe);
        r.runtime = runtime(r);
        return r;
    }

    // ---------------------------------------------------------------- stacks

    /**
     * Item inputs. GregTech marks "this slot must hold this, and keeps it" by a stack size of 0 (the programmed
     * circuit: {@code getIntegratedCircuit(n)} is a 0-sized stack whose damage is the setting) or an input chance of
     * 0; a chance between 0 and 10000 uses the input up that often. GregTech's fluid display items are NEI's drawing
     * of a fluid, never an item a machine holds, and are left out.
     */
    private void readItemInputs(final List<Stack> out, final GTRecipe recipe, final List<Integer> slots) {
        final ItemStack[] stacks = recipe.mInputs;
        if (stacks == null) return;
        final int[] chances = recipe.mInputChances;
        for (int index = 0; index < stacks.length; index++) {
            final ItemStack stack = stacks[index];
            if (stack == null || stack.getItem() == null || stack.getItem() instanceof ItemFluidDisplay) continue;
            final Stack item;
            if (stack.stackSize > 0) item = itemStack(stack);
            else {
                final ItemStack present = stack.copy();
                present.stackSize = 1;
                item = itemStack(present);
                if (item != null) item.consumed = Boolean.FALSE;
            }
            if (item == null) continue;
            final int chance = chances == null || index >= chances.length ? 10000 : chances[index];
            if (chance <= 0) item.consumed = Boolean.FALSE;
            else if (chance < 10000) item.inputChance = Integer.valueOf(chance);
            out.add(item);
            slots.add(Integer.valueOf(index));
        }
    }

    /** Item outputs, each with GregTech's chance when it is not certain. */
    private void readItemOutputs(final List<Stack> out, final GTRecipe recipe) {
        final ItemStack[] stacks = recipe.mOutputs;
        if (stacks == null) return;
        for (int index = 0; index < stacks.length; index++) {
            final ItemStack stack = stacks[index];
            if (stack != null && stack.getItem() instanceof ItemFluidDisplay) continue;
            final Stack item = itemStack(stack);
            if (item == null) continue;
            // GregTech makes an output when its roll is under the chance (rollOutput): 0 is never.
            final int chance = recipe.getOutputChance(index);
            if (chance < 10000) item.chance = Double.valueOf(Math.max(0, chance) / 10000.0D);
            out.add(item);
        }
    }

    /**
     * Fluids in slot order, empty slots left out. GregTech rolls a fluid's chance as it rolls an item's
     * (ParallelHelper):
     * an input's chance is how often it is used up (0: kept), an output's how often it is made.
     */
    private void readFluids(final List<Stack> out, final FluidStack[] stacks, final int[] chances, final boolean inputs,
        final List<Integer> slots) {
        if (stacks == null) return;
        for (int index = 0; index < stacks.length; index++) {
            final Stack fluid = fluidStack(stacks[index]);
            if (fluid == null) continue;
            final int chance = chances == null || index >= chances.length ? 10000 : chances[index];
            if (inputs) {
                if (chance <= 0) fluid.consumed = Boolean.FALSE;
                else if (chance < 10000) fluid.inputChance = Integer.valueOf(chance);
            } else if (chance < 10000) fluid.chance = Double.valueOf(Math.max(0, chance) / 10000.0D);
            out.add(fluid);
            if (slots != null) slots.add(Integer.valueOf(index));
        }
    }

    /** An item stack as a slot; null when it is empty or its item has no registry name. */
    public static Stack itemStack(final ItemStack stack) {
        if (stack == null || stack.getItem() == null || stack.stackSize <= 0) return null;
        final String registryId = String.valueOf(Item.itemRegistry.getNameForObject(stack.getItem()));
        if (registryId.length() == 0 || "null".equals(registryId)) return null;
        final Stack item = new Stack();
        final int meta = stack.getItemDamage();
        item.kind = "item";
        item.id = itemId(registryId, meta);
        item.registryId = registryId;
        item.meta = meta;
        item.amount = stack.stackSize;
        item.displayName = displayName(stack, registryId);
        final int separator = registryId.indexOf(':');
        if (separator > 0) item.modId = registryId.substring(0, separator);
        if (stack.stackTagCompound != null) item.nbt = nbtText(stack.stackTagCompound);
        item.host = stack;
        return item;
    }

    /**
     * An item's NBT as the game prints it, with each compound's keys in one fixed order. The game prints a compound in
     * its HashMap's order, and two keys sharing a bucket come out in the order they were set, which differs from one
     * recipe (and one run) to the next for the same tag: {mDataName:..,mDataTitle:..} and {mDataTitle:..,mDataName:..}.
     * A copy that sets its keys sorted prints the same text for the same tag every time.
     */
    public static String nbtText(final NBTTagCompound tag) {
        return canonical(tag).toString();
    }

    private static NBTTagCompound canonical(final NBTTagCompound tag) {
        final List<String> keys = new ArrayList<String>();
        for (final Object key : tag.func_150296_c()) keys.add(String.valueOf(key));
        Collections.sort(keys);
        final NBTTagCompound out = new NBTTagCompound();
        for (final String key : keys) out.setTag(key, canonicalValue(tag.getTag(key)));
        return out;
    }

    private static NBTBase canonicalValue(final NBTBase value) {
        if (value instanceof NBTTagCompound) return canonical((NBTTagCompound) value);
        if (value instanceof NBTTagList && ((NBTTagList) value).func_150303_d() == 10) {
            final NBTTagList list = (NBTTagList) value;
            final NBTTagList out = new NBTTagList();
            for (int i = 0; i < list.tagCount(); i++) out.appendTag(canonical(list.getCompoundTagAt(i)));
            return out;
        }
        return value.copy();
    }

    /** A fluid stack as a slot; null when it is empty. */
    public static Stack fluidStack(final FluidStack stack) {
        if (stack == null || stack.getFluid() == null || stack.amount <= 0) return null;
        final Stack fluid = new Stack();
        fluid.kind = "fluid";
        fluid.id = stack.getFluid()
            .getName();
        fluid.amount = stack.amount;
        fluid.displayName = stack.getLocalizedName();
        fluid.host = stack;
        return fluid;
    }

    /** The planner's item id: the registry name lowercased, {@code @meta} when the damage is not 0. */
    public static String itemId(final String registryId, final int meta) {
        final String normalized = registryId.trim();
        final int separator = normalized.indexOf(':');
        final String id = separator < 0 ? normalized.toLowerCase(Locale.ROOT)
            : normalized.substring(0, separator)
                .toLowerCase(Locale.ROOT) + ":"
                + normalized.substring(separator + 1)
                    .toLowerCase(Locale.ROOT);
        return meta == 0 ? id : id + "@" + meta;
    }

    private static String displayName(final ItemStack stack, final String registryId) {
        try {
            return stack.getDisplayName();
        } catch (final Throwable ignored) {
            return registryId + "@" + stack.getItemDamage() + "x" + stack.stackSize;
        }
    }

    // ---------------------------------------------------------------- what else a slot takes

    /**
     * What else a slot accepts, from the recipe's own per-slot lists: {@code mOreDictAlt} (items, on the GTRecipe
     * subclass some recipes use) and {@code mAltFluidInputs} (the Circuit Assembler's soldering alloy, tin or lead).
     * Each list includes the slot's own stack, so one entry is no choice and is left out. GregTech fills an item list
     * from ore dictionary and unification sets whose order is new every run, so the slot's own item comes first and the
     * rest are sorted by id; a fluid list is the recipe's own, written in code, and keeps its order.
     */
    private static void attachSlotAlternatives(final List<Stack> exported, final List<Integer> slots, final Object raw,
        final boolean fluids) {
        if (raw == null || !raw.getClass()
            .isArray()) return;
        final int length = Array.getLength(raw);
        for (int position = 0; position < exported.size(); position++) {
            final int slot = slots.get(position)
                .intValue();
            if (slot >= length) continue;
            final Object perSlot = Array.get(raw, slot);
            if (perSlot == null || !perSlot.getClass()
                .isArray()) continue;
            final List<Stack> alternatives = new ArrayList<Stack>();
            final int count = Array.getLength(perSlot);
            for (int index = 0; index < count; index++) {
                final Object entry = Array.get(perSlot, index);
                final Stack resource = fluids ? (entry instanceof FluidStack ? fluidStack((FluidStack) entry) : null)
                    : (entry instanceof ItemStack ? itemStack((ItemStack) entry) : null);
                if (resource != null && !containsId(alternatives, resource.id)) alternatives.add(resource);
            }
            if (alternatives.size() > 1) exported.get(position).alternatives = fluids ? alternatives
                : ownFirst(alternatives, exported.get(position));
        }
    }

    /** The slot's own item first (when the list holds it), then the others by id. */
    private static List<Stack> ownFirst(final List<Stack> alternatives, final Stack own) {
        final List<Stack> out = new ArrayList<Stack>();
        final List<Stack> rest = new ArrayList<Stack>();
        for (final Stack s : alternatives) {
            if (out.isEmpty() && s.id.equals(own.id)) out.add(s);
            else rest.add(s);
        }
        Collections.sort(rest, BY_ID);
        out.addAll(rest);
        return out;
    }

    /**
     * The items GregTech treats as the slot's own item (two resistors take two SMD resistors), asked the way NEI asks
     * for its cycling slots: {@code GTOreDictUnificator.getNonUnifiedStacks}. Its rule, not the ore dictionary's: a
     * vacuum tube shares a group with the NAND chip and the machine takes neither for the other. The game builds that
     * list from a hash map whose order is new every run, so the added items are sorted by id.
     */
    private void attachUnifiedItemAlternatives(final List<Stack> exported, final List<Integer> slots,
        final ItemStack[] inputs) {
        final Method method = unifiedStacksMethod();
        if (inputs == null || method == null) return;
        for (int position = 0; position < exported.size(); position++) {
            final int slot = slots.get(position)
                .intValue();
            if (slot >= inputs.length || inputs[slot] == null) continue;
            final List<?> variants;
            try {
                variants = (List<?>) method.invoke(null, new Object[] { inputs[slot] });
            } catch (final Throwable ignored) {
                return;
            }
            if (variants == null || variants.size() < 2) continue;
            final Stack target = exported.get(position);
            final List<Stack> alternatives = target.alternatives != null ? target.alternatives
                : new ArrayList<Stack>(Collections.singletonList(selfCopy(target)));
            final List<Stack> added = new ArrayList<Stack>();
            for (final Object entry : variants) {
                if (!(entry instanceof ItemStack)) continue;
                final Stack resource = itemStack((ItemStack) entry);
                if (resource != null && !containsId(alternatives, resource.id) && !containsId(added, resource.id))
                    added.add(resource);
            }
            Collections.sort(added, BY_ID);
            alternatives.addAll(added);
            if (alternatives.size() > 1) target.alternatives = alternatives;
        }
    }

    /** The Eye of Harmony's map. */
    public static final String EYE_OF_HARMONY_MAP = "gt.recipe.eyeofharmony";
    /** GregTech's centrifuge map and GT++'s multiblock centrifuge map, which holds the same recipes. */
    private static final String CENTRIFUGE_MAP = "gt.recipe.centrifuge",
        MULTI_CENTRIFUGE_MAP = "gt.recipe.multicentrifuge";

    /** CropsNH's Crop Breeder map. */
    private static final String CROP_BREEDER_MAP = "cropsnh.recipes.cropBreeder";

    /**
     * The Eye of Harmony's items in the game's own order, most made first (EyeOfHarmonyRecipe sorts them by amount),
     * with equal amounts by id: the game leaves those in its TMap's hash order.
     */
    private static void sortEyeOfHarmonyItems(final List<Stack> outputs, final Object special) {
        final Map<String, Long> made = new java.util.HashMap<String, Long>();
        try {
            for (final Object entry : iterable(
                special.getClass()
                    .getMethod("getOutputItems")
                    .invoke(special))) {
                final Object stack = entry.getClass()
                    .getField("itemStack")
                    .get(entry);
                final Object size = entry.getClass()
                    .getField("stackSize")
                    .get(entry);
                if (stack instanceof ItemStack && size instanceof Long) {
                    final Stack read = itemStack((ItemStack) stack);
                    if (read != null) made.put(read.id, (Long) size);
                }
            }
        } catch (final Throwable ignored) {
            // No amounts: the items go by id alone.
        }
        Collections.sort(outputs, new Comparator<Stack>() {

            @Override
            public int compare(final Stack left, final Stack right) {
                final Long l = made.get(left.id), r = made.get(right.id);
                final int byAmount = Long.compare(r == null ? 0L : r.longValue(), l == null ? 0L : l.longValue());
                return byAmount != 0 ? byAmount : left.id.compareTo(right.id);
            }
        });
    }

    /** A cell with alternatives as its first alternative by id, the rest after it by id; else the cell itself. */
    private static Stack firstById(final Stack cell) {
        if (cell.alternatives == null || cell.alternatives.size() < 2) return cell;
        final List<Stack> sorted = new ArrayList<Stack>(cell.alternatives);
        Collections.sort(sorted, BY_ID);
        final Stack first = selfCopy(sorted.get(0));
        first.amount = cell.amount;
        first.consumed = cell.consumed;
        first.inputChance = cell.inputChance;
        first.chance = cell.chance;
        first.alternatives = sorted;
        return first;
    }

    /** The items Forestry's centrifuge takes, as registry name and damage; read once, null until then. */
    private java.util.Set<String> forestryCentrifugeInputs;

    /** Whether {@code recipe} is GregTech's copy of a Forestry centrifuge recipe: one item in, that Forestry takes. */
    private boolean isForestryCentrifugeCopy(final String mapId, final GTRecipe recipe) {
        if (!CENTRIFUGE_MAP.equals(mapId) && !MULTI_CENTRIFUGE_MAP.equals(mapId)) return false;
        if (recipe.mInputs == null || recipe.mInputs.length != 1 || recipe.mInputs[0] == null) return false;
        if (forestryCentrifugeInputs == null) {
            forestryCentrifugeInputs = new java.util.HashSet<String>();
            try {
                final Object manager = Class.forName("forestry.api.recipes.RecipeManagers")
                    .getField("centrifugeManager")
                    .get(null);
                for (final Object entry : iterable(
                    manager.getClass()
                        .getMethod("recipes")
                        .invoke(manager))) {
                    final Object input = entry.getClass()
                        .getMethod("getInput")
                        .invoke(entry);
                    if (input instanceof ItemStack) forestryCentrifugeInputs.add(itemKey((ItemStack) input));
                }
            } catch (final Throwable ignored) {
                // No Forestry: no copies to find.
            }
        }
        return forestryCentrifugeInputs.contains(itemKey(recipe.mInputs[0]));
    }

    private static String itemKey(final ItemStack stack) {
        return stack.getItem() == null ? ""
            : Item.itemRegistry.getNameForObject(stack.getItem()) + "@" + stack.getItemDamage();
    }

    private static final Comparator<Stack> BY_CHANCE_THEN_ID = new Comparator<Stack>() {

        @Override
        public int compare(final Stack left, final Stack right) {
            final double l = left.chance == null ? 1.0D : left.chance.doubleValue();
            final double r = right.chance == null ? 1.0D : right.chance.doubleValue();
            final int byChance = Double.compare(r, l);
            return byChance != 0 ? byChance : left.id.compareTo(right.id);
        }
    };

    private static final Comparator<Stack> BY_ID = new Comparator<Stack>() {

        @Override
        public int compare(final Stack left, final Stack right) {
            return left.id.compareTo(right.id);
        }
    };

    private Method unifiedStacksMethod() {
        if (!unifiedResolved) {
            unifiedResolved = true;
            try {
                unified = Class.forName("gregtech.api.util.GTOreDictUnificator")
                    .getMethod("getNonUnifiedStacks", Object.class);
            } catch (final Throwable ignored) {
                unified = null;
            }
        }
        return unified;
    }

    private static Stack selfCopy(final Stack s) {
        final Stack c = new Stack();
        c.kind = s.kind;
        c.id = s.id;
        c.registryId = s.registryId;
        c.meta = s.meta;
        c.amount = s.amount;
        c.displayName = s.displayName;
        c.modId = s.modId;
        c.nbt = s.nbt;
        c.consumed = s.consumed;
        c.inputChance = s.inputChance;
        c.chance = s.chance;
        c.host = s.host;
        return c;
    }

    private static boolean containsId(final List<Stack> stacks, final String id) {
        for (final Stack s : stacks) if (id.equals(s.id)) return true;
        return false;
    }

    // ---------------------------------------------------------------- metadata and extras

    /** Coil heat, glass tier, sieverts and mass, as the mod's machine models read them; any GregTech lacks stays 0. */
    private static void readMetadata(final GtRecipeRecord r, final GTRecipe recipe) {
        try {
            r.coilHeat = recipe.getMetadataOrDefault(GTRecipeConstants.COIL_HEAT, 0);
        } catch (final Throwable ignored) {}
        try {
            r.glassTier = recipe.getMetadataOrDefault(GTRecipeConstants.GLASS, 3);
        } catch (final Throwable ignored) {}
        try {
            final Sievert sievert = recipe.getMetadataOrDefault(GTRecipeConstants.SIEVERT, new Sievert(0, false));
            r.sievert = sievert.sievert;
            r.sievertExact = sievert.isExact;
        } catch (final Throwable ignored) {}
        try {
            r.mass = recipe.getMetadataOrDefault(GTRecipeConstants.MASS, 0);
        } catch (final Throwable ignored) {}
    }

    /** The Sparge Tower's byproducts: every fluid output, the gas it spends, and how many it rolls at most. */
    private static void readSparge(final GtRecipeRecord r, final GTRecipe recipe) {
        final List<Stack> fluids = new ArrayList<Stack>();
        if (recipe.mFluidOutputs != null) for (final FluidStack stack : recipe.mFluidOutputs) {
            if (stack == null || stack.getFluid() == null) continue;
            final Stack fluid = new Stack();
            fluid.kind = "fluid";
            fluid.id = stack.getFluid()
                .getName();
            fluid.amount = stack.amount;
            fluid.displayName = stack.getLocalizedName();
            fluid.host = stack;
            fluids.add(fluid);
        }
        r.spargeFluidOutputs = fluids;
        if (recipe.mFluidInputs != null && recipe.mFluidInputs.length > 0 && recipe.mFluidInputs[0] != null)
            r.spargeGasAmount = Integer.valueOf(recipe.mFluidInputs[0].amount);
        try {
            final Object key = GTRecipeConstants.class.getField("SPARGE_MAX_BYPRODUCT")
                .get(null);
            for (final Method method : GTRecipe.class.getMethods()) {
                if ("getMetadataOrDefault".equals(method.getName()) && method.getParameterTypes().length == 2) {
                    final Object value = method.invoke(recipe, key, Integer.valueOf(0));
                    if (value instanceof Number) r.spargeMaxByproduct = Integer.valueOf(((Number) value).intValue());
                    break;
                }
            }
        } catch (final Throwable t) {
            r.spargeError = t.toString();
        }
    }

    /**
     * Inputs a machine wears down instead of eating whole: GT++ chemical plant catalysts and IsaMill milling balls lose
     * one durability per damage roll and break at their maximum (a catalyst leaving an Empty Catalyst Carrier).
     */
    private static void readWearing(final GtRecipeRecord r, final GTRecipe recipe) {
        final List<Wearing> wearing = new ArrayList<Wearing>();
        try {
            final Class<?> chemicalPlant = Class.forName(
                "gtPlusPlus.xmod.gregtech.common.tileentities.machines.multi.production.chemplant.MTEChemicalPlant");
            final Class<?> isaMill = Class
                .forName("gtPlusPlus.xmod.gregtech.common.tileentities.machines.multi.processing.MTEIsaMill");
            final Class<?> chemBase = Class.forName("gtPlusPlus.core.item.chemistry.general.ItemGenericChemBase");
            final Method isCatalyst = chemicalPlant.getMethod("isCatalyst", ItemStack.class);
            final Method isMillingBall = isaMill.getMethod("isMillingBall", ItemStack.class);
            for (final ItemStack stack : recipe.mInputs) {
                if (stack == null || stack.getItem() == null) continue;
                final ItemStack probe = stack.copy();
                probe.stackSize = 1;
                Wearing entry = null;
                if (Boolean.TRUE.equals(isCatalyst.invoke(null, probe))) {
                    entry = new Wearing();
                    entry.durability = chemBase.getMethod("getMaxCatalystDurability")
                        .invoke(null);
                    final Object carrier = Class.forName("gtPlusPlus.xmod.gregtech.api.enums.GregtechItemList")
                        .getField("EmptyCatalystCarrier")
                        .get(null);
                    final Object spent = carrier.getClass()
                        .getMethod("get", long.class, Object[].class)
                        .invoke(carrier, Long.valueOf(1L), new Object[0]);
                    if (spent instanceof ItemStack) entry.spent = itemStack((ItemStack) spent);
                } else if (Boolean.TRUE.equals(isMillingBall.invoke(null, probe))) {
                    entry = new Wearing();
                    entry.durability = chemBase.getMethod("getMaxBallDurability", ItemStack.class)
                        .invoke(null, probe);
                }
                if (entry != null) {
                    entry.id = itemId(
                        String.valueOf(Item.itemRegistry.getNameForObject(probe.getItem())),
                        probe.getItemDamage());
                    wearing.add(entry);
                }
            }
        } catch (final Throwable t) {
            r.wearError = t.toString();
            return;
        }
        if (!wearing.isEmpty()) r.wearingInputs = wearing;
    }

    // ---------------------------------------------------------------- the overclock ladder

    /**
     * The recipe at every tier from its own up, as GregTech's OverclockCalculator runs it: the tier's voltage times the
     * map's amperage (a single block hands the calculator that, EUOverclockDescriber.createCalculator), one parallel,
     * and the map's own profile: the Electric Blast Furnace's heat bonuses for every coil hot enough, the Pyrolyse
     * Oven's and the Oil Cracker's coil bonuses, the Large Chemical Reactor's perfect overclocks. A tier the calculator
     * refuses is left out. The recipe's minimum tier counts EU/t per amp, as NEI and the machine do.
     */
    private Runtime runtime(final GtRecipeRecord r) {
        final Runtime out = new Runtime();
        out.profile = profile(r.mapId, r.mapName);
        final int minimumTier = GtRecipeRecord.tierForEu(r.eut / r.amperage);
        final int heat = r.specialValue;
        for (int tier = minimumTier; tier < GtRecipeRecord.TIER_VOLTAGES.length; tier++) {
            if ("blast-furnace-heat".equals(out.profile)) {
                for (int coil = 0; coil < GtRecipeRecord.COIL_HEATS.length; coil++) {
                    final int machineHeat = GtRecipeRecord.COIL_HEATS[coil] + 100 * (tier - 2);
                    if (heat > machineHeat) continue;
                    add(out, variant(r, tier, false, heat, machineHeat, true, 0.0D, 0.0D), "ebf-heat", coil);
                }
                continue;
            }
            if ("pyrolyse-coil".equals(out.profile)) {
                for (int coil = 0; coil < GtRecipeRecord.COIL_HEATS.length; coil++)
                    add(out, variant(r, tier, false, -1, -1, false, 2.0D / (1.0D + coil), 0.0D), "pyrolyse-coil", coil);
                continue;
            }
            if ("oil-cracker-coil".equals(out.profile)) {
                for (int coil = 0; coil < GtRecipeRecord.COIL_HEATS.length; coil++) {
                    final double discount = 1.0D - Math.min(0.1D * (coil + 1), 0.5D);
                    add(out, variant(r, tier, false, -1, -1, false, 0.0D, discount), "oil-cracker-coil", coil);
                }
                continue;
            }
            if ("large-chemical-reactor-perfect".equals(out.profile)) {
                add(out, variant(r, tier, true, -1, -1, false, 0.0D, 0.0D), "perfect-oc", -1);
                continue;
            }
            add(out, variant(r, tier, false, -1, -1, false, 0.0D, 0.0D), null, -1);
        }
        if (out.variants.isEmpty()) {
            out.status = "missing";
            final List<String> warnings = new ArrayList<String>();
            warnings.add("OverclockCalculator runtime invocation did not return any variant.");
            if ("blast-furnace-heat".equals(out.profile) && heat > maxBlastFurnaceHeat()) {
                out.oracleEligible = false;
                out.strict = false;
                warnings.add(
                    "Recipe heat " + heat
                        + " K exceeds the exported EBF/Volcanus heat profiles; a Godforge/high-heat blast adapter is required.");
            }
            out.warnings = warnings;
        }
        return out;
    }

    private static void add(final Runtime out, final Variant variant, final String profile, final int coil) {
        if (variant == null) return;
        variant.profile = profile;
        if (coil >= 0) variant.configKey = GtRecipeRecord.COIL_KEYS[coil];
        out.variants.add(variant);
    }

    private static int maxBlastFurnaceHeat() {
        int max = 0;
        for (final int coil : GtRecipeRecord.COIL_HEATS)
            for (int tier = 0; tier < GtRecipeRecord.TIER_NAMES.length; tier++)
                max = Math.max(max, coil + 100 * (tier - 2));
        return max;
    }

    /** The map's ladder profile, by its id or its English name. */
    public static String profile(final String mapId, final String mapName) {
        final String id = mapId == null ? "" : mapId.toLowerCase(Locale.ROOT);
        final String name = mapName == null ? "" : mapName.toLowerCase(Locale.ROOT);
        if (id.equals("gt.recipe.blastfurnace") || name.equals("blast furnace")) return "blast-furnace-heat";
        if (id.equals("gt.recipe.pyro") || name.equals("pyrolyse oven")) return "pyrolyse-coil";
        if (id.equals("gt.recipe.craker") || id.equals("gt.recipe.cracker") || name.equals("oil cracker"))
            return "oil-cracker-coil";
        if (id.equals("gt.recipe.largechemicalreactor") || name.equals("large chemical reactor"))
            return "large-chemical-reactor-perfect";
        return "standard";
    }

    /** One rung through GregTech's calculator, or null when it refuses (reached by reflection: its setters moved). */
    private Variant variant(final GtRecipeRecord r, final int tier, final boolean perfectOc, final int recipeHeat,
        final int machineHeat, final boolean heatOc, final double durationModifier, final double eutDiscount) {
        try {
            final Class<?> type = calculatorClass();
            if (type == null) return null;
            final Object calc = type.getConstructor()
                .newInstance();
            call(calc, "setRecipeEUt", Long.TYPE, Long.valueOf(Math.max(0L, r.eut)));
            final long voltage = GtRecipeRecord.TIER_VOLTAGES[tier];
            // MAX is Long.MAX_VALUE volts: a plain product would wrap negative and never overclock.
            call(
                calc,
                "setEUt",
                Long.TYPE,
                Long.valueOf(voltage > Long.MAX_VALUE / r.amperage ? Long.MAX_VALUE : voltage * r.amperage));
            call(calc, "setDuration", Integer.TYPE, Integer.valueOf(Math.max(1, r.durationTicks)));
            call(calc, "setParallel", Integer.TYPE, Integer.valueOf(1));
            if (perfectOc) call(calc, "enablePerfectOC", null, null);
            if (recipeHeat >= 0) call(calc, "setRecipeHeat", Integer.TYPE, Integer.valueOf(recipeHeat));
            if (machineHeat >= 0) call(calc, "setMachineHeat", Integer.TYPE, Integer.valueOf(machineHeat));
            if (heatOc) {
                call(calc, "setHeatOC", Boolean.TYPE, Boolean.TRUE);
                call(calc, "setHeatDiscount", Boolean.TYPE, Boolean.TRUE);
            }
            if (durationModifier > 0.0D)
                call(calc, "setDurationModifier", Double.TYPE, Double.valueOf(durationModifier));
            if (eutDiscount > 0.0D) call(calc, "setEUtDiscount", Double.TYPE, Double.valueOf(eutDiscount));
            call(calc, "calculate", null, null);
            final Variant v = new Variant();
            v.tier = tier;
            v.durationTicks = Math.max(
                1,
                ((Number) type.getMethod("getDuration")
                    .invoke(calc)).intValue());
            v.eut = Math.max(
                0L,
                ((Number) type.getMethod("getConsumption")
                    .invoke(calc)).longValue());
            return v;
        } catch (final Throwable ignored) {
            return null;
        }
    }

    private Class<?> calculatorClass() {
        if (!calculatorResolved) {
            calculatorResolved = true;
            try {
                calculator = Class.forName("gregtech.api.util.OverclockCalculator");
            } catch (final Throwable ignored) {
                calculator = null;
            }
        }
        return calculator;
    }

    private static void call(final Object target, final String name, final Class<?> parameter, final Object value)
        throws Exception {
        if (parameter == null) target.getClass()
            .getMethod(name)
            .invoke(target);
        else target.getClass()
            .getMethod(name, parameter)
            .invoke(target, value);
    }

    // ---------------------------------------------------------------- reflection helpers

    private static Object readField(final Object target, final String name) {
        Class<?> type = target == null ? null : target.getClass();
        while (type != null) {
            try {
                final Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (final Throwable ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static Iterable<?> iterable(final Object value) {
        if (value == null) return Collections.emptyList();
        if (value instanceof Iterable) return (Iterable<?>) value;
        if (value instanceof Map) return ((Map<?, ?>) value).entrySet();
        if (value.getClass()
            .isArray()) {
            final List<Object> out = new ArrayList<Object>();
            final int length = Array.getLength(value);
            for (int index = 0; index < length; index++) out.add(Array.get(value, index));
            return out;
        }
        return Collections.singletonList(value);
    }
}
