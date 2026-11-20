package com.gtnhplanner.machines.game;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import com.google.gson.JsonObject;
import com.gtnhplanner.api.RecipePropertyAPI;
import com.gtnhplanner.core.base.JsonTree;
import com.gtnhplanner.core.machines.AlgaePond;
import com.gtnhplanner.core.model.ResourceKey;
import com.gtnhplanner.core.model.read.CraftingDatasetRecipe;
import com.gtnhplanner.core.model.read.CraftingRecord;
import com.gtnhplanner.core.model.read.GtDatasetRecipe;
import com.gtnhplanner.core.model.read.GtRecipeRecord;
import com.gtnhplanner.core.model.read.SmeltingDatasetRecipe;
import com.gtnhplanner.core.model.read.SmeltingRecord;
import com.gtnhplanner.data.MachineConfig;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.data.provider.GTKeys;
import com.gtnhplanner.data.provider.GTProvider;
import com.gtnhplanner.data.provider.VanillaProvider;
import com.gtnhplanner.importer.game.GameIds;
import com.gtnhplanner.machines.web.HandlerData;
import com.gtnhplanner.machines.web.MachineTable;
import com.gtnhplanner.machines.web.RecipeRules;
import com.gtnhplanner.machines.web.Tiers;
import com.gtnhplanner.machines.web.Web;
import com.gtnhplanner.read.crafting.CraftingReader;
import com.gtnhplanner.ui.card.CardDefaults;
import com.gtnhplanner.ui.card.CardModel;

import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.FurnaceRecipeHandler;
import codechicken.nei.recipe.RecipeHandlerRef;
import codechicken.nei.recipe.ShapelessRecipeHandler;
import gregtech.api.recipe.RecipeMap;

/**
 * A GregTech card as the website's machine maths see it (machines/web): its recipe as the website's dataset carries it
 * (map, numbers, ports, special value, handlers and settings from handlers.json), and its settings as the website's
 * node fields (docs/design/machine-table-port.md has the table of keys).
 */
public final class WebCards {

    private WebCards() {}

    /** The card's settings keys for the website's node fields. */
    public static final String VOLTAGE = "voltage", AMPS = "amp", HATCH_TYPE = "energy_hatch_type", COIL = "coil",
        CONTROL_PREFIX = "machine:";

    /** The website's vanilla furnace map: 200 ticks at no EU, run by GT's furnaces at 128 ticks and 4 EU/t. */
    private static final String SMELTING = "smelting";

    /** The game recipe map's id as handlers.json keys it, or null when the card is no GregTech recipe. */
    @Nullable
    public static String mapId(final Node node) {
        if (node.isPower()) return null;
        // The Extreme Entity Crusher's recipes are kubatech's own, on MobsInfo's page: the website files them so.
        if (EecCards.isEec(node)) return com.gtnhplanner.machines.MobDrops.MAP_ID;
        if (node.properties.get(GTProvider.RECIPE_MAP) instanceof final RecipeMap<?> map) return map.unlocalizedName;
        final RecipeHandlerRef ref = com.gtnhplanner.nei.RecipeRefs.of(com.gtnhplanner.nei.NeiRecipes.id(node));
        return ref != null && ref.handler instanceof FurnaceRecipeHandler ? SMELTING : null;
    }

