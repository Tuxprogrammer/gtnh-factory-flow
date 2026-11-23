package dev.gtnhplanner.calcoracle;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import dev.gtnhplanner.calcoracle.icons.FluidStackIconExporter;
import dev.gtnhplanner.calcoracle.icons.ItemStackIconExporter;
import gregtech.api.enums.StoneType;
import gregtech.api.interfaces.IOreMaterial;
import gregtech.api.interfaces.IStoneType;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.recipe.RecipeMapBackend;
import gregtech.api.util.GTRecipe;
import com.gtnhplanner.core.model.read.CraftingRecord;
import com.gtnhplanner.core.model.read.GtRecipeRecord;
import com.gtnhplanner.core.model.read.SmeltingRecord;
import com.gtnhplanner.read.OreLists;
import com.gtnhplanner.read.crafting.CraftingReader;
import com.gtnhplanner.read.furnace.SmeltingReader;
import com.gtnhplanner.read.gt.GtRecipeReader;
import com.gtnhplanner.read.postea.PosteaRenames;
import gtneioreplugin.util.DimensionHelper;
import gtneioreplugin.util.GT5OreLayerHelper;
import gtneioreplugin.util.GT5OreSmallHelper;
import gtneioreplugin.util.GT5UndergroundFluidHelper;
import gtneioreplugin.util.OreVeinLayer;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.StatCollector;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.oredict.OreDictionary;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;

public final class GtnhCalcOracleExporter {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private Map<String, List<String>> oreDictionaryNamesByChoiceSignature;
    private Map<String, List<Map<String, Object>>> recipeMapCatalystsById;
    /** The run's one time stamp: every record of a run was read in that run. */
    private String generatedAt;

    public ExportResult export() throws Exception {
        generatedAt = isoNow();
        List<Map<String, Object>> adapters = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> domains = new ArrayList<Map<String, Object>>();

        domains.add(exportOreDictionary(adapters));
        domains.add(exportGregtech(adapters));
        domains.add(exportCrafting(adapters));
        domains.add(exportSmelting(adapters));
        domains.add(exportThaumcraft(adapters));
        domains.add(exportForestryBees(adapters));
        domains.add(exportIc2Crops(adapters));
        domains.add(exportCropsNhCrops(adapters));
        domains.add(exportMobDrops(adapters));
        domains.add(exportMining(adapters));

        Map<String, Object> root = map();
        root.put("schemaVersion", Integer.valueOf(1));
        root.put("exporter", "gtnh-calculation-oracle");
        root.put("format", "dev.gtnhplanner.oracle.v1");
        root.put("generatedAt", generatedAt);
        root.put("minecraftVersion", "1.7.10");
        root.put("loadedMods", loadedMods());
        root.put("adapters", adapters);
        root.put("domains", domains);
        // Items the game renames in old saves (Postea): the pipeline maps old plans and recipe ids through them.
        root.put("resourceRenames", PosteaRenames.read(item -> {
            Object name = item instanceof Item ? Item.itemRegistry.getNameForObject(item) : null;
            return name == null ? null : String.valueOf(name);
        }).toExport());

        int recipeCount = countRecipes(domains);
        root.put("recipeCount", Integer.valueOf(recipeCount));

        File outputDir = new File(System.getProperty("gtnh.oracle.outputDir", "GTNH-Calc-Oracle"));
        if (!outputDir.isDirectory() && !outputDir.mkdirs()) {
            throw new IllegalStateException("Could not create oracle output directory: " + outputDir);
        }

        File outputFile = new File(outputDir, "gtnh-oracle-" + safeTimestamp(generatedAt) + ".json");
        FileWriter writer = new FileWriter(outputFile);
        try {
            GSON.toJson(root, writer);
            writer.write('\n');
        } finally {
            writer.close();
        }

        return new ExportResult(outputFile.getAbsolutePath(), recipeCount, adapters.size());
    }