    /**
     * The card's recipe as the website's dataset carries it, or null when handlers.json has no such map. A GregTech
     * map recipe is the dataset's own record of it (core-model GtDatasetRecipe over the record the shared reader
     * read, GTProvider.RECORD): its numbers, slots and the game's overclock ladder exactly as the export run writes
     * them. A furnace recipe is the dataset's own record of it too (core-model SmeltingDatasetRecipe over the furnace
     * reader's record, VanillaProvider.SMELTING_RECORD). The crusher's mobs are built from the card.
     */
    @Nullable
    public static Web.Recipe recipe(final Node node) {
        if (AutoWorkbench.crafts(node)) return craftingRecipe(node);
        final String mapId = mapId(node);
        final HandlerData.MapEntry entry = entry(mapId);
        if (entry == null) return null;
        final boolean smelting = SMELTING.equals(mapId);
        final JsonObject eec = EecCards.metadata(node);
        final Web.Recipe r;
        final Web.Recipe smelted = smelting
            && node.properties.get(VanillaProvider.SMELTING_RECORD) instanceof final SmeltingRecord read
                ? SmeltingDatasetRecipe.of(read)
                : null;
        if (!smelting && eec == null && node.properties.get(GTProvider.RECORD) instanceof final GtRecipeRecord read) {
            r = GtDatasetRecipe.of(read, entry.name);
            // The maths key their tables by handlers.json's map id (GT++'s maps there are gtpp.recipe.*).
            r.metadata.put("recipeMapId", entry.id);
        } else if (smelted != null) {
            // Vanilla smelting as the dataset files it: the Furnace map in category furnace, not a GregTech map. The
            // card keeps handlers.json's map id beside it, as a GregTech card does (PlanExport files it by that id).
            r = smelted;
            r.metadata = new LinkedHashMap<>();
            r.metadata.put("recipeMapId", entry.id);
        } else {
            r = new Web.Recipe();
            r.name = node.machineName;
            r.kind = "gregtech_machine";
            r.category = "gregtech";
            r.machineType = entry.name;
            r.durationTicks = smelting ? 200 : number(node.properties.get(RecipePropertyAPI.DURATION_TICKS));
            r.eut = smelting ? 0 : number(node.properties.get(GTKeys.EU_PER_TICK));
            r.minimumTier = smelting ? "NONE" : HandlerData.recipeMinimumTier(r.eut);
            final double special = number(node.properties.get(GTProvider.SPECIAL_VALUE));
            r.specialValue = special;
            r.nei = new Web.Nei();
            r.nei.additionalInfo = List.of("Special value: " + (long) special);
            r.source = new Web.Source();
            r.source.recipeMap = entry.name;
            r.metadata = new LinkedHashMap<>();
            r.metadata.put("recipeMapId", entry.id);
            r.metadata.put("specialValue", special);
            if (node.properties.get(GTProvider.FUSION_THRESHOLD) instanceof final Number startup)
                r.metadata.put("fusionStartupEu", startup.doubleValue());
            r.inputs = resources(node.inputs);
            r.outputs = resources(node.outputs);
        }
        r.id = mapId + ":" + com.gtnhplanner.nei.NeiRecipes.id(node);
        if (eec != null) {
            // The crusher's kills, replayed per mob (machines/web/ExtremeEntityCrusher); kubatech's overclock, not GT's.
            r.name = com.gtnhplanner.machines.MobDrops.MACHINE_TYPE + ": " + EecCards.mobName(node);
            r.category = com.gtnhplanner.machines.MobDrops.MAP_ID;
            r.source.rawRecipeId = EecCards.mob(node);
            r.metadata.put("eec", JsonTree.parse(eec.toString()));
        }
        final double special = r.specialValue != null ? r.specialValue : 0;
        r.machineConfigControls = HandlerData.recipeControls(entry, special);
        r.machineHandlers = HandlerData.handlers(entry, r.minimumTier, r.durationTicks, r.eut, r.machineConfigControls);
        // The Algae Farm's recipes are registered at 0 EU/t: the recipe carries the draw of the hatch that grows it.
        return AlgaePond.isRecipe(r) ? AlgaePond.enrich(r) : r;
    }

    /**
     * Which of the recipe's outputs each of the card's output ports is: the same place when the two lists line up,
     * else the first output of the port's resource (the card folds a resource made twice into one port); -1 when none.
     */
    public static int recipeOutput(final Web.Recipe recipe, final List<Port<?>> ports, final int port) {
        final List<Web.Resource> outputs = recipe.outputs;
        if (outputs == null) return -1;
        final ResourceKey key = ports.get(port)
            .resource();
        if (outputs.size() == ports.size() && sameResource(outputs.get(port), key)) return port;
        for (int i = 0; i < outputs.size(); i++) if (sameResource(outputs.get(i), key)) return i;
        return -1;
    }

    private static boolean sameResource(final Web.Resource resource, @Nullable final ResourceKey key) {
        return key != null && key.kind()
            .json()
            .equals(resource.kind)
            && key.id()
                .equals(resource.id);
    }

    /**
     * A crafting-grid recipe as the website's dataset carries it: a tick at no EU on the shaped or shapeless crafting
     * map, which lists no machines; the website's rules give it the Auto Workbench and the crafting table
     * ({@link RecipeRules#machineHandlers}).
     */
    private static Web.Recipe craftingRecipe(final Node node) {
        final CraftingRecord read = craftingRecord(node);
        final Web.Recipe dataset = read == null ? null : CraftingDatasetRecipe.of(read);
        if (dataset != null) {
            // The game's own recipe as the dataset carries it (core-model CraftingDatasetRecipe over the shared
            // crafting reader's record): ore dictionary cells as ore dictionary names, no special value.
            dataset.id = "crafting:" + com.gtnhplanner.nei.NeiRecipes.id(node);
            return dataset;
        }
        final Web.Recipe r = new Web.Recipe();
        final String type = AutoWorkbench.machineType(node);
        r.id = "crafting:" + com.gtnhplanner.nei.NeiRecipes.id(node);
        r.name = node.machineName;
        r.kind = "gregtech_machine";
        r.category = "crafting";
        r.machineType = type;
        r.durationTicks = 1;
        r.eut = 0;
        r.minimumTier = "NONE";
        r.specialValue = 0.0;
        r.source = new Web.Source();
        r.source.recipeMap = type;
        r.inputs = resources(node.inputs);
        r.outputs = resources(node.outputs);
        return r;
    }

    /** The crafting reader the cards share (the client thread's), and each card's recipe once found. */
    private static final CraftingReader CRAFTING = new CraftingReader();
    private static final Map<Node, Optional<CraftingRecord>> CRAFTING_RECORDS = Collections
        .synchronizedMap(new WeakHashMap<>());

    /**
     * The game recipe a crafting card's NEI page shows, read by the shared crafting reader: the CraftingManager recipe
     * that makes the page's result and takes, in each cell, one of the items the page cycles there. Null when none fits
     * (the page of a recipe class the reader cannot open).
     */
    @Nullable
    static CraftingRecord craftingRecord(final Node node) {
        return CRAFTING_RECORDS.computeIfAbsent(node, n -> Optional.ofNullable(findCraftingRecord(n)))
            .orElse(null);
    }

    @Nullable
    private static CraftingRecord findCraftingRecord(final Node node) {
        final RecipeHandlerRef ref = com.gtnhplanner.nei.RecipeRefs.of(com.gtnhplanner.nei.NeiRecipes.id(node));
        if (ref == null || !AutoWorkbench.crafts(ref.handler)) return null;
        final PositionedStack result = ref.handler.getResultStack(ref.recipeIndex);
        final List<PositionedStack> shown = ref.handler.getIngredientStacks(ref.recipeIndex);
        if (result == null || shown == null) return null;
        final boolean shapeless = ref.handler instanceof ShapelessRecipeHandler;
        final List<CraftingReader.ShownCell> cells = new ArrayList<>();
        for (int i = 0; i < shown.size(); i++) {
            final PositionedStack cell = shown.get(i);
            if (cell == null) continue;
            // ShapedRecipeHandler lays a cell at 25 + 18 x column, 6 + 18 x row.
            final int column = shapeless ? -1 : (cell.relx - 25) / 18, row = shapeless ? -1 : (cell.rely - 6) / 18;
            cells.add(new CraftingReader.ShownCell(column, row, i, Arrays.asList(cell.items)));
        }
        return CRAFTING.find(result.item, cells);
    }