    private Map<String, Object> exportOreDictionary(List<Map<String, Object>> adapters) {
        long started = System.currentTimeMillis();
        Map<String, Object> domain = domain("oreDictionary");
        Map<String, Object> entries = map();
        int stackCount = 0;

        // The planner mod's ore list order (com.gtnhplanner.read.OreLists): names sorted, since their ids follow a
        // registration order that differs run to run, and GregTech's hash-ordered glass tiers sorted.
        for (String name : OreLists.names()) {
            List<Map<String, Object>> alternatives = new ArrayList<Map<String, Object>>();
            for (ItemStack stack : OreLists.of(name)) {
                Map<String, Object> item = itemStack(stack);
                if (item != null) {
                    alternatives.add(item);
                    stackCount++;
                }
            }
            entries.put(name, alternatives);
        }

        domain.put("entries", entries);
        adapters.add(adapter("ore-dictionary", "computed", true, entries.size(), stackCount, started, null));
        return domain;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> exportGregtech(List<Map<String, Object>> adapters) {
        long started = System.currentTimeMillis();
        Map<String, Object> domain = domain("gregtech");
        List<Map<String, Object>> recipeMaps = new ArrayList<Map<String, Object>>();
        int recipeCount = 0;

        try {
            List<RecipeMap<RecipeMapBackend>> maps = new ArrayList<RecipeMap<RecipeMapBackend>>();
            for (RecipeMap<?> recipeMap : RecipeMap.ALL_RECIPE_MAPS.values()) {
                try {
                    maps.add((RecipeMap<RecipeMapBackend>) recipeMap);
                } catch (ClassCastException ignored) {
                }
            }
            Collections.sort(maps, new java.util.Comparator<RecipeMap<RecipeMapBackend>>() {
                @Override
                public int compare(RecipeMap<RecipeMapBackend> left, RecipeMap<RecipeMapBackend> right) {
                    return safeString(left.unlocalizedName).compareTo(safeString(right.unlocalizedName));
                }
            });

            // The planner mod's own reader (com.gtnhplanner.read.gt, compiled into this jar): the mod reads a
            // card's recipe with the same class, so the dataset and the game card cannot read a recipe two ways.
            GtRecipeReader reader = new GtRecipeReader();
            for (RecipeMap<RecipeMapBackend> map : maps) {
                Map<String, Object> exportedMap = map();
                String name = GtRecipeReader.mapName(map);
                exportedMap.put("id", safeString(map.unlocalizedName));
                exportedMap.put("name", name);
                exportedMap.put("sourceClass", map.getClass().getName());
                exportedMap.put("amperage", Integer.valueOf(Math.max(1, map.getAmperage())));
                List<Map<String, Object>> catalysts = recipeMapCatalysts(map);
                if (!catalysts.isEmpty()) {
                    exportedMap.put("catalysts", catalysts);
                    exportedMap.put("icon", catalysts.get(0).get("resource"));
                } else {
                    // A map no machine runs (Cauldron, Nuclear Fission) names its NEI tab
                    // face itself, in its handler info.
                    putIfPresent(exportedMap, "icon", recipeMapHandlerInfoIcon(map));
                }

                // A map lists its recipes in the order they were added, which for some maps follows a hash order
                // that is new every run (CropsNH's mutations, Forestry's combs). Sorted by first output, EU/t and
                // time, then by the whole exported content, the export lists them the same way every run.
                List<SortedRecipe> sorted = new ArrayList<SortedRecipe>();
                for (GTRecipe recipe : map.getAllRecipes()) {
                    // Zero-duration recipes are real (the reader runs them in one tick); a
                    // disabled or negative one is not.
                    if (!recipe.mEnabled || recipe.mDuration < 0) {
                        continue;
                    }
                    Map<String, Object> exported = GtRawRecipe.recipe(reader.read(map, recipe), "", generatedAt);
                    sorted.add(new SortedRecipe(recipe, exported, recordKey(exported)));
                }
                Collections.sort(sorted, new java.util.Comparator<SortedRecipe>() {
                    @Override
                    public int compare(SortedRecipe left, SortedRecipe right) {
                        int outputCompare = firstOutputName(left.exported).compareTo(firstOutputName(right.exported));
                        if (outputCompare != 0) return outputCompare;
                        int eutCompare = Long.compare(left.recipe.mEUt, right.recipe.mEUt);
                        if (eutCompare != 0) return eutCompare;
                        int durationCompare = Integer.compare(left.recipe.mDuration, right.recipe.mDuration);
                        if (durationCompare != 0) return durationCompare;
                        return left.key.compareTo(right.key);
                    }
                });

                List<Map<String, Object>> recipes = new ArrayList<Map<String, Object>>();
                int index = 0;
                for (SortedRecipe entry : sorted) {
                    // The raw id names the recipe's place and content, never the JVM's object hash.
                    entry.exported.put("id", sha1(map.unlocalizedName + ":" + index + ":" + entry.key).substring(0, 16));
                    recipes.add(entry.exported);
                    index++;
                }

                exportedMap.put("recipes", recipes);
                recipeCount += recipes.size();
                recipeMaps.add(exportedMap);
            }

            domain.put("recipeMaps", recipeMaps);
            adapters.add(adapter("gregtech-recipe-maps", "computed", true, recipeMaps.size(), recipeCount, started, null));
        } catch (Throwable t) {
            adapters.add(adapter("gregtech-recipe-maps", "missing", true, 0, recipeCount, started, t.toString()));
        }

        return domain;
    }

    private Map<String, Object> exportCrafting(List<Map<String, Object>> adapters) {
        long started = System.currentTimeMillis();
        Map<String, Object> domain = domain("crafting");
        List<Map<String, Object>> recipes = new ArrayList<Map<String, Object>>();

        try {
            // The planner mod's own crafting reader (com.gtnhplanner.read.crafting). CraftingManager's order is not the
            // same every run (some mods register from hash maps), so the recipes are sorted by what they are, and the
            // ids minted from their place follow that order.
            List<SortedCrafting> sorted = new ArrayList<SortedCrafting>();
            for (CraftingRecord read : new CraftingReader().readAll()) {
                Map<String, Object> exported = map();
                exported.put("id", "");
                exported.put("className", read.className);
                exported.put("type", read.type);
                exported.put("width", Integer.valueOf(read.width));
                exported.put("height", Integer.valueOf(read.height));
                exported.put("inputs", craftingInputs(read));
                if (read.output != null) {
                    exported.put("output", GtRawRecipe.stack(read.output));
                }
                sorted.add(new SortedCrafting(read, exported, recordKey(exported)));
            }
            Collections.sort(sorted, new java.util.Comparator<SortedCrafting>() {
                @Override
                public int compare(SortedCrafting left, SortedCrafting right) {
                    return left.key.compareTo(right.key);
                }
            });
            int index = 0;
            for (SortedCrafting entry : sorted) {
                entry.exported.put("id", sha1("crafting:" + index + ":" + entry.read.className + ":"
                    + CraftingReader.stackKey(entry.read.output == null ? null : (ItemStack) entry.read.output.host))
                    .substring(0, 16));
                recipes.add(entry.exported);
                index++;
            }
            domain.put("recipes", recipes);
            adapters.add(adapter("minecraft-forge-crafting", "computed", true, 1, recipes.size(), started, null));
        } catch (Throwable t) {
            adapters.add(adapter("minecraft-forge-crafting", "missing", true, 0, recipes.size(), started, t.toString()));
        }

        return domain;
    }

    private Map<String, Object> exportSmelting(List<Map<String, Object>> adapters) {
        long started = System.currentTimeMillis();
        Map<String, Object> domain = domain("smelting");
        List<Map<String, Object>> recipes = new ArrayList<Map<String, Object>>();

        try {
            // The planner mod's own furnace reader (com.gtnhplanner.read.furnace), which lists the recipes sorted
            // by content: FurnaceRecipes' own order is new every run.
            int index = 0;
            for (SmeltingRecord read : SmeltingReader.readAll()) {
                Map<String, Object> recipe = map();
                recipe.put("id", sha1("smelting:" + index + ":" + stackKey((ItemStack) read.input.host)).substring(0, 16));
                recipe.put("input", GtRawRecipe.stack(read.input));
                recipe.put("output", GtRawRecipe.stack(read.output));
                recipes.add(recipe);
                index++;
            }
            domain.put("recipes", recipes);
            adapters.add(adapter("minecraft-furnace", "computed", true, 1, recipes.size(), started, null));
        } catch (Throwable t) {
            adapters.add(adapter("minecraft-furnace", "missing", true, 0, recipes.size(), started, t.toString()));
        }

        return domain;
    }

    /** A crafting record with what the export writes for it and that content as text, for a total order. */
    private static final class SortedCrafting {
        final CraftingRecord read;
        final Map<String, Object> exported;
        final String key;

        SortedCrafting(CraftingRecord read, Map<String, Object> exported, String key) {
            this.read = read;
            this.exported = exported;
            this.key = key;
        }
    }

    /** A GregTech recipe with what the export writes for it and that content as text, for a total order. */
    private static final class SortedRecipe {
        final GTRecipe recipe;
        final Map<String, Object> exported;
        final String key;

        SortedRecipe(GTRecipe recipe, Map<String, Object> exported, String key) {
            this.recipe = recipe;
            this.exported = exported;
            this.key = key;
        }
    }

    /**
     * An exported record as text without its id and icon file names: two records with the same key are the same
     * recipe as far as the dataset can tell.
     */
    private static String recordKey(Map<String, Object> exported) {
        Map<String, Object> content = new LinkedHashMap<String, Object>(exported);
        content.remove("id");
        return GSON.toJson(withoutIcons(content));
    }

    private static Object withoutIcons(Object value) {
        if (value instanceof Map) {
            Map<String, Object> copy = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (!"icon".equals(key)) copy.put(key, withoutIcons(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List) {
            List<Object> copy = new ArrayList<Object>();
            for (Object entry : (List<?>) value) copy.add(withoutIcons(entry));
            return copy;
        }
        return value;
    }

    /** A stack's content as text (item, damage, size, NBT): equal for equal stacks in every run. */
    private String contentKey(Object value) {
        if (!(value instanceof ItemStack)) {
            return String.valueOf(value == null ? "" : value.getClass().getName());
        }
        ItemStack stack = (ItemStack) value;
        return stackKey(stack) + (stack.stackTagCompound == null ? "" : stack.stackTagCompound.toString());
    }

    private Map<String, Object> exportThaumcraft(List<Map<String, Object>> adapters) {
        long started = System.currentTimeMillis();
        Map<String, Object> domain = domain("thaumcraft");
        List<Map<String, Object>> recipes = new ArrayList<Map<String, Object>>();
        boolean present = isClassPresent("thaumcraft.api.ThaumcraftApi") || GtnhCalcOracleMod.isModLoaded("Thaumcraft");
        Map<String, Map<String, Object>> neiLayoutsBySignature = Collections.emptyMap();

        if (!present) {
            adapters.add(adapter("thaumcraft-crafting", "not_present", false, 0, 0, started, null));
            domain.put("recipes", recipes);
            return domain;
        }

        try {
            unlockThaumcraftKnowledgeForOracle();
            neiLayoutsBySignature = exportThaumcraftNeiLayoutsBySignature(adapters);
            Class<?> api = Class.forName("thaumcraft.api.ThaumcraftApi");
            for (Field field : api.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || !Collection.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                String fieldName = field.getName().toLowerCase(Locale.ROOT);
                if (!fieldName.contains("recipe")) {
                    continue;
                }
                field.setAccessible(true);
                Collection<?> rawRecipes = (Collection<?>) field.get(null);
                if (rawRecipes == null) {
                    continue;
                }
                // In the game's order, as before: Thaumcraft's lists keep their order run to run but for a handful of
                // recipes, and the ids minted from it are the ones the served dataset holds, which a sort would lose
                // for every recipe whose content changed since (the arcane grid fix).
                int index = 0;
                for (Object rawRecipe : rawRecipes) {
                    Map<String, Object> recipe = thaumcraftRecipe(field.getName(), index, rawRecipe);
                    if (recipe != null) {
                        Map<String, Object> neiLayout = neiLayoutsBySignature.get(thaumcraftRecipeSignature(recipe));
                        if (neiLayout == null) {
                            neiLayout = neiLayoutsBySignature.get(thaumcraftSimpleRecipeSignature(recipe));
                        }
                        putIfPresent(recipe, "neiLayout", neiLayout);
                        recipes.add(recipe);
                    }
                    index++;
                }
            }
            int essentiaSmeltingCount = addThaumcraftEssentiaSmeltingRecipes(recipes);
            domain.put("recipes", recipes);
            adapters.add(adapter("thaumcraft-crafting", "computed", true, 1, recipes.size(), started, null));
            adapters.add(
                adapter(
                    "thaumcraft-essentia-smelting",
                    essentiaSmeltingCount > 0 ? "computed" : "partial",
                    true,
                    1,
                    essentiaSmeltingCount,
                    started,
                    essentiaSmeltingCount > 0 ? null : "No Thaumcraft object aspect tags were exportable."
                )
            );
        } catch (Throwable t) {
            adapters.add(adapter("thaumcraft-crafting", "partial", true, 0, recipes.size(), started, t.toString()));
            domain.put("recipes", recipes);
        }

        return domain;
    }

    private int addThaumcraftEssentiaSmeltingRecipes(List<Map<String, Object>> recipes) throws Exception {
        Class<?> api = Class.forName("thaumcraft.api.ThaumcraftApi");
        Class<?> aspectListClass = Class.forName("thaumcraft.api.aspects.AspectList");
        Class<?> craftingManager = Class.forName("thaumcraft.common.lib.crafting.ThaumcraftCraftingManager");
        Method getObjectTags = craftingManager.getMethod("getObjectTags", ItemStack.class);
        Method getBonusTags = craftingManager.getMethod("getBonusTags", ItemStack.class, aspectListClass);
        Field objectTagsField = api.getField("objectTags");
        Object rawObjectTags = objectTagsField.get(null);
        if (!(rawObjectTags instanceof Map)) {
            return 0;
        }

        Map<String, Boolean> seen = new LinkedHashMap<String, Boolean>();
        List<ItemStack> stacks = new ArrayList<ItemStack>();
        for (Object key : ((Map<?, ?>) rawObjectTags).keySet()) {
            for (ItemStack stack : thaumcraftObjectTagStacks(key)) {
                if (stack == null) {
                    continue;
                }
                String stackKey = stackKey(stack);
                if (seen.containsKey(stackKey)) {
                    continue;
                }
                seen.put(stackKey, Boolean.TRUE);
                stacks.add(stack);
            }
        }
        Collections.sort(stacks, new java.util.Comparator<ItemStack>() {
            @Override
            public int compare(ItemStack left, ItemStack right) {
                return stackKey(left).compareTo(stackKey(right));
            }
        });

        int exported = 0;
        for (ItemStack stack : stacks) {
            Object aspects = getObjectTags.invoke(null, stack);
            aspects = getBonusTags.invoke(null, stack, aspects);
            List<Map<String, Object>> outputs = aspectResources(aspects);
            if (outputs.isEmpty()) {
                continue;
            }

            Map<String, Object> input = itemStack(stack);
            if (input == null) {
                continue;
            }

            int durationTicks = thaumcraftAlchemyFurnaceDurationTicks(outputs, 0);
            Map<String, Object> recipe = map();
            recipe.put("id", sha1("thaumcraft:essentia-smelting:" + stackKey(stack)).substring(0, 16));
            recipe.put("sourceList", "objectTags");
            recipe.put("type", "essentiaSmelting");
            recipe.put("className", "thaumcraft.common.tiles.TileAlchemyFurnace");
            recipe.put("input", input);
            recipe.put("output", outputs.get(0));
            recipe.put("outputs", outputs);
            recipe.put("aspects", outputs);
            recipe.put("durationTicks", Integer.valueOf(durationTicks));
            recipe.put("durationSource", "thaumcraft.common.tiles.TileAlchemyFurnace.canSmelt");
            recipe.put("durationFormula", "visSize(aspects) * 10 * (1 - 0.125 * bellows)");
            recipe.put("durationBellows", Integer.valueOf(0));
            recipe.put("variants", thaumcraftAlchemyFurnaceVariants(outputs));
            recipe.put(
                "notes",
                "Essentia result exported through ThaumcraftCraftingManager.getObjectTags/getBonusTags. The Alchemical Furnace burns the item; Alembics only store and transport the resulting essentia."
            );
            recipes.add(recipe);
            exported++;
        }
        return exported;
    }

    private List<ItemStack> thaumcraftObjectTagStacks(Object key) {
        List<ItemStack> stacks = new ArrayList<ItemStack>();
        if (!(key instanceof List)) {
            return stacks;
        }
        List<?> parts = (List<?>) key;
        if (parts.size() < 2 || !(parts.get(0) instanceof Item) || !(parts.get(1) instanceof Number)) {
            return stacks;
        }
        Item item = (Item) parts.get(0);
        int meta = ((Number) parts.get(1)).intValue();
        if (meta == OreDictionary.WILDCARD_VALUE) {
            addWildcardItemStacks(stacks, item);
            return stacks;
        }
        stacks.add(new ItemStack(item, 1, meta));
        return stacks;
    }

    private void addWildcardItemStacks(List<ItemStack> stacks, Item item) {
        Map<String, Boolean> seen = new LinkedHashMap<String, Boolean>();
        addUniqueStack(stacks, seen, new ItemStack(item, 1, 0));

        if (!item.getHasSubtypes()) {
            return;
        }

        List<ItemStack> subItems = new ArrayList<ItemStack>();
        collectSubItems(item, null, subItems);
        if (subItems.isEmpty()) {
            collectSubItems(item, item.getCreativeTab(), subItems);
        }
        for (ItemStack subItem : subItems) {
            addUniqueStack(stacks, seen, subItem);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void collectSubItems(Item item, CreativeTabs tab, List<ItemStack> subItems) {
        try {
            item.getSubItems(item, tab, (List) subItems);
        } catch (Throwable ignored) {
        }
    }

    private void addUniqueStack(List<ItemStack> stacks, Map<String, Boolean> seen, ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return;
        }
        String key = stackKey(stack);
        if (seen.containsKey(key)) {
            return;
        }
        seen.put(key, Boolean.TRUE);
        stacks.add(new ItemStack(stack.getItem(), 1, stack.getItemDamage()));
    }

    private int thaumcraftAlchemyFurnaceDurationTicks(List<Map<String, Object>> aspects, int bellows) {
        int visSize = 0;
        for (Map<String, Object> aspect : aspects) {
            Number amount = asNumber(aspect.get("amount"));
            if (amount != null && amount.intValue() > 0) {
                visSize += amount.intValue();
            }
        }
        double bellowsMultiplier = 1.0D - (0.125D * Math.max(0, bellows));
        return Math.max(1, (int) (visSize * 10.0D * bellowsMultiplier));
    }

    private List<Map<String, Object>> thaumcraftAlchemyFurnaceVariants(List<Map<String, Object>> aspects) {
        List<Map<String, Object>> variants = new ArrayList<Map<String, Object>>();
        for (int bellows = 0; bellows <= 6; bellows++) {
            Map<String, Object> variant = map();
            variant.put("id", "bellows-" + bellows);
            variant.put("label", bellows == 0 ? "No bellows" : bellows + " bellows");
            variant.put("bellows", Integer.valueOf(bellows));
            variant.put("durationTicks", Integer.valueOf(thaumcraftAlchemyFurnaceDurationTicks(aspects, bellows)));
            variants.add(variant);
        }
        return variants;
    }

    private Map<String, Map<String, Object>> exportThaumcraftNeiLayoutsBySignature(List<Map<String, Object>> adapters) {
        long started = System.currentTimeMillis();
        Map<String, Map<String, Object>> layoutsBySignature = new LinkedHashMap<String, Map<String, Object>>();

        if (!FMLCommonHandler.instance().getSide().isClient()) {
            return layoutsBySignature;
        }

        String[][] handlers = new String[][] {
            { "infusion", "ru.timeconqueror.tcneiadditions.nei.TCNAInfusionRecipeHandler" },
            { "crucible", "ru.timeconqueror.tcneiadditions.nei.TCNACrucibleRecipeHandler" },
            { "arcane", "ru.timeconqueror.tcneiadditions.nei.arcaneworkbench.ArcaneCraftingShapedHandler" },
            { "arcane", "ru.timeconqueror.tcneiadditions.nei.arcaneworkbench.ArcaneCraftingShapelessHandler" },
            { "infusion", "com.djgiannuzz.thaumcraftneiplugin.nei.recipehandler.InfusionRecipeHandler" },
            { "crucible", "com.djgiannuzz.thaumcraftneiplugin.nei.recipehandler.CrucibleRecipeHandler" },
            { "arcane", "com.djgiannuzz.thaumcraftneiplugin.nei.recipehandler.ArcaneShapedRecipeHandler" },
            { "arcane", "com.djgiannuzz.thaumcraftneiplugin.nei.recipehandler.ArcaneShapelessRecipeHandler" }
        };

        int exported = 0;
        List<String> warnings = new ArrayList<String>();
        for (String[] handlerInfo : handlers) {
            String type = handlerInfo[0];
            String className = handlerInfo[1];
            if (!isClassPresent(className)) {
                continue;
            }
            try {
                Object handler = Class.forName(className).getConstructor().newInstance();
                Object overlay = invokeBest(handler, "getOverlayIdentifier", new Object[0]);
                if (overlay == null) {
                    continue;
                }
                String recipeName = safeString(invokeBest(handler, "getRecipeName", new Object[0]));
                invokeBest(handler, "loadCraftingRecipes", new Object[] { safeString(overlay), new Object[0] });
                List<?> cachedRecipes = listField(handler, "arecipes");
                int index = 0;
                for (Object cachedRecipe : cachedRecipes) {
                    Map<String, Object> layout = thaumcraftNeiLayout(
                        handler,
                        type,
                        className,
                        safeString(overlay),
                        recipeName,
                        index,
                        cachedRecipe
                    );
                    index++;
                    if (layout == null) {
                        continue;
                    }
                    String signature = safeString(layout.get("signature"));
                    if (signature.length() == 0 || layoutsBySignature.containsKey(signature)) {
                        continue;
                    }
                    layoutsBySignature.put(signature, layout);
                    String simpleSignature = safeString(layout.get("simpleSignature"));
                    if (simpleSignature.length() > 0 && !layoutsBySignature.containsKey(simpleSignature)) {
                        layoutsBySignature.put(simpleSignature, layout);
                    }
                    exported++;
                }
            } catch (Throwable t) {
                warnings.add(className + ": " + t.toString());
            }
        }

        adapters.add(
            adapter(
                "thaumcraft-nei-layouts",
                warnings.isEmpty() ? "computed" : "partial",
                true,
                handlers.length,
                exported,
                started,
                warnings.isEmpty() ? null : join(warnings, "; ")
            )
        );
        return layoutsBySignature;
    }

    private Map<String, Object> thaumcraftNeiLayout(
        Object handler,
        String type,
        String handlerClass,
        String overlayIdentifier,
        String recipeName,
        int recipeIndex,
        Object cachedRecipe
    ) {
        Object result = invokeBest(cachedRecipe, "getResult", new Object[0]);
        List<Map<String, Object>> slots = new ArrayList<Map<String, Object>>();
        Map<String, Object> output = positionedStackSlot("output", 0, result);
        if (output != null) {
            slots.add(output);
        }

        int inputIndex = 0;
        for (Object ingredient : listFrom(invokeBest(cachedRecipe, "getIngredients", new Object[0]))) {
            Map<String, Object> slot = positionedStackSlot("input", inputIndex, ingredient);
            if (slot != null) {
                slots.add(slot);
                inputIndex++;
            }
        }
        for (Object other : listFrom(invokeBest(cachedRecipe, "getOtherStacks", new Object[0]))) {
            Map<String, Object> slot = positionedStackSlot("input", inputIndex, other);
            if (slot != null) {
                slots.add(slot);
                inputIndex++;
            }
        }

        if (output == null || slots.size() <= 1) {
            return null;
        }

        Map<String, Object> layout = map();
        layout.put("source", "gtnh-nei-handler");
        layout.put("type", type);
        layout.put("handlerClass", handlerClass);
        layout.put("overlayIdentifier", overlayIdentifier);
        putIfPresent(layout, "recipeName", recipeName);
        putIfPresent(layout, "category", thaumcraftNeiCategory(type, handlerClass, overlayIdentifier, recipeName));
        layout.put("recipeIndex", Integer.valueOf(recipeIndex));
        layout.put("signature", thaumcraftNeiLayoutSignature(type, output, slots));
        layout.put("simpleSignature", type + ":" + resourceKey((Map<?, ?>) output.get("resource")));
        layout.put("slots", slots);
        layout.put("canvas", thaumcraftNeiCanvas(type, slots));
        putIfPresent(layout, "backgroundImage", captureNeiBackground(handler, recipeIndex, layout));
        return layout;
    }

    private Map<String, Object> thaumcraftNeiCategory(
        String type,
        String handlerClass,
        String overlayIdentifier,
        String recipeName
    ) {
        Map<String, Object> category = map();
        category.put("source", "gtnh-nei-handler");
        category.put("type", type);
        category.put("handlerClass", handlerClass);
        category.put("overlayIdentifier", overlayIdentifier);
        putIfPresent(category, "recipeName", recipeName);
        putIfPresent(category, "icon", thaumcraftNeiCategoryIcon(type));
        return category;
    }

    private Map<String, Object> thaumcraftNeiCategoryIcon(String type) {
        if ("infusion".equals(type)) {
            return itemStack(registryStack("Thaumcraft:blockStoneDevice", 2));
        }
        if ("crucible".equals(type)) {
            return itemStack(registryStack("Thaumcraft:blockMetalDevice", 0));
        }
        if ("arcane".equals(type)) {
            return itemStack(registryStack("Thaumcraft:blockTable", 0));
        }
        return null;
    }

    private ItemStack registryStack(String registryId, int meta) {
        Object item = Item.itemRegistry.getObject(registryId);
        return item instanceof Item ? new ItemStack((Item) item, 1, meta) : null;
    }

    private Map<String, Object> positionedStackSlot(String side, int slotIndex, Object positionedStack) {
        if (positionedStack == null) {
            return null;
        }
        Object rawItem = readField(positionedStack, "item");
        ItemStack stack = rawItem instanceof ItemStack ? (ItemStack) rawItem : null;
        if (stack == null) {
            Object rawItems = readField(positionedStack, "items");
            if (rawItems != null && rawItems.getClass().isArray() && Array.getLength(rawItems) > 0) {
                Object first = Array.get(rawItems, 0);
                stack = first instanceof ItemStack ? (ItemStack) first : null;
            }
        }

        Map<String, Object> resource = resourceFromNeiStack(stack);
        if (resource == null) {
            return null;
        }

        Number x = asNumber(readField(positionedStack, "relx"));
        Number y = asNumber(readField(positionedStack, "rely"));
        if (x == null || y == null) {
            return null;
        }

        Map<String, Object> slot = map();
        slot.put("side", side);
        slot.put("kind", resource.get("kind"));
        slot.put("slotIndex", Integer.valueOf(slotIndex));
        slot.put("x", Integer.valueOf(x.intValue()));
        slot.put("y", Integer.valueOf(y.intValue()));
        slot.put("resource", resource);
        return slot;
    }

    private Map<String, Object> resourceFromNeiStack(ItemStack stack) {
        Map<String, Object> aspect = aspectResourceFromItemAspectStack(stack);
        if (aspect != null) {
            return aspect;
        }
        return itemStack(stack);
    }

    private Map<String, Object> aspectResourceFromItemAspectStack(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return null;
        }
        String itemClass = stack.getItem().getClass().getName();
        if (!itemClass.endsWith(".ItemAspect") && !itemClass.contains("thaumcraftneiplugin.items.ItemAspect")) {
            return null;
        }
        try {
            Class<?> itemAspect = Class.forName("com.djgiannuzz.thaumcraftneiplugin.items.ItemAspect");
            Object aspectList = itemAspect.getMethod("getAspects", ItemStack.class).invoke(null, stack);
            List<Map<String, Object>> aspects = aspectResources(aspectList);
            if (aspects.isEmpty()) {
                return null;
            }
            Map<String, Object> aspect = aspects.get(0);
            aspect.put("amount", Integer.valueOf(Math.max(1, stack.stackSize)));
            return aspect;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Map<String, Object> thaumcraftNeiCanvas(String type, List<Map<String, Object>> slots) {
        int width = "infusion".equals(type) ? 214 : 170;
        int height = "arcane".equals(type) ? 174 : ("infusion".equals(type) ? 160 : 132);
        for (Map<String, Object> slot : slots) {
            Number x = asNumber(slot.get("x"));
            Number y = asNumber(slot.get("y"));
            if (x != null) {
                width = Math.max(width, x.intValue() + 20);
            }
            if (y != null) {
                height = Math.max(height, y.intValue() + 20);
            }
        }
        Map<String, Object> canvas = map();
        canvas.put("width", Integer.valueOf(width));
        canvas.put("height", Integer.valueOf(height));
        return canvas;
    }

    private String captureNeiBackground(Object handler, int recipeIndex, Map<String, Object> layout) {
        if ("arcane".equals(safeString(layout.get("type")))) {
            return null;
        }
        Object rawCanvas = layout.get("canvas");
        if (!(rawCanvas instanceof Map)) {
            return null;
        }
        Map<?, ?> canvas = (Map<?, ?>) rawCanvas;
        Number widthValue = asNumber(canvas.get("width"));
        Number heightValue = asNumber(canvas.get("height"));
        int width = widthValue == null ? 170 : Math.max(1, widthValue.intValue());
        int height = heightValue == null ? 82 : Math.max(1, heightValue.intValue());
        try {
            Class<?> renderer = Class.forName("dev.gtnhplanner.calcoracle.nei.ClientNeiLayoutBackgroundRenderer");
            Method method = renderer.getMethod(
                "capture",
                Object.class,
                Integer.TYPE,
                String.class,
                String.class,
                Integer.TYPE,
                Integer.TYPE
            );
            Object result = method.invoke(
                null,
                handler,
                Integer.valueOf(recipeIndex),
                safeString(layout.get("type")),
                safeString(layout.get("signature")),
                Integer.valueOf(width),
                Integer.valueOf(height)
            );
            return result == null ? null : String.valueOf(result);
        } catch (Throwable t) {
            GtnhCalcOracleMod.LOG.warn("Could not capture NEI background for {}.", safeString(layout.get("signature")), t);
            return null;
        }
    }

    private String thaumcraftRecipeSignature(Map<String, Object> recipe) {
        Object output = recipe.get("output");
        List<Object> inputs = new ArrayList<Object>();
        Object centralInput = recipe.get("centralInput");
        Object catalyst = recipe.get("catalyst");
        if (centralInput instanceof Map) {
            inputs.add(centralInput);
        }
        if (catalyst instanceof Map) {
            inputs.add(catalyst);
        }
        for (Object component : iterable(recipe.get("components"))) {
            if (component instanceof Map) {
                inputs.add(component);
            }
        }
        for (Object aspect : iterable(recipe.get("aspects"))) {
            if (aspect instanceof Map) {
                inputs.add(aspect);
            }
        }
        return safeString(recipe.get("type"))
            + ":"
            + resourceKey(output instanceof Map ? (Map<?, ?>) output : null)
            + ":"
            + sortedResourceFingerprint(inputs);
    }

    private String thaumcraftSimpleRecipeSignature(Map<String, Object> recipe) {
        Object output = recipe.get("output");
        return safeString(recipe.get("type")) + ":" + resourceKey(output instanceof Map ? (Map<?, ?>) output : null);
    }

    private String thaumcraftNeiLayoutSignature(String type, Map<String, Object> output, List<Map<String, Object>> slots) {
        List<Object> inputs = new ArrayList<Object>();
        for (Map<String, Object> slot : slots) {
            if (!"input".equals(safeString(slot.get("side")))) {
                continue;
            }
            Object resource = slot.get("resource");
            if (resource instanceof Map) {
                inputs.add(resource);
            }
        }
        return type + ":" + resourceKey((Map<?, ?>) output.get("resource")) + ":" + sortedResourceFingerprint(inputs);
    }

    private String sortedResourceFingerprint(List<Object> rawResources) {
        List<String> keys = new ArrayList<String>();
        for (Object raw : rawResources) {
            if (raw instanceof Map) {
                keys.add(resourceKeyWithAmount((Map<?, ?>) raw));
            }
        }
        Collections.sort(keys);
        return join(keys, ",");
    }

    private String resourceKey(Map<?, ?> resource) {
        if (resource == null) {
            return "";
        }
        return safeString(resource.get("kind")) + ":" + safeString(resource.get("id"));
    }

    private String resourceKeyWithAmount(Map<?, ?> resource) {
        return resourceKey(resource) + "x" + safeString(resource.get("amount"));
    }

    private Map<String, Object> exportForestryBees(List<Map<String, Object>> adapters) {
        long started = System.currentTimeMillis();
        Map<String, Object> domain = domain("forestryBees");
        List<Map<String, Object>> species = new ArrayList<Map<String, Object>>();
        boolean present = isClassPresent("forestry.api.apiculture.BeeManager") || GtnhCalcOracleMod.isModLoaded("Forestry");

        if (!present) {
            adapters.add(adapter("forestry-bee-species", "not_present", false, 0, 0, started, null));
            domain.put("species", species);
            return domain;
        }

        try {
            Class<?> alleleManager = Class.forName("forestry.api.genetics.AlleleManager");
            Object registry = readStaticField(alleleManager, "alleleRegistry");
            Class<?> chromosomeClass = Class.forName("forestry.api.apiculture.EnumBeeChromosome");
            Object speciesChromosome = Enum.valueOf((Class<Enum>) chromosomeClass.asSubclass(Enum.class), "SPECIES");
            Object alleles = invokeBest(registry, "getRegisteredAlleles", new Object[] { speciesChromosome });
            for (Object allele : iterable(alleles)) {
                Map<String, Object> exported = beeSpecies(allele);
                if (exported != null) {
                    species.add(exported);
                }
            }
            // Forestry's allele registry hands its species out in hash order, new every run: sorted by uid.
            Collections.sort(species, new java.util.Comparator<Map<String, Object>>() {
                @Override
                public int compare(Map<String, Object> left, Map<String, Object> right) {
                    return safeString(left.get("uid")).compareTo(safeString(right.get("uid")));
                }
            });
            domain.put("species", species);
            adapters.add(adapter("forestry-bee-species", "computed", true, 1, species.size(), started, null));
        } catch (Throwable t) {
            adapters.add(adapter("forestry-bee-species", "partial", true, 0, species.size(), started, t.toString()));
            domain.put("species", species);
        }

        return domain;
    }

    private Map<String, Object> exportIc2Crops(List<Map<String, Object>> adapters) {
        long started = System.currentTimeMillis();
        Map<String, Object> domain = domain("ic2Crops");
        List<Map<String, Object>> crops = new ArrayList<Map<String, Object>>();
        boolean present = isClassPresent("ic2.api.crops.Crops") || GtnhCalcOracleMod.isModLoaded("IC2");

        if (!present) {
            adapters.add(adapter("ic2-crop-cards", "not_present", false, 0, 0, started, null));
            domain.put("crops", crops);
            return domain;
        }

        try {
            Object cropsApi = readStaticField(Class.forName("ic2.api.crops.Crops"), "instance");
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
            // Drops and some regrowth sizes are rolled on IC2.random; the export samples them, so the roll is seeded
            // afresh for every crop: the same averages every run, whatever order the crops come in.
            Object ic2Random = null;
            try {
                ic2Random = readStaticField(Class.forName("ic2.core.IC2"), "random");
            } catch (Throwable ignored) {
            }
            for (Method method : methods(cropsApi.getClass())) {
                if (method.getParameterTypes().length != 0 || !Collection.class.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                Object value = method.invoke(cropsApi);
                for (Object candidate : iterable(value)) {
                    if (candidate == null || !seen.add(candidate) || !looksLikeCropCard(candidate)) {
                        continue;
                    }
                    if (ic2Random instanceof Random) {
                        ((Random) ic2Random).setSeed(CROP_SAMPLE_SEED);
                    }
                    crops.add(cropCard(candidate));
                }
            }
            Collections.sort(crops, new java.util.Comparator<Map<String, Object>>() {
                @Override
                public int compare(Map<String, Object> left, Map<String, Object> right) {
                    return cropOrderKey(left).compareTo(cropOrderKey(right));
                }
            });
            domain.put("crops", crops);
            String status = crops.isEmpty() ? "partial" : "computed";
            String warning = crops.isEmpty() ? "IC2 crop API was present, but no crop-card collection method returned cards." : null;
            adapters.add(adapter("ic2-crop-cards", status, true, 1, crops.size(), started, warning));
        } catch (Throwable t) {
            adapters.add(adapter("ic2-crop-cards", "partial", true, 0, crops.size(), started, t.toString()));
            domain.put("crops", crops);
        }

        return domain;
    }

    /**
     * The Extreme Entity Crusher's recipes: kubatech's MobHandlerLoader.recipeMap, one entry per mob a
     * Powered Spawner can hold, built from MobsInfo's simulated drop tables (overrides and extra loaders
     * applied). MobsInfo only posts the per-mob registration events that fill that map when a world
     * starts, and the client autorun sits at the main menu, so an empty map is processed here first.
     */
    private Map<String, Object> exportMobDrops(List<Map<String, Object>> adapters) {
        long started = System.currentTimeMillis();
        Map<String, Object> domain = domain("mobDrops");
        List<Map<String, Object>> mobs = new ArrayList<Map<String, Object>>();
        boolean present = isClassPresent("kubatech.loaders.MobHandlerLoader")
            && isClassPresent("com.kuba6000.mobsinfo.loader.MobRecipeLoader");

        if (!present) {
            adapters.add(adapter("kubatech-eec-mobs", "not_present", false, 0, 0, started, null));
            domain.put("mobs", mobs);
            return domain;
        }

        try {
            Class<?> handlerLoader = Class.forName("kubatech.loaders.MobHandlerLoader");
            Map<?, ?> recipeMap = (Map<?, ?>) readStaticField(handlerLoader, "recipeMap");
            boolean processed = false;
            if (recipeMap == null || recipeMap.isEmpty()) {
                Class.forName("com.kuba6000.mobsinfo.loader.MobRecipeLoader")
                    .getMethod("processMobRecipeMap")
                    .invoke(null);
                recipeMap = (Map<?, ?>) readStaticField(handlerLoader, "recipeMap");
                processed = true;
            }
            domain.put("processedMobRecipeMap", Boolean.valueOf(processed));
            domain.put("machine", eecMachine());
            putIfPresent(domain, "infernal", infernalMobsSettings());

            Item spawnerItem = poweredSpawnerItem();
            List<String> keys = new ArrayList<String>();
            for (Object key : recipeMap.keySet()) {
                keys.add(String.valueOf(key));
            }
            Collections.sort(keys);
            for (String key : keys) {
                Map<String, Object> exported = eecMob(key, recipeMap.get(key), spawnerItem);
                if (exported != null) {
                    mobs.add(exported);
                }
            }
            domain.put("mobs", mobs);
            String status = mobs.isEmpty() ? "partial" : "computed";
            String warning = mobs.isEmpty() ? "MobHandlerLoader.recipeMap stayed empty after processing MobsInfo's mob map." : null;
            adapters.add(adapter("kubatech-eec-mobs", status, true, 1, mobs.size(), started, warning));
        } catch (Throwable t) {
            adapters.add(adapter("kubatech-eec-mobs", "partial", true, 0, mobs.size(), started, t.toString()));
            domain.put("mobs", mobs);
        }

        return domain;
    }

    private Map<String, Object> eecMachine() {
        Map<String, Object> machine = map();
        String className = "kubatech.tileentity.gregtech.multiblock.MTEExtremeEntityCrusher";
        machine.put("className", className);
        try {
            Class<?> eec = Class.forName(className);
            putIfPresent(machine, "mobSpawnInterval", readStaticField(eec, "MOB_SPAWN_INTERVAL"));
            putIfPresent(machine, "maxLootingLevel", readStaticField(eec, "MAX_LOOTING_LEVEL"));
            putIfPresent(machine, "diamondSpikesDamage", readStaticField(eec, "DIAMOND_SPIKES_DAMAGE"));
        } catch (Throwable ignored) {
        }
        try {
            putIfPresent(
                machine,
                "playerOnlyDropsModifier",
                readStaticField(Class.forName("kubatech.config.Config$MobHandler"), "playerOnlyDropsModifier")
            );
        } catch (Throwable ignored) {
        }
        try {
            Class<?> itemList = Class.forName("kubatech.api.enums.ItemList");
            @SuppressWarnings({ "unchecked", "rawtypes" })
            Object constant = Enum.valueOf((Class<Enum>) itemList.asSubclass(Enum.class), "ExtremeEntityCrusher");
            Object stack = invokeBest(constant, "get", new Object[] { Long.valueOf(1L), new Object[0] });
            putIfPresent(machine, "controller", itemStack(stack instanceof ItemStack ? (ItemStack) stack : null));
        } catch (Throwable ignored) {
        }
        putIfPresent(machine, "experience", fluidStack(FluidRegistry.getFluidStack("xpjuice", 120)));
        Item spawnerItem = poweredSpawnerItem();
        if (spawnerItem != null) {
            putIfPresent(machine, "spawner", itemStack(new ItemStack(spawnerItem, 1, 0)));
        }
        return machine;
    }

    private Item poweredSpawnerItem() {
        try {
            Object block = readStaticField(Class.forName("crazypants.enderio.EnderIO"), "blockPoweredSpawner");
            return block instanceof net.minecraft.block.Block
                ? Item.getItemFromBlock((net.minecraft.block.Block) block)
                : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Map<String, Object> infernalMobsSettings() {
        try {
            Class<?> core = Class.forName("atomicstryker.infernalmobs.common.InfernalMobsCore");
            Object instance = core.getMethod("instance").invoke(null);
            Map<String, Object> settings = map();
            for (String name : Arrays.asList(
                "getEliteRarity",
                "getUltraRarity",
                "getInfernoRarity",
                "getMinEliteModifiers",
                "getMinUltraModifiers",
                "getMinInfernoModifiers",
                "getMobModHealthFactor"
            )) {
                putIfPresent(settings, name.substring(3, 4).toLowerCase(Locale.ROOT) + name.substring(4), asNumber(invokeBest(instance, name, new Object[0])));
            }
            putIfPresent(settings, "eliteDrops", infernalDropPool(invokeBest(instance, "getDropIdListElite", new Object[0])));
            putIfPresent(settings, "ultraDrops", infernalDropPool(invokeBest(instance, "getDropIdListUltra", new Object[0])));
            putIfPresent(settings, "infernoDrops", infernalDropPool(invokeBest(instance, "getDropIdListInfernal", new Object[0])));
            putIfPresent(settings, "dimensionBlackList", invokeBest(instance, "getDimensionBlackList", new Object[0]));
            return settings.isEmpty() ? null : settings;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private List<Map<String, Object>> infernalDropPool(Object rawPool) {
        List<Map<String, Object>> pool = new ArrayList<Map<String, Object>>();
        for (Object entry : iterable(rawPool)) {
            Map<String, Object> stack = itemStack(entry instanceof ItemStack ? (ItemStack) entry : null);
            if (stack != null) {
                pool.add(stack);
            }
        }
        return pool;
    }

    private Map<String, Object> eecMob(String key, Object eecRecipe, Item spawnerItem) {
        if (eecRecipe == null) {
            return null;
        }
        Object recipe = readField(eecRecipe, "recipe");
        Map<String, Object> mob = map();
        mob.put("key", key);
        putIfPresent(mob, "displayName", mobDisplayName(key, recipe));
        putIfPresent(mob, "entityName", readField(recipe, "entityName"));
        Object entity = readField(recipe, "entity");
        if (entity != null) {
            mob.put("entityClass", entity.getClass().getName());
        }
        putIfPresent(mob, "maxEntityHealth", asNumber(readField(recipe, "maxEntityHealth")));
        putIfPresent(mob, "eut", asNumber(readField(eecRecipe, "mEUt")));
        putIfPresent(mob, "durationTicks", asNumber(readField(eecRecipe, "mDuration")));
        putIfPresent(mob, "isPeacefulAllowed", readField(recipe, "isPeacefulAllowed"));
        putIfPresent(mob, "alwaysInfernal", readField(recipe, "alwaysinfernal"));
        putIfPresent(mob, "infernalityAllowed", readField(recipe, "infernalityAllowed"));
        putIfPresent(mob, "maxDamageChance", asNumber(readField(recipe, "mMaxDamageChance")));
        if (spawnerItem != null) {
            ItemStack spawner = new ItemStack(spawnerItem, 1, 0);
            NBTTagCompound tag = new NBTTagCompound();
            tag.setString("mobType", key);
            spawner.setTagCompound(tag);
            putIfPresent(mob, "spawner", itemStack(spawner));
        }
        List<Map<String, Object>> drops = new ArrayList<Map<String, Object>>();
        for (Object drop : iterable(readField(eecRecipe, "mOutputs"))) {
            Map<String, Object> exported = mobDrop(drop);
            if (exported != null) {
                drops.add(exported);
            }
        }
        mob.put("drops", drops);
        return mob;
    }

    private String mobDisplayName(String key, Object recipe) {
        try {
            String translationKey = "entity." + key + ".name";
            String translated = StatCollector.translateToLocal(translationKey);
            if (translated != null && translated.length() > 0 && !translated.equals(translationKey)) {
                return translated;
            }
        } catch (Throwable ignored) {
        }
        String fromEntity = invokeString(readField(recipe, "entity"), "getCommandSenderName");
        return fromEntity != null && fromEntity.length() > 0 && !fromEntity.startsWith("entity.") ? fromEntity : key;
    }

    private Map<String, Object> mobDrop(Object drop) {
        Object rawStack = readField(drop, "stack");
        ItemStack stack = rawStack instanceof ItemStack ? (ItemStack) rawStack : null;
        Map<String, Object> resource = itemStack(stack);
        if (resource == null) {
            return null;
        }
        Map<String, Object> exported = map();
        exported.put("resource", resource);
        putIfPresent(exported, "damageable", Boolean.valueOf(stack.isItemStackDamageable()));
        Object type = readField(drop, "type");
        putIfPresent(exported, "type", type instanceof Enum ? ((Enum<?>) type).name() : null);
        putIfPresent(exported, "chance", asNumber(readField(drop, "chance")));
        putIfPresent(exported, "enchantable", asNumber(readField(drop, "enchantable")));
        Object damages = readField(drop, "damages");
        if (damages instanceof Map && !((Map<?, ?>) damages).isEmpty()) {
            Map<String, Object> weights = map();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) damages).entrySet()) {
                weights.put(String.valueOf(entry.getKey()), asNumber(entry.getValue()));
            }
            exported.put("damages", weights);
        }
        putIfPresent(exported, "lootable", readField(drop, "lootable"));
        putIfPresent(exported, "playerOnly", readField(drop, "playerOnly"));
        putIfPresent(exported, "variableChance", readField(drop, "variableChance"));
        List<Map<String, Object>> modifiers = new ArrayList<Map<String, Object>>();
        for (Object modifier : iterable(readField(drop, "chanceModifiers"))) {
            if (modifier != null) {
                modifiers.add(mobChanceModifier(modifier));
            }
        }
        putIfPresent(exported, "chanceModifiers", modifiers);
        List<String> info = new ArrayList<String>();
        for (Object line : iterable(readField(drop, "additionalInfo"))) {
            if (line != null) {
                info.add(String.valueOf(line));
            }
        }
        putIfPresent(exported, "additionalInfo", info);
        return exported;
    }

    /** A chance modifier's class, its NEI description and its plain fields, for the pipeline to judge. */
    private Map<String, Object> mobChanceModifier(Object modifier) {
        Map<String, Object> exported = map();
        exported.put("className", modifier.getClass().getName());
        putIfPresent(exported, "description", invokeString(modifier, "getDescription"));
        Map<String, Object> fields = map();
        Class<?> type = modifier.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic() || fields.containsKey(field.getName())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    putIfPresent(fields, field.getName(), plainModifierValue(field.get(modifier)));
                } catch (Throwable ignored) {
                }
            }
            type = type.getSuperclass();
        }
        putIfPresent(exported, "fields", fields.isEmpty() ? null : fields);
        return exported;
    }

    private Object plainModifierValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof String) {
            return value;
        }
        if (value instanceof Enum) {
            return ((Enum<?>) value).name();
        }
        if (value instanceof ItemStack) {
            return itemStack((ItemStack) value);
        }
        if (value instanceof net.minecraft.world.biome.BiomeGenBase) {
            return ((net.minecraft.world.biome.BiomeGenBase) value).biomeName;
        }
        if (value instanceof net.minecraft.enchantment.Enchantment) {
            return enchantmentRef((net.minecraft.enchantment.Enchantment) value, null);
        }
        if (value instanceof net.minecraft.enchantment.EnchantmentData) {
            net.minecraft.enchantment.EnchantmentData data = (net.minecraft.enchantment.EnchantmentData) value;
            return enchantmentRef(data.enchantmentobj, Integer.valueOf(data.enchantmentLevel));
        }
        return value.getClass().getName();
    }

    private Map<String, Object> enchantmentRef(net.minecraft.enchantment.Enchantment enchantment, Integer level) {
        Map<String, Object> ref = map();
        if (enchantment == null) {
            return ref;
        }
        ref.put("id", Integer.valueOf(enchantment.effectId));
        putIfPresent(ref, "name", enchantment.getName());
        putIfPresent(ref, "level", level);
        return ref;
    }

    private Map<String, Object> thaumcraftRecipe(String sourceList, int index, Object rawRecipe) {
        if (rawRecipe == null) {
            return null;
        }

        Map<String, Object> recipe = map();
        String className = rawRecipe.getClass().getName();
        String type = thaumcraftType(className);
        recipe.put("id", sha1("thaumcraft:" + sourceList + ":" + index + ":" + className).substring(0, 16));
        recipe.put("sourceList", sourceList);
        recipe.put("type", type);
        recipe.put("className", className);
        putIfPresent(recipe, "research", firstString(rawRecipe, "research", "researchKey", "key"));
        putIfPresent(recipe, "output", resourceFromUnknown(firstObject(rawRecipe, "getRecipeOutput", "recipeOutput", "output")));
        boolean arcane = "arcane".equals(type);
        if (!arcane) {
            putIfPresent(recipe, "centralInput", resourceFromUnknown(firstObject(rawRecipe, "getRecipeInput", "recipeInput", "input")));
            putIfPresent(recipe, "catalyst", resourceFromUnknown(firstObject(rawRecipe, "getCatalyst", "catalyst")));
        }
        List<Map<String, Object>> components = arcane
            ? thaumcraftArcaneComponents(rawRecipe)
            : resourcesFromUnknown(firstObject(rawRecipe, "getComponents", "components", "recipeItems"));
        List<Map<String, Object>> aspects = aspectResources(firstObject(rawRecipe, "getAspects", "aspects"));
        putIfPresent(recipe, "components", components);
        putIfPresent(recipe, "aspects", aspects);
        putIfPresent(recipe, "durationTicks", thaumcraftDurationTicks(type, components, aspects));
        if ("infusion".equals(type)) {
            recipe.put("durationSource", "thaumcraft.common.tiles.TileInfusionMatrix.craftCycle");
        }
        putIfPresent(recipe, "instability", firstNumber(rawRecipe, "getInstability", "instability"));
        return recipe;
    }

    // An arcane recipe's materials are its `input`: a width x height grid for a shaped
    // recipe (null where the cell is empty), a plain list for a shapeless one. Each
    // ingredient stays its own entry, because every one is required; only a list nested
    // inside the grid is a set of alternatives for that cell. `gridSlot` is the cell in the
    // 3x3 arcane workbench, anchored at the top left as NEI draws it.
    private List<Map<String, Object>> thaumcraftArcaneComponents(Object rawRecipe) {
        List<Map<String, Object>> components = new ArrayList<Map<String, Object>>();
        Object rawInput = firstObject(rawRecipe, "getInput", "input");
        if (rawInput == null) {
            return components;
        }
        Number rawWidth = rawInput.getClass().isArray() ? firstNumber(rawRecipe, "width") : null;
        int width = rawWidth == null ? 0 : rawWidth.intValue();
        int index = 0;
        int sequential = 0;
        for (Object entry : iterable(rawInput)) {
            int cell = index++;
            Map<String, Object> resource = resourceFromUnknown(entry);
            if (resource == null) {
                continue;
            }
            Map<String, Object> component = new LinkedHashMap<String, Object>(resource);
            int gridSlot = width > 0 ? (cell / width) * 3 + (cell % width) : sequential;
            component.put("gridSlot", Integer.valueOf(Math.min(gridSlot, 8)));
            components.add(component);
            sequential++;
        }
        return components;
    }

    private Map<String, Object> beeSpecies(Object allele) {
        if (allele == null) {
            return null;
        }

        Map<String, Object> species = map();
        putIfPresent(species, "uid", invokeString(allele, "getUID"));
        putIfPresent(species, "name", invokeString(allele, "getName"));
        species.put("className", allele.getClass().getName());
        putIfPresent(species, "input", beeMemberStack(allele));
        putIfPresent(species, "products", beeProducts(firstObject(allele, "getProductChances", "getProducts")));
        putIfPresent(species, "specialty", beeProducts(firstObject(allele, "getSpecialtyChances", "getSpecialty")));
        species.put("cycleTicks", Integer.valueOf(Integer.getInteger("gtnh.oracle.beeCycleTicks", 550)));
        return species;
    }

    private Map<String, Object> beeMemberStack(Object allele) {
        String uid = invokeString(allele, "getUID");
        if (uid == null || uid.length() == 0) {
            return null;
        }

        try {
            Object root = invokeBest(allele, "getRoot", new Object[0]);
            Object template = invokeBest(root, "getTemplate", new Object[] { uid });
            Object individual = invokeBest(root, "templateAsIndividual", new Object[] { template });
            Object beeType = Enum.valueOf(
                (Class<Enum>) Class.forName("forestry.api.apiculture.EnumBeeType").asSubclass(Enum.class),
                "PRINCESS"
            );
            Object stack = invokeBest(root, "getMemberStack", new Object[] { individual, Integer.valueOf(((Enum<?>) beeType).ordinal()) });
            return itemStack(stack instanceof ItemStack ? (ItemStack) stack : null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private List<Map<String, Object>> beeProducts(Object rawProducts) {
        List<Map<String, Object>> products = new ArrayList<Map<String, Object>>();
        if (!(rawProducts instanceof Map)) {
            return products;
        }
        // Forestry keeps a species' products in a HashMap keyed by ItemStack (identity hash): no order of its own.
        // Most likely first, then by the stack's content, the same every run.
        for (Map.Entry<?, ?> entry : byChanceThenContent((Map<?, ?>) rawProducts)) {
            Map<String, Object> product = map();
            Map<String, Object> stack = itemStack(entry.getKey() instanceof ItemStack ? (ItemStack) entry.getKey() : null);
            if (stack == null) {
                continue;
            }
            product.put("resource", stack);
            Number chance = asNumber(entry.getValue());
            if (chance != null) {
                double rawChance = chance.doubleValue();
                product.put("rawChance", Double.valueOf(rawChance));
                product.put("chance", Double.valueOf(rawChance > 1.0D ? rawChance / 100.0D : rawChance));
            }
            products.add(product);
        }
        return products;
    }

    /** A map from stacks to a chance or weight, most likely first, then by the stack's content. */
    private List<Map.Entry<?, ?>> byChanceThenContent(Map<?, ?> raw) {
        List<Map.Entry<?, ?>> entries = new ArrayList<Map.Entry<?, ?>>(raw.entrySet());
        Collections.sort(entries, new java.util.Comparator<Map.Entry<?, ?>>() {
            @Override
            public int compare(Map.Entry<?, ?> left, Map.Entry<?, ?> right) {
                Number l = asNumber(left.getValue());
                Number r = asNumber(right.getValue());
                int byChance = Double.compare(r == null ? 0.0D : r.doubleValue(), l == null ? 0.0D : l.doubleValue());
                return byChance != 0 ? byChance : contentKey(left.getKey()).compareTo(contentKey(right.getKey()));
            }
        });
        return entries;
    }

    /** The seed IC2.random is reset to before each crop's drops are sampled. */
    private static final long CROP_SAMPLE_SEED = 0x67746e68L;

    private String cropOrderKey(Map<String, Object> crop) {
        return safeString(crop.get("owner")) + "|" + safeString(crop.get("id")) + "|" + safeString(crop.get("name"))
            + "|" + safeString(crop.get("className"));
    }

    private Map<String, Object> cropCard(Object crop) {
        Map<String, Object> exported = map();
        exported.put("className", crop.getClass().getName());
        putIfPresent(exported, "id", firstString(crop, "getId", "id", "name"));
        putIfPresent(exported, "name", firstString(crop, "displayName", "getDisplayName", "name"));
        putIfPresent(exported, "owner", firstString(crop, "owner", "getOwner"));
        putIfPresent(exported, "tier", firstNumber(crop, "tier", "getTier"));
        putIfPresent(exported, "attributes", resourcesFromUnknown(firstObject(crop, "attributes", "getAttributes")));
        Object displayItem = firstObject(crop, "getDisplayItem", "getBaseSeed");
        if (displayItem == null) {
            displayItem = invokeBest(crop, "getDisplayItem", new Object[] { crop });
        }
        putIfPresent(exported, "displayItem", resourceFromUnknown(displayItem));
        List<Map<String, Object>> variants = cropVariants(crop);
        putIfPresent(exported, "variants", variants);
        if (!variants.isEmpty()) {
            putIfPresent(exported, "seed", variants.get(0).get("seed"));
            putIfPresent(exported, "drops", variants.get(0).get("drops"));
            putIfPresent(exported, "durationTicks", variants.get(0).get("durationTicks"));
        }
        exported.put(
            "notes",
            "Crop-card drops exported from the live IC2/CropsNH API using a simulated server crop tile."
        );
        return exported;
    }

    private List<Map<String, Object>> cropVariants(Object crop) {
        List<Map<String, Object>> variants = new ArrayList<Map<String, Object>>();
        addCropVariant(variants, crop, "23-31-0", "GTNH crop manager baseline 23/31/0", 23, 31, 0);
        addCropVariant(variants, crop, "31-31-31", "Perfect stats 31/31/31", 31, 31, 31);
        addCropVariant(variants, crop, "1-1-1", "Low stats 1/1/1", 1, 1, 1);
        return variants;
    }

    private void addCropVariant(
        List<Map<String, Object>> variants,
        Object crop,
        String key,
        String label,
        int growth,
        int gain,
        int resistance
    ) {
        Object tile = cropTileProxy(crop, growth, gain, resistance, cropMaxSize(crop));
        List<Map<String, Object>> drops = cropDrops(crop, tile, gain);
        if (drops.isEmpty()) {
            return;
        }

        Map<String, Object> variant = map();
        variant.put("id", key);
        variant.put("label", label);
        variant.put("growth", Integer.valueOf(growth));
        variant.put("gain", Integer.valueOf(gain));
        variant.put("resistance", Integer.valueOf(resistance));
        putIfPresent(variant, "seed", itemStack(generateCropSeed(crop, growth, gain, resistance, 4)));
        variant.put("drops", drops);
        variant.put("durationTicks", Integer.valueOf(cropDurationTicks(crop, tile)));
        variants.add(variant);
    }

    private List<Map<String, Object>> cropDrops(Object crop, Object tile, int gain) {
        int samples = Math.max(1, Integer.getInteger("gtnh.oracle.cropDropSamples", 256).intValue());
        Map<String, Map<String, Object>> resourcesByKey = new LinkedHashMap<String, Map<String, Object>>();
        Map<String, Double> amountsByKey = new LinkedHashMap<String, Double>();
        int maxSize = cropMaxSize(crop);

        for (int index = 0; index < samples; index++) {
            invokeBest(tile, "setSize", new Object[] { Byte.valueOf((byte) maxSize) });
            Object rawDrop = invokeBest(crop, "getGain", new Object[] { tile });
            if (!(rawDrop instanceof ItemStack)) {
                continue;
            }
            ItemStack drop = ((ItemStack) rawDrop).copy();
            if (drop.getItem() == null || drop.stackSize <= 0) {
                continue;
            }
            String key = stackKey(drop);
            if (key.length() == 0) {
                continue;
            }
            if (!resourcesByKey.containsKey(key)) {
                Map<String, Object> resource = itemStack(drop);
                if (resource != null) {
                    resourcesByKey.put(key, resource);
                }
            }
            Double current = amountsByKey.get(key);
            amountsByKey.put(key, Double.valueOf((current == null ? 0.0D : current.doubleValue()) + drop.stackSize));
        }

        double dropRounds = cropAverageDropRounds(crop, gain);
        double stackIncrease = (gain + 1) / 100.0D;
        List<Map<String, Object>> drops = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, Double> entry : amountsByKey.entrySet()) {
            Map<String, Object> resource = resourcesByKey.get(entry.getKey());
            if (resource == null) {
                continue;
            }
            double amount = ((entry.getValue().doubleValue() / samples) + stackIncrease) * dropRounds;
            if (!(amount > 0.0D)) {
                continue;
            }
            resource.put("amount", Double.valueOf(round(amount, 6)));
            Map<String, Object> drop = map();
            drop.put("resource", resource);
            drops.add(drop);
        }
        return drops;
    }

    private double cropAverageDropRounds(Object crop, int gain) {
        Number rawChance = asNumber(invokeBest(crop, "dropGainChance", new Object[0]));
        double chance = rawChance == null ? 1.0D : rawChance.doubleValue();
        chance *= Math.pow(1.03D, gain);

        double min = -10.0D;
        double max = 10.0D;
        int steps = 10000;
        double stepSize = (max - min) / steps;
        double sum = 0.0D;
        for (int k = 1; k <= steps - 1; k++) {
            sum += weightedDropChance(min + k * stepSize, chance);
        }
        return stepSize * ((weightedDropChance(min, chance) + weightedDropChance(max, chance)) / 2.0D + sum);
    }

    private double weightedDropChance(double x, double chance) {
        return Math.max(0L, Math.round(x * chance * 0.6827D + chance)) * stdNormDistr(x);
    }

    private double stdNormDistr(double x) {
        return Math.exp(-0.5D * x * x) / Math.sqrt(2.0D * Math.PI);
    }

    private int cropDurationTicks(Object crop, Object tile) {
        int maxSize = cropMaxSize(crop);
        int startSize = Math.max(1, Math.min(maxSize - 1, cropSizeAfterHarvest(crop, tile)));
        int tickRate = readStaticInt("ic2.core.crop.TileEntityCrop", "tickRate", 256);
        int growthRate = Math.max(1, cropGrowthRate(crop, tile));
        int totalTicks = 0;
        for (int size = startSize; size < maxSize; size++) {
            invokeBest(tile, "setSize", new Object[] { Byte.valueOf((byte) size) });
            Number duration = asNumber(invokeBest(crop, "growthDuration", new Object[] { tile }));
            int growthDuration = duration == null ? 200 : Math.max(1, duration.intValue());
            totalTicks += Math.max(1, (int) Math.ceil(growthDuration / (double) growthRate)) * tickRate;
        }
        return Math.max(1, totalTicks);
    }

    private int cropGrowthRate(Object crop, Object tile) {
        Number weight = asNumber(invokeBest(crop, "weightInfluences", new Object[] { tile, Float.valueOf(1.0F), Float.valueOf(1.0F), Float.valueOf(1.0F) }));
        if (weight != null && weight.intValue() > 0) {
            return weight.intValue();
        }
        Number growth = asNumber(invokeBest(tile, "getGrowth", new Object[0]));
        return growth == null ? 1 : Math.max(1, growth.intValue());
    }

    private int cropSizeAfterHarvest(Object crop, Object tile) {
        Number size = asNumber(invokeBest(crop, "getSizeAfterHarvest", new Object[] { tile }));
        return size == null ? Math.max(1, cropMaxSize(crop) - 1) : Math.max(1, size.intValue());
    }

    private int cropMaxSize(Object crop) {
        Number maxSize = firstNumber(crop, "maxSize", "getMaxSize");
        return maxSize == null ? 1 : Math.max(1, maxSize.intValue());
    }

    private Object cropTileProxy(final Object crop, int growth, int gain, int resistance, int size) {
        try {
            Class<?> cropTileClass = Class.forName("ic2.api.crops.ICropTile");
            final byte[] cropSize = new byte[] { (byte) Math.max(1, size) };
            final byte[] cropGrowth = new byte[] { (byte) Math.max(0, growth) };
            final byte[] cropGain = new byte[] { (byte) Math.max(0, gain) };
            final byte[] cropResistance = new byte[] { (byte) Math.max(0, resistance) };
            final NBTTagCompound customData = new NBTTagCompound();
            InvocationHandler handler = new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    String name = method.getName();
                    if ("getCrop".equals(name)) return crop;
                    if ("setCrop".equals(name)) return null;
                    if ("getID".equals(name)) return Short.valueOf((short) cropId(crop));
                    if ("setID".equals(name)) return null;
                    if ("getSize".equals(name)) return Byte.valueOf(cropSize[0]);
                    if ("setSize".equals(name)) {
                        cropSize[0] = args != null && args.length > 0 && args[0] instanceof Number
                            ? ((Number) args[0]).byteValue()
                            : cropSize[0];
                        return null;
                    }
                    if ("getGrowth".equals(name)) return Byte.valueOf(cropGrowth[0]);
                    if ("setGrowth".equals(name)) {
                        cropGrowth[0] = args != null && args.length > 0 && args[0] instanceof Number
                            ? ((Number) args[0]).byteValue()
                            : cropGrowth[0];
                        return null;
                    }
                    if ("getGain".equals(name)) return Byte.valueOf(cropGain[0]);
                    if ("setGain".equals(name)) {
                        cropGain[0] = args != null && args.length > 0 && args[0] instanceof Number
                            ? ((Number) args[0]).byteValue()
                            : cropGain[0];
                        return null;
                    }
                    if ("getResistance".equals(name)) return Byte.valueOf(cropResistance[0]);
                    if ("setResistance".equals(name)) {
                        cropResistance[0] = args != null && args.length > 0 && args[0] instanceof Number
                            ? ((Number) args[0]).byteValue()
                            : cropResistance[0];
                        return null;
                    }
                    if ("getScanLevel".equals(name)) return Byte.valueOf((byte) 4);
                    if ("setScanLevel".equals(name)) return null;
                    if ("getCustomData".equals(name)) return customData;
                    if ("getNutrientStorage".equals(name) || "getHydrationStorage".equals(name) || "getWeedExStorage".equals(name)) {
                        return Integer.valueOf(100);
                    }
                    if ("setNutrientStorage".equals(name) || "setHydrationStorage".equals(name) || "setWeedExStorage".equals(name)) {
                        return null;
                    }
                    if ("getHumidity".equals(name) || "getNutrients".equals(name) || "getAirQuality".equals(name)) {
                        return Byte.valueOf((byte) 10);
                    }
                    if ("getWorld".equals(name)) return null;
                    if ("getLocation".equals(name)) return new ChunkCoordinates(0, 64, 0);
                    if ("getLightLevel".equals(name)) return Integer.valueOf(15);
                    if ("pick".equals(name) || "harvest".equals(name)) return Boolean.FALSE;
                    if ("harvest_automated".equals(name)) return new ItemStack[0];
                    if ("reset".equals(name) || "updateState".equals(name)) return null;
                    if ("isBlockBelow".equals(name)) return Boolean.FALSE;
                    if ("generateSeeds".equals(name)) {
                        Object seedCrop = args != null && args.length > 0 ? args[0] : crop;
                        int seedGrowth = args != null && args.length > 1 && args[1] instanceof Number ? ((Number) args[1]).intValue() : cropGrowth[0];
                        int seedGain = args != null && args.length > 2 && args[2] instanceof Number ? ((Number) args[2]).intValue() : cropGain[0];
                        int seedResistance = args != null && args.length > 3 && args[3] instanceof Number ? ((Number) args[3]).intValue() : cropResistance[0];
                        int seedScan = args != null && args.length > 4 && args[4] instanceof Number ? ((Number) args[4]).intValue() : 4;
                        return generateCropSeed(seedCrop, seedGrowth, seedGain, seedResistance, seedScan);
                    }
                    return defaultReturnValue(method.getReturnType());
                }
            };
            return Proxy.newProxyInstance(cropTileClass.getClassLoader(), new Class<?>[] { cropTileClass }, handler);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private ItemStack generateCropSeed(Object crop, int growth, int gain, int resistance, int scan) {
        try {
            Object tile = Class.forName("ic2.core.crop.TileEntityCrop").newInstance();
            invokeBest(tile, "setCrop", new Object[] { crop });
            Object stack = invokeBest(
                tile,
                "generateSeeds",
                new Object[] {
                    crop,
                    Byte.valueOf((byte) growth),
                    Byte.valueOf((byte) gain),
                    Byte.valueOf((byte) resistance),
                    Byte.valueOf((byte) scan)
                }
            );
            return stack instanceof ItemStack ? (ItemStack) stack : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private int cropId(Object crop) {
        try {
            Object cropsApi = readStaticField(Class.forName("ic2.api.crops.Crops"), "instance");
            Number id = asNumber(invokeBest(cropsApi, "getIdFor", new Object[] { crop }));
            return id == null ? 0 : id.intValue();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private Map<String, Object> exportCropsNhCrops(List<Map<String, Object>> adapters) {
        long started = System.currentTimeMillis();
        Map<String, Object> domain = domain("cropsNhCrops");
        List<Map<String, Object>> crops = new ArrayList<Map<String, Object>>();
        boolean present = isClassPresent("com.gtnewhorizon.cropsnh.api.ICropCard")
            || GtnhCalcOracleMod.isModLoaded("cropsnh");
        if (!present) {
            adapters.add(adapter("cropsnh-crop-cards", "not_present", false, 0, 0, started, null));
            domain.put("crops", crops);
            return domain;
        }

        try {
            Map<String, Object> config = map();
            config.put("growthCycleTicks", Integer.valueOf(readStaticInt(
                "com.gtnewhorizon.cropsnh.reference.Constants", "GROWTH_CYCLE_TICKS", 256)));
            putIfPresent(config, "growthMultiplier", cropsNhStaticNumber(
                "com.gtnewhorizon.cropsnh.handler.ConfigurationHandler", "growthMultiplier"));
            domain.put("config", config);

            Object registry = cropsNhRegistry();
            if (registry == null) {
                adapters.add(adapter(
                    "cropsnh-crop-cards",
                    "partial",
                    true,
                    0,
                    0,
                    started,
                    "CropsNH is loaded, but no crop registry with an iterable crop collection was found."));
                domain.put("crops", crops);
                return domain;
            }

            int seen = 0;
            for (Object card : cropsNhAllCards(registry)) {
                if (card == null) {
                    continue;
                }
                seen++;
                Map<String, Object> exported = cropsNhCropCard(card);
                if (exported != null) {
                    crops.add(exported);
                }
            }
            domain.put("crops", crops);
            String status = crops.isEmpty() ? "partial" : "computed";
            String warning = crops.isEmpty()
                ? "CropsNH crop registry was present, but no crop cards were exported."
                : null;
            adapters.add(adapter("cropsnh-crop-cards", status, true, seen, crops.size(), started, warning));
        } catch (Throwable t) {
            adapters.add(adapter("cropsnh-crop-cards", "partial", true, 0, crops.size(), started, t.toString()));
            domain.put("crops", crops);
        }
        return domain;
    }

    private Object cropsNhRegistry() {
        String[] registryClasses = new String[] {
            "com.gtnewhorizon.cropsnh.farming.registries.CropRegistry",
            "com.gtnewhorizon.cropsnh.farming.CropRegistry",
            "com.gtnewhorizon.cropsnh.api.CropsNHApi"
        };
        for (String className : registryClasses) {
            try {
                Class<?> type = Class.forName(className);
                for (String fieldName : new String[] { "instance", "INSTANCE" }) {
                    try {
                        Object registry = readStaticField(type, fieldName);
                        if (registry != null) {
                            return registry;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private List<?> cropsNhAllCards(Object registry) {
        for (String methodName : new String[] { "getAllInRegistrationOrder", "getAll", "getCrops", "values" }) {
            Object value = invokeBest(registry, methodName, new Object[0]);
            List<?> cards = listFrom(value);
            if (!cards.isEmpty() && looksLikeCropsNhCard(cards.get(0))) {
                return cards;
            }
        }
        // Last resort: scan zero-argument methods for a collection of crop cards so a
        // renamed accessor in a future CropsNH version still exports.
        for (Method method : methods(registry.getClass())) {
            if (method.getParameterTypes().length != 0 || method.getDeclaringClass() == Object.class) {
                continue;
            }
            Object value = invokeBest(registry, method.getName(), new Object[0]);
            List<?> cards = listFrom(value);
            if (!cards.isEmpty() && looksLikeCropsNhCard(cards.get(0))) {
                return cards;
            }
        }
        return Collections.emptyList();
    }

    private boolean looksLikeCropsNhCard(Object value) {
        return value != null
            && hasMethod(value, "getDropTable")
            && hasMethod(value, "getGrowthDuration")
            && hasMethod(value, "getTier");
    }

    private Map<String, Object> cropsNhCropCard(Object crop) {
        if (Boolean.TRUE.equals(invokeBest(crop, "hideFromNEI", new Object[0]))) {
            return null;
        }

        List<Map<String, Object>> dropTable = new ArrayList<Map<String, Object>>();
        Object rawTable = invokeBest(crop, "getDropTable", new Object[0]);
        if (rawTable instanceof Map) {
            // CropsNH's drop table is a HashMap keyed by ItemStack (identity hash): heaviest first, then by content.
            for (Object rawEntry : byChanceThenContent((Map<?, ?>) rawTable)) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) rawEntry;
                if (!(entry.getKey() instanceof ItemStack)) {
                    continue;
                }
                Map<String, Object> resource = itemStack((ItemStack) entry.getKey());
                if (resource == null) {
                    continue;
                }
                Number weight = asNumber(entry.getValue());
                Map<String, Object> drop = map();
                drop.put("resource", resource);
                drop.put("weight", Integer.valueOf(weight == null ? 0 : weight.intValue()));
                dropTable.add(drop);
            }
        }
        if (dropTable.isEmpty()) {
            // Weeds, the migrator, and other utility cards produce nothing harvestable.
            return null;
        }

        Map<String, Object> exported = map();
        exported.put("className", crop.getClass().getName());
        putIfPresent(exported, "id", firstString(crop, "getId"));
        putIfPresent(exported, "numericId", firstNumber(crop, "getNumericId"));
        putIfPresent(exported, "name", cropsNhCropName(crop));
        putIfPresent(exported, "unlocalizedName", firstString(crop, "getUnlocalizedName"));
        putIfPresent(exported, "creator", firstString(crop, "getCreator"));
        putIfPresent(exported, "tier", firstNumber(crop, "getTier"));
        putIfPresent(exported, "growthDuration", firstNumber(crop, "getGrowthDuration"));
        putIfPresent(exported, "minSeedBedTier", firstNumber(crop, "getMinSeedBedTier"));
        putIfPresent(exported, "machineBreedingRecipeTier", firstNumber(crop, "getMachineBreedingRecipeTier"));
        putIfPresent(exported, "dropChance", firstNumber(crop, "getDropChance"));
        exported.put("dropTable", dropTable);

        List<String> biomeTags = new ArrayList<String>();
        for (Object tag : iterable(invokeBest(crop, "getLikedBiomeTags", new Object[0]))) {
            if (tag != null) {
                biomeTags.add(String.valueOf(tag));
            }
        }
        // A HashSet of enum values (identity hash): sorted by name.
        Collections.sort(biomeTags);
        putIfPresent(exported, "likedBiomeTags", biomeTags);

        List<String> requirements = new ArrayList<String>();
        boolean machineOnly = false;
        for (Object requirement : iterable(invokeBest(crop, "getGrowthRequirements", new Object[0]))) {
            if (requirement == null) {
                continue;
            }
            if (requirement.getClass().getName().toLowerCase(Locale.ROOT).contains("machineonly")) {
                machineOnly = true;
            }
            String description = firstString(requirement, "getDescriptionForNEI", "getDescription");
            if (description != null && description.length() > 0) {
                requirements.add(description);
            }
        }
        putIfPresent(exported, "growthRequirements", requirements);
        if (machineOnly) {
            exported.put("machineOnly", Boolean.TRUE);
        }

        putIfPresent(exported, "soils", cropsNhStackList(invokeBest(crop, "getSoilsForNEI", new Object[] { Boolean.TRUE }), 8));
        putIfPresent(exported, "blocksUnder", cropsNhStackList(invokeBest(crop, "getBlocksUnderForNEI", new Object[] { Boolean.TRUE }), 16));
        putIfPresent(exported, "alternateSeeds", cropsNhStackList(invokeBest(crop, "getAlternateSeeds", new Object[0]), 8));

        Object seedStack = invokeBest(crop, "getSeedItem", new Object[] { cropsNhSeedStatsProxy(1, 1, 1) });
        if (seedStack instanceof ItemStack) {
            putIfPresent(exported, "seed", itemStack((ItemStack) seedStack));
        }

        exported.put(
            "notes",
            "CropsNH crop card exported from the live crop registry. Drop table weights are rolled per drop"
                + " round against 10000; drop rounds average dropChance * 1.03^gain; each successful roll also"
                + " gains +1 item with probability (gain + 1) / 100.");
        return exported;
    }

    private String cropsNhCropName(Object crop) {
        String unlocalized = firstString(crop, "getUnlocalizedName");
        if (unlocalized != null && unlocalized.length() > 0) {
            String direct = StatCollector.translateToLocal(unlocalized);
            if (direct != null && direct.length() > 0 && !direct.equals(unlocalized)) {
                return direct;
            }
            String suffixed = StatCollector.translateToLocal(unlocalized + ".name");
            if (suffixed != null && suffixed.length() > 0 && !suffixed.equals(unlocalized + ".name")) {
                return suffixed;
            }
        }
        return firstString(crop, "getId");
    }

    private List<Map<String, Object>> cropsNhStackList(Object value, int limit) {
        List<Map<String, Object>> stacks = new ArrayList<Map<String, Object>>();
        // CropsNH builds its soil and block lists from hash sets (their order is new every run): sorted by content
        // before the first `limit` are taken, so the same ones come out every run.
        List<Object> entries = new ArrayList<Object>();
        for (Object entry : iterable(value)) {
            entries.add(entry);
        }
        Collections.sort(entries, new java.util.Comparator<Object>() {
            @Override
            public int compare(Object left, Object right) {
                return contentKey(left).compareTo(contentKey(right));
            }
        });
        for (Object entry : entries) {
            if (!(entry instanceof ItemStack)) {
                continue;
            }
            Map<String, Object> stack = itemStack((ItemStack) entry);
            if (stack != null) {
                stacks.add(stack);
                if (stacks.size() >= limit) {
                    break;
                }
            }
        }
        return stacks;
    }

    private Number cropsNhStaticNumber(String className, String fieldName) {
        try {
            Object value = readStaticField(Class.forName(className), fieldName);
            return value instanceof Number ? (Number) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object cropsNhSeedStatsProxy(final int growth, final int gain, final int resistance) {
        try {
            Class<?> statsClass = Class.forName("com.gtnewhorizon.cropsnh.api.ISeedStats");
            InvocationHandler handler = new InvocationHandler() {
                public Object invoke(Object proxy, Method method, Object[] args) {
                    String name = method.getName();
                    if ("getGrowth".equals(name)) return Byte.valueOf((byte) growth);
                    if ("getGain".equals(name)) return Byte.valueOf((byte) gain);
                    if ("getResistance".equals(name)) return Byte.valueOf((byte) resistance);
                    if ("isAnalyzed".equals(name)) return Boolean.TRUE;
                    if ("copy".equals(name)) return proxy;
                    if ("writeToNBT".equals(name) && args != null && args.length == 1) {
                        // Write real stat NBT so seed items render as this crop's
                        // analyzed seeds instead of the generic "Unknown Seeds".
                        if (args[0] instanceof NBTTagCompound) {
                            NBTTagCompound tag = (NBTTagCompound) args[0];
                            tag.setByte("gr", (byte) growth);
                            tag.setByte("ga", (byte) gain);
                            tag.setByte("re", (byte) resistance);
                            tag.setByte("scan", (byte) 1);
                        }
                        return args[0];
                    }
                    if ("equals".equals(name) && args != null && args.length == 1) {
                        return Boolean.valueOf(args[0] == proxy);
                    }
                    if ("hashCode".equals(name)) return Integer.valueOf(System.identityHashCode(proxy));
                    if ("toString".equals(name)) return "OracleSeedStats(" + growth + "/" + gain + "/" + resistance + ")";
                    return defaultReturnValue(method.getReturnType());
                }
            };
            return Proxy.newProxyInstance(statsClass.getClassLoader(), new Class<?>[] { statsClass }, handler);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Map<String, Object> exportMining(List<Map<String, Object>> adapters) {
        Map<String, Object> domain = domain("mining");
        domain.put(
            "notes",
            "GregTech worldgen exported through the NEI ore plugin helpers bundled in GT5-Unofficial. A vein's"
                + " weight is its relative pick weight among the veins that can generate in a dimension; dimChances"
                + " are the plugin's computed per-dimension probabilities keyed by dimension abbreviation. Density"
                + " and size describe how the picked vein fills chunks. Small ores generate independently at"
                + " amountPerChunk tries per chunk, and undergroundFluids list per-dimension chunk deposits.");
        List<Map<String, Object>> dimensions = new ArrayList<Map<String, Object>>();
        try {
            for (DimensionHelper.Dimension dimension : DimensionHelper.ALL_DIMENSIONS) {
                if (dimension == null) {
                    continue;
                }
                Map<String, Object> exported = map();
                exported.put("id", safeString(dimension.internalName()));
                putIfPresent(exported, "name", safeString(dimension.trimmedName()));
                putIfPresent(exported, "fullName", safeString(dimension.fullName()));
                putIfPresent(exported, "abbr", safeString(dimension.abbr()));
                putIfPresent(exported, "tier", safeString(dimension.tierKey()));
                dimensions.add(exported);
            }
        } catch (Throwable ignored) {
            // A failure here also fails the vein export below, which reports it.
        }
        domain.put("dimensions", dimensions);
        exportMiningOreVeins(adapters, domain);
        exportMiningSmallOres(adapters, domain);
        exportMiningUndergroundFluids(adapters, domain);
        return domain;
    }

    private void exportMiningOreVeins(List<Map<String, Object>> adapters, Map<String, Object> domain) {
        long started = System.currentTimeMillis();
        List<Map<String, Object>> veins = new ArrayList<Map<String, Object>>();
        try {
            Map<String, GT5OreLayerHelper.OreLayerWrapper> byName = GT5OreLayerHelper.getOreVeinsByName();
            if (byName == null || byName.isEmpty()) {
                // The NEI plugin fills these caches when its handlers first open, which
                // never happens on the autorun path. init() rebuilds fresh immutable maps
                // from the OreMixes enum, so calling it again is harmless.
                GT5OreLayerHelper.init();
                byName = GT5OreLayerHelper.getOreVeinsByName();
            }

            Map<GT5OreLayerHelper.OreLayerWrapper, Map<String, Object>> chancesByVein =
                new IdentityHashMap<GT5OreLayerHelper.OreLayerWrapper, Map<String, Object>>();
            Map<String, GT5OreLayerHelper.NormalOreDimensionWrapper> byDim = GT5OreLayerHelper.getOreVeinsByDim();
            for (Map.Entry<String, GT5OreLayerHelper.NormalOreDimensionWrapper> dimEntry
                : (byDim == null ? Collections.<String, GT5OreLayerHelper.NormalOreDimensionWrapper>emptyMap() : byDim)
                    .entrySet()) {
                if (dimEntry.getValue() == null || dimEntry.getValue().oreVeinToProbabilityInDimension == null) {
                    continue;
                }
                for (Map.Entry<GT5OreLayerHelper.OreLayerWrapper, Double> chance : dimEntry.getValue()
                    .oreVeinToProbabilityInDimension.entrySet()) {
                    Map<String, Object> chances = chancesByVein.get(chance.getKey());
                    if (chances == null) {
                        chances = map();
                        chancesByVein.put(chance.getKey(), chances);
                    }
                    chances.put(dimEntry.getKey(), chance.getValue());
                }
            }

            for (GT5OreLayerHelper.OreLayerWrapper vein : byName.values()) {
                if (vein == null) {
                    continue;
                }
                veins.add(oreVein(vein, chancesByVein.get(vein)));
            }
            domain.put("veins", veins);
            String status = veins.isEmpty() ? "partial" : "computed";
            String warning = veins.isEmpty()
                ? "GregTech ore vein helper was present, but no veins were exported."
                : null;
            adapters.add(adapter("gregtech-ore-veins", status, true, byName.size(), veins.size(), started, warning));
        } catch (Throwable t) {
            adapters.add(adapter("gregtech-ore-veins", "partial", true, 0, veins.size(), started, t.toString()));
            domain.put("veins", veins);
        }
    }

    private Map<String, Object> oreVein(GT5OreLayerHelper.OreLayerWrapper vein, Map<String, Object> dimChances) {
        Map<String, Object> exported = map();
        exported.put("id", safeString(vein.veinName));
        String name;
        try {
            name = vein.getLocalizedName();
        } catch (Throwable ignored) {
            name = vein.veinName;
        }
        exported.put("name", safeString(name));
        exported.put("weight", Integer.valueOf(vein.randomWeight));
        exported.put("density", Integer.valueOf(vein.density));
        exported.put("size", Integer.valueOf(vein.size));
        putIfPresent(exported, "heightRange", vein.worldGenHeightRange);
        if (vein.allowedDimWithOrigNames != null) {
            exported.put("dims", new ArrayList<String>(vein.allowedDimWithOrigNames));
        }
        if (vein.abbrDimNames != null) {
            exported.put("dimAbbrs", new ArrayList<String>(vein.abbrDimNames));
        }
        if (vein.dimWorldGenHeightRange != null && !vein.dimWorldGenHeightRange.isEmpty()) {
            exported.put("dimHeightRanges", new LinkedHashMap<String, String>(vein.dimWorldGenHeightRange));
        }
        if (dimChances != null && !dimChances.isEmpty()) {
            exported.put("dimChances", dimChances);
        }

        List<Map<String, Object>> ores = new ArrayList<Map<String, Object>>();
        String[] roles = new String[] { "primary", "secondary", "between", "sporadic" };
        int[] layerIds = new int[] {
            OreVeinLayer.VEIN_PRIMARY, OreVeinLayer.VEIN_SECONDARY, OreVeinLayer.VEIN_BETWEEN,
            OreVeinLayer.VEIN_SPORADIC
        };
        for (int index = 0; index < roles.length; index++) {
            IOreMaterial material = vein.ores != null && layerIds[index] < vein.ores.length
                ? vein.ores[layerIds[index]]
                : null;
            if (material == null) {
                continue;
            }
            Map<String, Object> layer = map();
            layer.put("role", roles[index]);
            putIfPresent(layer, "material", oreMaterial(material));
            putIfPresent(layer, "ore", itemStack(veinLayerOre(vein, layerIds[index], material)));
            ores.add(layer);
        }
        exported.put("ores", ores);
        return exported;
    }

    private ItemStack veinLayerOre(GT5OreLayerHelper.OreLayerWrapper vein, int layer, IOreMaterial material) {
        try {
            IStoneType stone = null;
            List<IStoneType> validStones = material.getValidStones();
            if (validStones != null && !validStones.isEmpty()) {
                stone = validStones.get(0);
            }
            if (stone == null) {
                stone = StoneType.Stone;
            }
            return vein.getLayerOre(layer, stone);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Map<String, Object> oreMaterial(IOreMaterial material) {
        if (material == null) {
            return null;
        }
        Map<String, Object> exported = map();
        exported.put("id", Integer.valueOf(material.getId()));
        putIfPresent(exported, "internalName", material.getInternalName());
        String name;
        try {
            name = material.getLocalizedName();
        } catch (Throwable ignored) {
            name = material.getDefaultLocalName();
        }
        putIfPresent(exported, "name", name);
        return exported;
    }

    private void exportMiningSmallOres(List<Map<String, Object>> adapters, Map<String, Object> domain) {
        long started = System.currentTimeMillis();
        List<Map<String, Object>> smallOres = new ArrayList<Map<String, Object>>();
        try {
            Map<String, GT5OreSmallHelper.OreSmallWrapper> byName = GT5OreSmallHelper.SMALL_ORES_BY_NAME;
            if (byName.isEmpty()) {
                // Unlike the vein helper, init() appends into final collections, so it
                // must only run when they are still empty.
                GT5OreSmallHelper.init();
            }
            for (GT5OreSmallHelper.OreSmallWrapper small : byName.values()) {
                if (small == null) {
                    continue;
                }
                Map<String, Object> exported = map();
                exported.put("id", safeString(small.oreGenName));
                putIfPresent(exported, "material", oreMaterial(small.material));
                putIfPresent(exported, "heightRange", small.worldGenHeightRange);
                exported.put("amountPerChunk", Integer.valueOf(small.amountPerChunk));
                if (small.allowedDimWithOrigNames != null) {
                    exported.put("dims", new ArrayList<String>(small.allowedDimWithOrigNames));
                }
                if (small.enabledDims != null) {
                    exported.put("enabledDims", new ArrayList<String>(small.enabledDims));
                }
                List<Map<String, Object>> drops = new ArrayList<Map<String, Object>>();
                List<ItemStack> rawDrops = GT5OreSmallHelper.ORE_MAT_TO_DROPS.get(small.material);
                for (ItemStack drop : rawDrops == null ? Collections.<ItemStack>emptyList() : rawDrops) {
                    Map<String, Object> exportedDrop = itemStack(drop);
                    if (exportedDrop != null) {
                        drops.add(exportedDrop);
                    }
                }
                putIfPresent(exported, "drops", drops);
                smallOres.add(exported);
            }
            domain.put("smallOres", smallOres);
            String status = smallOres.isEmpty() ? "partial" : "computed";
            String warning = smallOres.isEmpty()
                ? "GregTech small ore helper was present, but no small ores were exported."
                : null;
            adapters.add(
                adapter("gregtech-small-ores", status, true, byName.size(), smallOres.size(), started, warning));
        } catch (Throwable t) {
            adapters.add(adapter("gregtech-small-ores", "partial", true, 0, smallOres.size(), started, t.toString()));
            domain.put("smallOres", smallOres);
        }
    }

    private void exportMiningUndergroundFluids(List<Map<String, Object>> adapters, Map<String, Object> domain) {
        long started = System.currentTimeMillis();
        List<Map<String, Object>> fluids = new ArrayList<Map<String, Object>>();
        try {
            Map<String, List<GT5UndergroundFluidHelper.UndergroundFluidWrapper>> all =
                GT5UndergroundFluidHelper.getAllEntries();
            if (all == null || all.isEmpty()) {
                GT5UndergroundFluidHelper.init();
                all = GT5UndergroundFluidHelper.getAllEntries();
            }
            for (Map.Entry<String, List<GT5UndergroundFluidHelper.UndergroundFluidWrapper>> entry
                : (all == null
                    ? Collections.<String, List<GT5UndergroundFluidHelper.UndergroundFluidWrapper>>emptyMap()
                    : all).entrySet()) {
                Map<String, Object> exported = map();
                exported.put("fluidId", safeString(entry.getKey()));
                Fluid fluid = FluidRegistry.getFluid(entry.getKey());
                if (fluid != null) {
                    putIfPresent(exported, "fluid", fluidStack(new FluidStack(fluid, 1)));
                }
                List<Map<String, Object>> deposits = new ArrayList<Map<String, Object>>();
                for (GT5UndergroundFluidHelper.UndergroundFluidWrapper wrapper
                    : entry.getValue() == null
                        ? Collections.<GT5UndergroundFluidHelper.UndergroundFluidWrapper>emptyList()
                        : entry.getValue()) {
                    if (wrapper == null) {
                        continue;
                    }
                    Map<String, Object> deposit = map();
                    deposit.put("dim", safeString(wrapper.dimension));
                    deposit.put("chance", Integer.valueOf(wrapper.chance));
                    deposit.put("minAmount", Integer.valueOf(wrapper.minAmount));
                    deposit.put("maxAmount", Integer.valueOf(wrapper.maxAmount));
                    deposits.add(deposit);
                }
                exported.put("deposits", deposits);
                fluids.add(exported);
            }
            domain.put("undergroundFluids", fluids);
            String status = fluids.isEmpty() ? "partial" : "computed";
            String warning = fluids.isEmpty()
                ? "GregTech underground fluid helper was present, but no fluids were exported."
                : null;
            adapters.add(
                adapter("gregtech-underground-fluids", status, true, fluids.size(), fluids.size(), started, warning));
        } catch (Throwable t) {
            adapters
                .add(adapter("gregtech-underground-fluids", "partial", true, 0, fluids.size(), started, t.toString()));
            domain.put("undergroundFluids", fluids);
        }
    }

    private List<Map<String, Object>> recipeMapCatalysts(RecipeMap<?> target) {
        if (recipeMapCatalystsById == null) {
            recipeMapCatalystsById = buildRecipeMapCatalystCache();
        }
        List<Map<String, Object>> catalysts = recipeMapCatalystsById.get(safeString(target.unlocalizedName));
        return catalysts == null ? Collections.<Map<String, Object>>emptyList() : catalysts;
    }

    /**
     * The display stack GT gives the map's NEI tab through {@code neiHandlerInfo}, built the way
     * NEIGTConfig builds the tab: the map's handler-info creator applied to a fresh builder.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> recipeMapHandlerInfoIcon(RecipeMap<?> target) {
        try {
            Object properties = readField(target.getFrontend(), "neiProperties");
            Object creator = readField(properties, "handlerInfoCreator");
            if (!(creator instanceof java.util.function.UnaryOperator)) {
                return null;
            }
            Object builder = Class.forName("codechicken.nei.recipe.HandlerInfo$Builder")
                .getConstructor(String.class, String.class, String.class)
                .newInstance(target.unlocalizedName, "GregTech", "gregtech");
            Object applied = ((java.util.function.UnaryOperator<Object>) creator).apply(builder);
            Object stack = invokeBest(invokeBest(applied, "build", new Object[0]), "getItemStack", new Object[0]);
            return stack instanceof ItemStack ? itemStack((ItemStack) stack) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Map<String, List<Map<String, Object>>> buildRecipeMapCatalystCache() {
        Map<String, List<Map<String, Object>>> byRecipeMap = new LinkedHashMap<String, List<Map<String, Object>>>();
        try {
            Object rawMetatileEntities = readStaticField(Class.forName("gregtech.api.GregTechAPI"), "METATILEENTITIES");
            for (Object metatileEntity : iterable(rawMetatileEntities)) {
                if (metatileEntity == null) {
                    continue;
                }
                Object stackValue = invokeBest(metatileEntity, "getStackForm", new Object[] { Long.valueOf(1L) });
                if (!(stackValue instanceof ItemStack)) {
                    continue;
                }
                Map<String, Object> resource = itemStack((ItemStack) stackValue);
                if (resource == null) {
                    continue;
                }
                List<String> tooltip = tooltipLines((ItemStack) stackValue);
                if (!tooltip.isEmpty()) {
                    resource.put("tooltip", tooltip);
                }

                Number priorityValue = asNumber(invokeBest(metatileEntity, "getRecipeCatalystPriority", new Object[0]));
                int priority = priorityValue == null ? 0 : priorityValue.intValue();
                List<RecipeMap<?>> availableMaps = availableRecipeMaps(metatileEntity);
                for (RecipeMap<?> recipeMap : availableMaps) {
                    if (recipeMap == null || recipeMap.unlocalizedName == null) {
                        continue;
                    }
                    Map<String, Object> catalyst = map();
                    catalyst.put("resource", resource);
                    catalyst.put("priority", Integer.valueOf(priority));
                    catalyst.put("sourceClass", metatileEntity.getClass().getName());
                    catalyst.put("multiblock", Boolean.valueOf(metatileEntity instanceof
                        gregtech.api.metatileentity.implementations.MTEMultiBlockBase));

                    List<Map<String, Object>> entries = byRecipeMap.get(recipeMap.unlocalizedName);
                    if (entries == null) {
                        entries = new ArrayList<Map<String, Object>>();
                        byRecipeMap.put(recipeMap.unlocalizedName, entries);
                    }
                    if (!containsCatalystResource(entries, resource)) {
                        entries.add(catalyst);
                    }
                }
            }
            for (List<Map<String, Object>> catalysts : byRecipeMap.values()) {
                Collections.sort(catalysts, new java.util.Comparator<Map<String, Object>>() {
                    @Override
                    public int compare(Map<String, Object> left, Map<String, Object> right) {
                        int priorityCompare = Integer.compare(catalystPriority(right), catalystPriority(left));
                        if (priorityCompare != 0) return priorityCompare;
                        return catalystResourceId(left).compareTo(catalystResourceId(right));
                    }
                });
            }
        } catch (Throwable ignored) {
        }
        return byRecipeMap;
    }

    @SuppressWarnings("unchecked")
    private List<RecipeMap<?>> availableRecipeMaps(Object metatileEntity) {
        List<RecipeMap<?>> maps = new ArrayList<RecipeMap<?>>();
        Object rawMaps = invokeBest(metatileEntity, "getAvailableRecipeMaps", new Object[0]);
        for (Object value : iterable(rawMaps)) {
            if (value instanceof RecipeMap) {
                maps.add((RecipeMap<?>) value);
            }
        }
        if (!maps.isEmpty()) {
            return maps;
        }

        Object rawMap = invokeBest(metatileEntity, "getRecipeMap", new Object[0]);
        if (rawMap instanceof RecipeMap) {
            maps.add((RecipeMap<?>) rawMap);
        }
        return maps;
    }

    private boolean containsCatalystResource(List<Map<String, Object>> catalysts, Map<String, Object> resource) {
        String id = safeString(resource.get("id"));
        for (Map<String, Object> catalyst : catalysts) {
            Object candidate = catalyst.get("resource");
            if (candidate instanceof Map && id.equals(safeString(((Map<?, ?>) candidate).get("id")))) {
                return true;
            }
        }
        return false;
    }

    private int catalystPriority(Map<String, Object> catalyst) {
        Object priority = catalyst.get("priority");
        return priority instanceof Number ? ((Number) priority).intValue() : 0;
    }

    private String catalystResourceId(Map<String, Object> catalyst) {
        Object resource = catalyst.get("resource");
        return resource instanceof Map ? safeString(((Map<?, ?>) resource).get("id")) : "";
    }

    private List<String> tooltipLines(ItemStack stack) {
        try {
            List<?> rawTooltip = stack.getTooltip(null, false);
            List<String> tooltip = new ArrayList<String>();
            for (Object line : rawTooltip) {
                String text = String.valueOf(line).replaceAll("\\u00A7[0-9A-FK-ORa-fk-or]", "").trim();
                if (text.length() > 0) {
                    tooltip.add(text);
                }
            }
            return tooltip;
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    private Map<String, Object> itemStack(ItemStack stack) {
        if (stack == null || stack.getItem() == null || stack.stackSize <= 0) {
            return null;
        }

        String registryId = String.valueOf(Item.itemRegistry.getNameForObject(stack.getItem()));
        if (registryId == null || registryId.length() == 0 || "null".equals(registryId)) {
            return null;
        }

        Map<String, Object> item = map();
        int meta = stack.getItemDamage();
        item.put("kind", "item");
        item.put("id", itemResourceId(registryId, meta));
        item.put("registryId", registryId);
        item.put("meta", Integer.valueOf(meta));
        item.put("amount", Integer.valueOf(stack.stackSize));
        item.put("displayName", displayName(stack));
        String modId = modId(registryId);
        if (modId != null) {
            item.put("modId", modId);
        }
        if (stack.stackTagCompound != null) {
            item.put("nbt", stack.stackTagCompound.toString());
        }
        String icon = ItemStackIconExporter.captureIcon(stack);
        if (icon != null && icon.length() > 0) {
            item.put("icon", icon);
        }
        return item;
    }

    private Map<String, Object> fluidStack(FluidStack stack) {
        if (stack == null || stack.getFluid() == null || stack.amount <= 0) {
            return null;
        }

        Map<String, Object> fluid = map();
        fluid.put("kind", "fluid");
        fluid.put("id", stack.getFluid().getName());
        fluid.put("amount", Integer.valueOf(stack.amount));
        fluid.put("displayName", stack.getLocalizedName());
        String icon = FluidStackIconExporter.captureIcon(stack);
        if (icon != null && icon.length() > 0) {
            fluid.put("icon", icon);
        }
        return fluid;
    }

    /** A crafting record's cells as the raw export writes them (normalize-oracle-export.mjs reads this shape). */
    private List<Map<String, Object>> craftingInputs(CraftingRecord read) {
        List<Map<String, Object>> inputs = new ArrayList<Map<String, Object>>();
        for (CraftingRecord.Slot slot : read.inputs) {
            Map<String, Object> cell;
            if (CraftingRecord.Slot.ORE_DICTIONARY.equals(slot.kind)) {
                cell = oreDictionaryChoice(slot.oreNames);
            } else if (CraftingRecord.Slot.CHOICE.equals(slot.kind)) {
                cell = map();
                cell.put("kind", "choice");
                cell.put("id", slot.choiceId);
                cell.put("amount", Integer.valueOf(1));
                cell.put("displayName", "Item Choice");
                List<Map<String, Object>> alternatives = new ArrayList<Map<String, Object>>();
                for (GtRecipeRecord.Stack alternative : slot.alternatives) {
                    alternatives.add(compactStack(alternative));
                }
                cell.put("alternatives", alternatives);
            } else if (CraftingRecord.Slot.MISSING.equals(slot.kind)) {
                cell = map();
                cell.put("kind", "missing");
            } else {
                cell = GtRawRecipe.stack(slot.stack);
            }
            cell.put("slotIndex", Integer.valueOf(slot.slotIndex));
            inputs.add(cell);
        }
        return inputs;
    }

    /** One item of a choice cell: kind, id, amount, name, mod. */
    private Map<String, Object> compactStack(GtRecipeRecord.Stack stack) {
        Map<String, Object> item = map();
        item.put("kind", "item");
        item.put("id", stack.id);
        item.put("amount", Integer.valueOf(stack.amount));
        item.put("displayName", stack.host instanceof ItemStack ? StableNames.of((ItemStack) stack.host, stack.displayName) : stack.displayName);
        if (stack.modId != null) {
            item.put("modId", stack.modId);
        }
        return item;
    }

    private Map<String, Object> resourceFromUnknown(Object input) {
        if (input == null) {
            return null;
        }
        if (input instanceof ItemStack) {
            return itemStack((ItemStack) input);
        }
        if (input instanceof FluidStack) {
            return fluidStack((FluidStack) input);
        }
        if (input instanceof String) {
            return oreDictionaryChoice(Collections.singletonList((String) input));
        }
        if (input instanceof List) {
            return stackChoice((List<?>) input);
        }
        if (input.getClass().isArray()) {
            List<Map<String, Object>> resources = resourcesFromUnknown(input);
            if (resources.size() == 1) {
                return resources.get(0);
            }
        }
        return null;
    }

    private List<Map<String, Object>> resourcesFromUnknown(Object value) {
        List<Map<String, Object>> resources = new ArrayList<Map<String, Object>>();
        for (Object entry : iterable(value)) {
            Map<String, Object> resource = resourceFromUnknown(entry);
            if (resource != null) {
                resources.add(resource);
            } else if (entry != null) {
                Map<String, Object> text = map();
                text.put("kind", "text");
                text.put("value", String.valueOf(entry));
                resources.add(text);
            }
        }
        return resources;
    }

    private Map<String, Object> oreDictionaryChoice(List<String> names) {
        Map<String, Object> choice = map();
        choice.put("kind", "oreDictionary");
        choice.put("names", names);
        return choice;
    }

    private Map<String, Object> stackChoice(List<?> rawAlternatives) {
        List<String> oreNames = oreDictionaryNamesForChoice(rawAlternatives);
        if (oreNames != null && !oreNames.isEmpty()) {
            return oreDictionaryChoice(oreNames);
        }

        List<Map<String, Object>> alternatives = new ArrayList<Map<String, Object>>();
        List<String> keys = new ArrayList<String>();
        for (Object raw : rawAlternatives) {
            if (raw instanceof ItemStack) {
                ItemStack stack = (ItemStack) raw;
                Map<String, Object> item = compactItemStack(stack);
                if (item != null) {
                    alternatives.add(item);
                    keys.add(stackKey(stack));
                }
            }
        }
        if (alternatives.isEmpty()) {
            return null;
        }
        if (alternatives.size() == 1) {
            return alternatives.get(0);
        }
        Map<String, Object> choice = map();
        choice.put("kind", "choice");
        choice.put("id", "choice:" + sha1(keys.toString()).substring(0, 16));
        choice.put("amount", Integer.valueOf(1));
        choice.put("displayName", "Item Choice");
        choice.put("alternatives", alternatives);
        return choice;
    }

    private List<String> oreDictionaryNamesForChoice(List<?> rawAlternatives) {
        if (rawAlternatives == null || rawAlternatives.isEmpty()) {
            return Collections.emptyList();
        }
        if (oreDictionaryNamesByChoiceSignature == null) {
            oreDictionaryNamesByChoiceSignature = new LinkedHashMap<String, List<String>>();
            for (String name : OreLists.names()) {
                String signature = choiceSignature(OreDictionary.getOres(name));
                if (signature.length() == 0) {
                    continue;
                }
                List<String> names = oreDictionaryNamesByChoiceSignature.get(signature);
                if (names == null) {
                    names = new ArrayList<String>();
                    oreDictionaryNamesByChoiceSignature.put(signature, names);
                }
                names.add(name);
            }
        }
        return oreDictionaryNamesByChoiceSignature.get(choiceSignature(rawAlternatives));
    }

    private String choiceSignature(List<?> rawAlternatives) {
        List<String> keys = new ArrayList<String>();
        for (Object raw : rawAlternatives) {
            if (raw instanceof ItemStack) {
                String key = stackKey((ItemStack) raw);
                if (key.length() > 0) {
                    keys.add(key);
                }
            }
        }
        Collections.sort(keys);
        return keys.isEmpty() ? "" : keys.toString();
    }

    private void unlockThaumcraftKnowledgeForOracle() {
        try {
            String username = thaumcraftOracleUsername();
            int aspectCount = unlockThaumcraftAspects(username);
            int researchCount = unlockThaumcraftResearch(username);
            GtnhCalcOracleMod.LOG.info(
                "GTNH calculation oracle unlocked {} Thaumcraft aspect(s) and {} research entry/entries for {}.",
                Integer.valueOf(aspectCount),
                Integer.valueOf(researchCount),
                username
            );
        } catch (Throwable t) {
            GtnhCalcOracleMod.LOG.warn("Could not pre-unlock Thaumcraft knowledge for oracle export.", t);
        }
    }

    private String thaumcraftOracleUsername() {
        String configured = System.getProperty("gtnh.oracle.thaumcraftPlayer");
        if (configured != null && configured.trim().length() > 0) {
            return configured.trim();
        }

        try {
            Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft");
            Object minecraft = minecraftClass.getMethod("getMinecraft").invoke(null);
            Object player = readField(minecraft, "thePlayer");
            String playerName = invokeString(player, "getCommandSenderName");
            if (playerName != null && playerName.trim().length() > 0) {
                return playerName.trim();
            }
        } catch (Throwable ignored) {
        }

        return "GTNHPlannerOracle";
    }

    private int unlockThaumcraftAspects(String username) throws Exception {
        Class<?> aspectClass = Class.forName("thaumcraft.api.aspects.Aspect");
        Class<?> researchManager = Class.forName("thaumcraft.common.lib.research.ResearchManager");
        Method completeAspect = researchManager.getMethod(
            "completeAspectUnsaved",
            String.class,
            aspectClass,
            Short.TYPE
        );
        Object rawAspects = readStaticField(aspectClass, "aspects");
        int count = 0;
        for (Object aspect : mapValuesOrIterable(rawAspects)) {
            if (aspect == null) {
                continue;
            }
            completeAspect.invoke(null, username, aspect, Short.valueOf(Short.MAX_VALUE));
            count++;
        }

        try {
            Object proxy = readStaticField(Class.forName("thaumcraft.common.Thaumcraft"), "proxy");
            Object knowledge = readField(proxy, "playerKnowledge");
            invokeBest(knowledge, "addDiscoveredPrimalAspects", new Object[] { username });
            for (Object aspect : mapValuesOrIterable(rawAspects)) {
                invokeBest(knowledge, "addDiscoveredAspect", new Object[] { username, aspect });
            }
        } catch (Throwable ignored) {
        }

        return count;
    }

    private int unlockThaumcraftResearch(String username) throws Exception {
        Class<?> categoriesClass = Class.forName("thaumcraft.api.research.ResearchCategories");
        Class<?> researchManager = Class.forName("thaumcraft.common.lib.research.ResearchManager");
        Method completeResearch = researchManager.getMethod("completeResearchUnsaved", String.class, String.class);
        Object rawCategories = readStaticField(categoriesClass, "researchCategories");
        int count = 0;
        for (Object category : mapValuesOrIterable(rawCategories)) {
            Object rawResearch = readField(category, "research");
            if (!(rawResearch instanceof Map)) {
                continue;
            }
            for (Object key : ((Map<?, ?>) rawResearch).keySet()) {
                if (key == null) {
                    continue;
                }
                completeResearch.invoke(null, username, String.valueOf(key));
                count++;
            }
        }
        return count;
    }

    private Iterable<?> mapValuesOrIterable(Object value) {
        if (value instanceof Map) {
            return ((Map<?, ?>) value).values();
        }
        return iterable(value);
    }

    private Integer thaumcraftDurationTicks(
        String type,
        List<Map<String, Object>> components,
        List<Map<String, Object>> aspects
    ) {
        if ("infusion".equals(type)) {
            int essentia = 0;
            for (Map<String, Object> aspect : aspects) {
                Number amount = asNumber(aspect.get("amount"));
                if (amount != null && amount.intValue() > 0) {
                    essentia += amount.intValue();
                }
            }
            int componentCount = components == null ? 0 : components.size();
            return Integer.valueOf(10 * (essentia + componentCount * 6 + 1));
        }
        if ("crucible".equals(type)) {
            return Integer.valueOf(20);
        }
        if ("arcane".equals(type)) {
            return Integer.valueOf(1);
        }
        return null;
    }

    private List<Map<String, Object>> aspectResources(Object aspectList) {
        List<Map<String, Object>> aspects = new ArrayList<Map<String, Object>>();
        if (aspectList == null) {
            return aspects;
        }

        Object rawAspects = invokeBest(aspectList, "getAspectsSortedAmount", new Object[0]);
        if (rawAspects == null) {
            rawAspects = invokeBest(aspectList, "getAspects", new Object[0]);
        }
        for (Object aspect : iterable(rawAspects)) {
            Number amount = asNumber(invokeBest(aspectList, "getAmount", new Object[] { aspect }));
            if (amount == null || amount.doubleValue() <= 0.0D) {
                continue;
            }
            String tag = invokeString(aspect, "getTag");
            String name = invokeString(aspect, "getName");
            if (tag == null) {
                tag = safeString(aspect);
            }
            Map<String, Object> resource = map();
            resource.put("kind", "aspect");
            resource.put("id", "thaumcraft:aspect:" + tag.toLowerCase(Locale.ROOT));
            resource.put("tag", tag);
            resource.put("amount", amount);
            resource.put("displayName", name == null ? tag : name);
            putIfPresent(resource, "iconPath", aspectIconPath(aspect, tag));
            putIfPresent(resource, "dominantColor", aspectColor(aspect));
            aspects.add(resource);
        }
        // Thaumcraft sorts by amount only; equal amounts keep the AspectList's insertion order, which follows the
        // hash order of whatever built it. Ties go by tag, so the list is the same every run.
        Collections.sort(aspects, new java.util.Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> left, Map<String, Object> right) {
                int byAmount = Double.compare(
                    asNumber(right.get("amount")).doubleValue(),
                    asNumber(left.get("amount")).doubleValue());
                return byAmount != 0 ? byAmount : String.valueOf(left.get("id")).compareTo(String.valueOf(right.get("id")));
            }
        });
        return aspects;
    }

    private String aspectIconPath(Object aspect, String tag) {
        Object image = readField(aspect, "image");
        if (image == null) {
            image = invokeBest(aspect, "getImage", new Object[0]);
        }
        String location = safeString(image);
        if (location.toLowerCase(Locale.ROOT).contains("_unknown")) {
            location = "";
        }
        int separator = location.indexOf(':');
        String domain = separator > 0 ? location.substring(0, separator) : "thaumcraft";
        String resourcePath = separator > 0 ? location.substring(separator + 1) : location;
        if ("thaumcraft".equals(domain) && resourcePath.startsWith("textures/aspects/")) {
            return "/nei/thaumcraft/aspects/" + resourcePath.substring("textures/aspects/".length());
        }
        if (separator > 0 && resourcePath.startsWith("textures/")) {
            return "asset:" + domain + ":" + resourcePath;
        }
        if (isBundledThaumcraftAspect(tag)) {
            return "/nei/thaumcraft/aspects/" + tag.toLowerCase(Locale.ROOT) + ".png";
        }
        if (tag != null && tag.length() > 0) {
            return "asset:gregtech:textures/aspects/" + tag.toUpperCase(Locale.ROOT) + ".png";
        }
        if ("thaumcraft".equals(domain) && tag != null && tag.length() > 0) {
            return "/nei/thaumcraft/aspects/" + tag.toLowerCase(Locale.ROOT) + ".png";
        }
        return null;
    }

    private boolean isBundledThaumcraftAspect(String tag) {
        if (tag == null) {
            return false;
        }
        return Arrays.asList(
            "aer", "alienis", "aqua", "arbor", "auram", "bestia", "cognitio", "corpus",
            "exanimis", "fabrico", "fames", "gelum", "herba", "humanus", "ignis", "instrumentum",
            "iter", "limus", "lucrum", "lux", "machina", "messis", "metallum", "meto",
            "mortuus", "motus", "ordo", "pannus", "perditio", "perfodio", "permutatio",
            "potentia", "praecantatio", "sano", "sensus", "spiritus", "telum", "tempestas",
            "tenebrae", "terra", "tutamen", "vacuos", "venenum", "victus", "vinculum",
            "vitium", "vitreus", "volatus"
        ).contains(tag.toLowerCase(Locale.ROOT));
    }

    private String aspectColor(Object aspect) {
        Number color = asNumber(invokeBest(aspect, "getColor", new Object[0]));
        if (color == null) {
            return null;
        }
        return String.format("#%06x", Integer.valueOf(color.intValue() & 0xFFFFFF));
    }

    private Map<String, Object> compactItemStack(ItemStack stack) {
        if (stack == null || stack.getItem() == null || stack.stackSize <= 0) {
            return null;
        }

        String registryId = String.valueOf(Item.itemRegistry.getNameForObject(stack.getItem()));
        if (registryId == null || registryId.length() == 0 || "null".equals(registryId)) {
            return null;
        }

        Map<String, Object> item = map();
        int meta = stack.getItemDamage();
        item.put("kind", "item");
        item.put("id", itemResourceId(registryId, meta));
        item.put("amount", Integer.valueOf(stack.stackSize));
        item.put("displayName", displayName(stack));
        String modId = modId(registryId);
        if (modId != null) {
            item.put("modId", modId);
        }
        return item;
    }

    /**
     * The first output as the export writes it (after the reader's own ordering): its registry name, damage and size,
     * else the first fluid's name. GregTech's own first output is in hash order for some recipes.
     */
    @SuppressWarnings("unchecked")
    private static String firstOutputName(Map<String, Object> exported) {
        for (Object o : (List<Object>) exported.get("itemOutputs")) {
            Map<String, Object> stack = (Map<String, Object>) o;
            return stack.get("registryId") + "@" + stack.get("meta") + "x" + stack.get("amount");
        }
        for (Object o : (List<Object>) exported.get("fluidOutputs")) {
            return String.valueOf(((Map<String, Object>) o).get("id"));
        }
        return "";
    }

    private String thaumcraftType(String className) {
        String lower = className.toLowerCase(Locale.ROOT);
        if (lower.contains("infusion")) return "infusion";
        if (lower.contains("crucible")) return "crucible";
        if (lower.contains("arcane")) return "arcane";
        return "thaumcraft";
    }

    private boolean looksLikeCropCard(Object value) {
        String className = value.getClass().getName().toLowerCase(Locale.ROOT);
        return className.contains("crop") && (hasMethod(value, "tier") || hasMethod(value, "getId") || hasMethod(value, "name"));
    }

    private boolean hasMethod(Object target, String methodName) {
        for (Method method : methods(target.getClass())) {
            if (method.getName().equals(methodName) && method.getParameterTypes().length == 0) {
                return true;
            }
        }
        return false;
    }

    private Object firstObject(Object target, String... names) {
        for (String name : names) {
            Object value = invokeBest(target, name, new Object[0]);
            if (value != null) {
                return value;
            }
            value = readField(target, name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String firstString(Object target, String... names) {
        Object value = firstObject(target, names);
        return value == null ? null : String.valueOf(value);
    }

    private Number firstNumber(Object target, String... names) {
        return asNumber(firstObject(target, names));
    }

    private Object invokeBest(Object target, String methodName, Object[] args) {
        if (target == null || methodName == null) {
            return null;
        }
        for (Method method : methods(target.getClass())) {
            if (!method.getName().equals(methodName) || method.getParameterTypes().length != args.length) {
                continue;
            }
            try {
                return method.invoke(target, args);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * A class's public methods in a fixed order. {@code getMethods()} promises none, and HotSpot's changes between
     * runs, so a reflective read that tries the first match, or walks a type's collections, would differ run to run.
     */
    private static Method[] methods(Class<?> type) {
        Method[] methods = SORTED_METHODS.get(type);
        if (methods == null) {
            methods = type.getMethods();
            java.util.Arrays.sort(methods, new java.util.Comparator<Method>() {
                @Override
                public int compare(Method left, Method right) {
                    return left.toString().compareTo(right.toString());
                }
            });
            SORTED_METHODS.put(type, methods);
        }
        return methods;
    }

    private static final Map<Class<?>, Method[]> SORTED_METHODS = new IdentityHashMap<Class<?>, Method[]>();

    private String invokeString(Object target, String methodName) {
        Object value = invokeBest(target, methodName, new Object[0]);
        return value == null ? null : String.valueOf(value);
    }

    private Object readField(Object target, String fieldName) {
        if (target == null) {
            return null;
        }
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private Object readStaticField(Class<?> type, String fieldName) throws Exception {
        Field field = type.getField(fieldName);
        field.setAccessible(true);
        return field.get(null);
    }

    private int readStaticInt(String className, String fieldName, int fallback) {
        try {
            Object value = readStaticField(Class.forName(className), fieldName);
            return value instanceof Number ? ((Number) value).intValue() : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private Object defaultReturnValue(Class<?> type) {
        if (type == Boolean.TYPE) return Boolean.FALSE;
        if (type == Byte.TYPE) return Byte.valueOf((byte) 0);
        if (type == Short.TYPE) return Short.valueOf((short) 0);
        if (type == Integer.TYPE) return Integer.valueOf(0);
        if (type == Long.TYPE) return Long.valueOf(0L);
        if (type == Float.TYPE) return Float.valueOf(0.0F);
        if (type == Double.TYPE) return Double.valueOf(0.0D);
        if (type == Character.TYPE) return Character.valueOf('\0');
        return null;
    }

    private Number asNumber(Object value) {
        return value instanceof Number ? (Number) value : null;
    }

    private double round(double value, int decimals) {
        double factor = Math.pow(10.0D, Math.max(0, decimals));
        return Math.round(value * factor) / factor;
    }

    private Iterable<?> iterable(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        if (value instanceof Iterable) {
            return (Iterable<?>) value;
        }
        if (value instanceof Map) {
            return ((Map<?, ?>) value).entrySet();
        }
        if (value.getClass().isArray()) {
            List<Object> out = new ArrayList<Object>();
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                out.add(Array.get(value, index));
            }
            return out;
        }
        return Collections.singletonList(value);
    }

    private List<?> listFrom(Object value) {
        List<Object> out = new ArrayList<Object>();
        for (Object entry : iterable(value)) {
            out.add(entry);
        }
        return out;
    }

    private List<?> listField(Object target, String fieldName) {
        Object value = readField(target, fieldName);
        return listFrom(value);
    }

    private int readIntField(Object target, String fieldName) {
        Object value = readField(target, fieldName);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Collection && ((Collection<?>) value).isEmpty()) {
            return;
        }
        target.put(key, value);
    }

    private Map<String, Object> domain(String id) {
        Map<String, Object> domain = map();
        domain.put("id", id);
        return domain;
    }

    private Map<String, Object> adapter(
        String id,
        String status,
        boolean detected,
        int subjectCount,
        int recipeCount,
        long started,
        String warning
    ) {
        Map<String, Object> adapter = map();
        adapter.put("id", id);
        adapter.put("status", status);
        adapter.put("detected", Boolean.valueOf(detected));
        adapter.put("subjectCount", Integer.valueOf(subjectCount));
        adapter.put("recipeCount", Integer.valueOf(recipeCount));
        adapter.put("durationMillis", Long.valueOf(System.currentTimeMillis() - started));
        if (warning != null && warning.length() > 0) {
            adapter.put("warnings", Collections.singletonList(warning));
        }
        return adapter;
    }

    private List<String> loadedMods() {
        List<String> mods = new ArrayList<String>();
        for (Object modContainer : Loader.instance().getModList()) {
            Object value = invokeBest(modContainer, "getModId", new Object[0]);
            if (value != null) {
                mods.add(String.valueOf(value));
            }
        }
        Collections.sort(mods);
        return mods;
    }

    private int countRecipes(List<Map<String, Object>> domains) {
        int count = 0;
        for (Map<String, Object> domain : domains) {
            Object recipeMaps = domain.get("recipeMaps");
            for (Object recipeMap : iterable(recipeMaps)) {
                Object recipes = recipeMap instanceof Map ? ((Map<?, ?>) recipeMap).get("recipes") : null;
                for (Object ignored : iterable(recipes)) {
                    count++;
                }
            }
            for (String key : Arrays.asList("recipes", "species", "crops", "mobs")) {
                for (Object ignored : iterable(domain.get(key))) {
                    count++;
                }
            }
        }
        return count;
    }

    private boolean isClassPresent(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String displayName(ItemStack stack) {
        try {
            return StableNames.of(stack, stack.getDisplayName());
        } catch (Throwable ignored) {
            return stackKey(stack);
        }
    }

    private String stackKey(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return "";
        }
        String registryId = String.valueOf(Item.itemRegistry.getNameForObject(stack.getItem()));
        return registryId + "@" + stack.getItemDamage() + "x" + stack.stackSize;
    }

    private String modId(String registryId) {
        int separator = registryId.indexOf(':');
        return separator > 0 ? registryId.substring(0, separator) : null;
    }

    private String itemResourceId(String registryId, int meta) {
        String id = canonicalRegistryId(registryId);
        return meta == 0 ? id : id + "@" + meta;
    }

    private String canonicalRegistryId(String registryId) {
        String normalized = safeString(registryId).trim();
        int separator = normalized.indexOf(':');
        if (separator < 0) {
            return normalized.toLowerCase(Locale.ROOT);
        }
        String domain = normalized.substring(0, separator).toLowerCase(Locale.ROOT);
        String path = normalized.substring(separator + 1).toLowerCase(Locale.ROOT);
        return domain + ":" + path;
    }

    private String sha1(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] data = digest.digest(value.getBytes("UTF-8"));
            StringBuilder builder = new StringBuilder();
            for (byte b : data) {
                builder.append(String.format("%02x", Byte.valueOf(b)));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String safeTimestamp(String value) {
        return value.replaceAll("[^0-9T]", "").replace("T", "-");
    }

    /**
     * Now, or the time SOURCE_DATE_EPOCH names (seconds since 1970, the reproducible-builds convention), so two runs of
     * the same pack can write identical exports.
     */
    private String isoNow() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        String epoch = System.getenv("SOURCE_DATE_EPOCH");
        if (epoch != null && epoch.trim().matches("[0-9]+")) {
            return format.format(new Date(Long.parseLong(epoch.trim()) * 1000L));
        }
        return format.format(new Date());
    }

    private String safeString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String join(List<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) {
                builder.append(separator);
            }
            builder.append(value);
        }
        return builder.toString();
    }

    private Map<String, Object> map() {
        return new LinkedHashMap<String, Object>();
    }

    public static final class ExportResult {
        public final String outputFile;
        public final int recipeCount;
        public final int adapterCount;

        ExportResult(String outputFile, int recipeCount, int adapterCount) {
            this.outputFile = outputFile;
            this.recipeCount = recipeCount;
            this.adapterCount = adapterCount;
        }
    }
}