    /**
     * The website's entry for a game map id. Its dataset came from a pack where GT++'s maps were named gtpp.recipe.*;
     * this game names them gt.recipe.*, and the Industrial Coke Oven's map changed its name.
     */
    @Nullable
    public static HandlerData.MapEntry entry(@Nullable final String mapId) {
        if (mapId == null) return null;
        final HandlerData.MapEntry entry = HandlerData.map(mapId);
        if (entry != null) return entry;
        if (mapId.equals("gt.recipe.industrialcokeoven")) return HandlerData.map("gtpp.recipe.cokeoven");
        return mapId.startsWith("gt.recipe.") ? HandlerData.map("gtpp.recipe." + mapId.substring(10)) : null;
    }

    /**
     * The blocks the website's data lists for one of the card's map's machines, by its handler id ({@code extractor}:
     * every tier's Extractor; {@code steam-extractor}: the bronze one); null asks for the map's first, the website's
     * default. Empty when the data has no such machine.
     */
    public static List<String> handlerItems(final Node node, @Nullable final String handlerId) {
        final HandlerData.MapEntry entry = entry(mapId(node));
        if (entry == null) return List.of();
        String id = handlerId;
        if (id == null && entry.app != null && !entry.app.isEmpty()) id = entry.app.getFirst().id;
        if (id == null && entry.handlers != null)
            for (final HandlerData.Template t : entry.handlers) if (t.primary) id = t.id;
        if (id == null) return List.of();
        if (entry.app != null) for (final HandlerData.AppHandler a : entry.app)
            if (a.id.equals(id) && a.items != null && !a.items.isEmpty()) return a.items;
        if (entry.handlers != null) for (final HandlerData.Template t : entry.handlers)
            if (t.id.equals(id) && t.items != null && !t.items.isEmpty()) return t.items;
        return List.of();
    }

    /** The blocks of the website's machine {@code item} (a game item id) is one of: a single block's every tier. */
    public static List<String> machineItems(final Node node, final String item) {
        final HandlerData.MapEntry entry = entry(mapId(node));
        if (entry == null) return List.of();
        if (entry.app != null) for (final HandlerData.AppHandler a : entry.app)
            if (a.items != null && a.items.contains(item)) return a.items;
        if (entry.handlers != null) for (final HandlerData.Template t : entry.handlers)
            if (t.items != null && t.items.contains(item)) return t.items;
        return List.of();
    }

    private static double number(@Nullable final Object value) {
        return value instanceof final Number n ? n.doubleValue() : 0;
    }

    /** The ports as the dataset's resource amounts (kind, id, amount, chance, name). */
    public static List<Web.Resource> resources(final List<Port<?>> ports) {
        final List<Web.Resource> out = new ArrayList<>();
        for (final Port<?> port : ports) {
            final Web.Resource res = new Web.Resource();
            if (port.getValue() instanceof final FluidStack fluid) {
                res.kind = "fluid";
                res.id = GameIds.fluidId(fluid);
            } else {
                res.kind = "item";
                res.id = port.getValue() instanceof final ItemStack stack ? GameIds.itemId(stack) : "";
            }
            res.amount = port.amount();
            // GT keeps a chance in ten-thousandths: the website has it as that exact fraction.
            if (port.getChance() < 1) res.chance = Math.round(port.getChance() * 10_000d) / 10_000d;
            res.displayName = port.getDisplayName();
            out.add(res);
        }
        return out;
    }

    /**
     * The card's settings ({@code cfg}: its own, or a what-if copy's) as the website's node fields, its machine matched
     * to one of the recipe's handlers.
     */
    public static Web.Node node(final Node node, final MachineConfig cfg, final Web.Recipe recipe) {
        final Web.Node n = new Web.Node();
        final String voltage = string(cfg.settings.get(VOLTAGE));
        if (Tiers.isName(voltage)) {
            n.overclockTier = voltage;
            n.hatchVoltageTier = voltage;
        }
        n.hatchAmps = cfg.settings.get(AMPS) instanceof final Number amps ? amps.doubleValue() : 1.0;
        n.powerInputMode = "amps";
        n.energyHatchType = string(cfg.settings.get(HATCH_TYPE));
        n.coilTier = string(cfg.settings.get(COIL));
        final Map<String, String> tiers = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> e : cfg.settings.entrySet())
            if (e.getKey()
                .startsWith(CONTROL_PREFIX) && e.getValue() != null)
                tiers.put(
                    e.getKey()
                        .substring(CONTROL_PREFIX.length()),
                    String.valueOf(e.getValue()));
        n.machineConfigTiers = tiers;
        n.machineHandlerId = handlerId(node, recipe);
        if (steam(recipe, n.machineHandlerId)) {
            // Steam runs at no voltage (the card has no tier chip): a tier left in its settings overclocks nothing.
            n.overclockTier = null;
            n.hatchVoltageTier = null;
        }
        n.parallel = 1.0;
        n.machineCount = 1.0;
        return n;
    }

    /** Whether the recipe's handler {@code id} (null: its first) is one of the steam line's (isSteamMachineHandler). */
    private static boolean steam(final Web.Recipe recipe, @Nullable final String id) {
        final List<Web.Handler> handlers = RecipeRules.machineHandlers(recipe);
        for (final Web.Handler h : handlers) if (id == null || h.id.equals(id)) return RecipeRules.isSteamHandler(h);
        return false;
    }

    @Nullable
    private static String string(@Nullable final Object value) {
        return value instanceof final String s && !s.isEmpty() ? s : null;
    }

    /**
     * Which of the recipe's handlers the card's machine is: by its item among the handlers' machines, else a multiblock
     * by its display name (or a table alias of it), a singleblock by its family; null (the map's primary machine) when
     * none matches. Names drift between packs ("Fusion Control Computer Mk-I" here, "Mark I" in the website's data).
     */
    @Nullable
    public static String handlerId(final Node node, final Web.Recipe recipe) {
        final ItemStack machine = machineStack(node);
        final List<Web.Handler> handlers = RecipeRules.machineHandlers(recipe);
        // A crafting card: the Auto Workbench, else the crafting table's hand craft (the website's second handler).
        if (RecipeRules.isHandCraftingRecipeMap(recipe) && handlers.size() > 1)
            return AutoWorkbench.is(machine) ? RecipeRules.AUTO_WORKBENCH_HANDLER_ID : handlers.get(1).id;
        if (machine == null) return null;
        // By the machine itself: the template that lists it, or the family it folded into.
        final HandlerData.MapEntry entry = entry(mapId(node));
        if (entry != null && entry.handlers != null) {
            final String item = GameIds.itemId(machine);
            for (final HandlerData.Template t : entry.handlers) {
                if (t.items == null || !t.items.contains(item)) continue;
                for (final Web.Handler h : handlers) if (h.id.equals(t.id)) return h.id;
                final String family = RecipeRules.familyLabel(t.label);
                for (final Web.Handler h : handlers) if (h.label.equals(family)) return h.id;
            }
        }
        // By name, where the data has no ids: its display name or a table alias, else its family.
        final String name = machine.getDisplayName();
        final String wanted = MachineTable.normalizeMachineName(name);
        for (final Web.Handler h : handlers) if (MachineTable.normalizeMachineName(h.label)
            .equals(wanted)) return h.id;
        final MachineTable.Behaviour table = MachineTable.behaviour(name);
        if (table != null)
            for (final Web.Handler h : handlers) if (MachineTable.behaviour(h.machineType) == table) return h.id;
        final String family = RecipeRules.familyLabel(name);
        for (final Web.Handler h : handlers) if (h.label.equalsIgnoreCase(family)) return h.id;
        return null;
    }

    /** The catalyst the card runs on: its chosen machine, else the recipe's first. */
    @Nullable
    public static ItemStack machineStack(final Node node) {
        final List<ItemStack> catalysts = CardModel.catalystsOf(node);
        for (final ItemStack s : catalysts) if (CardDefaults.matches(s, node.machineName)) return s;
        return catalysts.isEmpty() ? null : catalysts.get(0);
    }
}
