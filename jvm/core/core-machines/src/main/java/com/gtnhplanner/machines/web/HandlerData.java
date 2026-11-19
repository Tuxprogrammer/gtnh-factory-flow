package com.gtnhplanner.machines.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.JsMath;
import com.gtnhplanner.core.base.JsonTree;

/**
 * The website's machine data per recipe map ({@code assets/gtnhplanner/machines/handlers.json}, written by its
 * {@code tools/audits/export-mod-machine-data.mjs}, never hand-edited): which machines run each map, as templates the
 * dataset pipeline instantiates per recipe, and the map's recipe-level settings. A recipe built here carries exactly
 * the {@code machineHandlers} and {@code machineConfigControls} the website's own copy of it does.
 */
public final class HandlerData {

    private HandlerData() {}

    /** A recipe map: its game id, its name (the website's machineType and recipe map), and its machines. */
    public static final class MapEntry {

        public String id;
        public String name;
        public boolean handlersOnRecipes;
        @Nullable
        public List<Template> handlers;
        @Nullable
        public List<String> controls;
        public boolean coilFromSpecialValue;
        @Nullable
        public List<AppHandler> app;
    }

    /** A machine template (instantiateRecipeMachineHandlers in the dataset pipeline). */
    public static final class Template {

        public String id;
        public String label;
        public String kind;
        public boolean primary;
        @Nullable
        public String minimumTier;
        @Nullable
        public String maximumTier;
        @Nullable
        public List<String> availableTiers;
        public boolean perfectOverclock;
        @Nullable
        public Double durationMultiplier;
        @Nullable
        public Double eutMultiplier;
        @Nullable
        public Double durationTicks;
        @Nullable
        public Double eut;
        @Nullable
        public List<String> controls;
        /** The machines this handler stands for, by their website ids ({@code gregtech:gt.blockmachines@1193}). */
        @Nullable
        public List<String> items;
    }

    /** What the website's getRecipeMachineHandlers gives the map's first recipe. */
    public static final class AppHandler {

        public String id;
        public String label;
        public String machineType;
        public String kind;
        @Nullable
        public Double durationTicks;
        @Nullable
        public Double eut;
        /** The machine table entry it runs on, if any. */
        @Nullable
        public String table;
        public boolean multiblock;
        @Nullable
        public List<String> items;
    }

    /** A control in the file's compact form: options carry an icon id and a name in place of a resource. */
    private static final class CompactOption {

        String key, label, icon, name;
        Double heat, durationMultiplier, eutMultiplier, outputMultiplier, parallelMultiplier, parallelPerVoltageTier,
            parallelVoltageBase;
    }

    private static final class CompactControl {

        String id, label, minimumKey, defaultKey;
        Web.Numeric numeric;
        Boolean minimumFromSpecialValue, minimumHeatFromSpecialValue;
        List<CompactOption> tiers;
    }

    /** A heating coil's key, label and heat in K. */
    public record Coil(String key, String label, double heat) {}

    private static Map<String, MapEntry> maps;
    private static Map<String, Web.Control> controls;
    private static List<Coil> coils;
    private static List<String> tierNames;
    /** The dataset's tier names for a recipe's draw and their voltages (it has no UMV: such draws read UXV). */
    private static List<String> recipeTierNames;
    private static List<Double> recipeTierVoltages;

    /**
     * A recipe's minimumTier as the website's dataset writes it (normalize-oracle-export.mjs voltageTierForEu): the
     * first of its tiers whose voltage carries |EU/t|, else MAX. Its table skips UMV, so a UMV draw is marked UXV and a
     * UXV draw OpV (read as UXV): the website's own numbers, kept.
     */
    public static String recipeMinimumTier(final double eut) {
        load();
        final double abs = Math.abs(eut);
        for (int i = 0; i < recipeTierNames.size(); i++)
            if (abs <= recipeTierVoltages.get(i)) return recipeTierNames.get(i);
        return "MAX";
    }

    /** The entry for a game recipe map id ({@code RecipeMap.unlocalizedName}, or "smelting"), or null. */
    @Nullable
    public static MapEntry map(@Nullable final String id) {
        load();
        return id == null ? null : maps.get(id);
    }

    /** Every map in the file. */
    public static Map<String, MapEntry> maps() {
        load();
        return Collections.unmodifiableMap(maps);
    }

    /** The 14 heating coils, cupronickel first. */
    public static List<Coil> coils() {
        load();
        return coils;
    }

    /**
     * The map's recipe-level settings for a recipe with this special value: on the blast furnace maps a positive
     * special value makes the first coil hot enough (the last if none is) the coil's minimum and default.
     */
    public static List<Web.Control> recipeControls(final MapEntry entry, final double specialValue) {
        final List<Web.Control> out = new ArrayList<>();
        if (entry.controls != null) for (final String ref : entry.controls) out.add(
            controls.get(ref)
                .copy());
        if (entry.coilFromSpecialValue && specialValue > 0) for (final Web.Control c : out) {
            if (!c.id.equals("heatingCoil")) continue;
            Coil coil = coils.get(coils.size() - 1);
            for (final Coil k : coils) if (k.heat >= specialValue) {
                coil = k;
                break;
            }
            c.minimumKey = coil.key;
            c.defaultKey = coil.key;
        }
        return out;
    }

    /**
     * The recipe's machine handlers, from the map's templates; null where the website's recipes carry none (it then
     * falls back to a machine named for the map). The minimum tier is the higher of the template's and the recipe's.
     */
    @Nullable
    public static List<Web.Handler> handlers(final MapEntry entry, final String recipeMinimumTier,
        final double durationTicks, final double eut, final List<Web.Control> recipeControls) {
        if (!entry.handlersOnRecipes || entry.handlers == null) return null;
        final int recipeTier = tierIndex(recipeMinimumTier);
        final List<Web.Handler> out = new ArrayList<>();
        for (final Template t : entry.handlers) {
            final int index = Math.max(tierIndex(t.minimumTier), recipeTier);
            final Web.Handler h = new Web.Handler();
            h.id = t.id;
            h.label = t.label;
            h.kind = t.kind;
            h.machineType = t.label;
            h.minimumTier = index >= 0 ? tierNames.get(index) : recipeMinimumTier;
            h.maximumTier = t.maximumTier;
            h.availableTiers = t.availableTiers;
            if (t.durationMultiplier != null)
                h.durationTicks = Math.max(1, JsMath.round(durationTicks * t.durationMultiplier));
            if (t.eutMultiplier != null) h.eut = Math.max(0, JsMath.round(eut * t.eutMultiplier * 100) / 100);
            if (t.durationTicks != null) h.durationTicks = Math.max(1, JsMath.round(t.durationTicks));
            if (t.eut != null) h.eut = Math.max(0, t.eut);
            if (t.perfectOverclock) h.perfectOverclock = true;
            if (t.controls != null) {
                final List<Web.Control> own = new ArrayList<>();
                final Set<String> ownIds = new HashSet<>();
                for (final String ref : t.controls) {
                    final Web.Control c = controls.get(ref)
                        .copy();
                    own.add(c);
                    ownIds.add(c.id);
                }
                for (final Web.Control c : recipeControls) if (!ownIds.contains(c.id)) own.add(c);
                h.machineConfigControls = own;
            }
            out.add(h);
        }
        return out;
    }

    /** The tier's index, any case, ignoring spaces around it; -1 for anything else. */
    private static int tierIndex(@Nullable final String tier) {
        if (tier == null) return -1;
        for (int i = 0; i < tierNames.size(); i++) if (tierNames.get(i)
            .equalsIgnoreCase(tier.trim())) return i;
        return -1;
    }

    private static synchronized void load() {
        if (maps != null) return;
        final Map<String, MapEntry> byId = new HashMap<>();
        final Map<String, Web.Control> byRef = new HashMap<>();
        final List<Coil> coilList = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        final List<String> recipeNames = new ArrayList<>();
        final List<Double> recipeVoltages = new ArrayList<>();
        try {
            final String text = ResourceText.read("/assets/gtnhplanner/machines/handlers.json");
            if (text != null) {
                final Map<String, Object> root = JsonTree.object(JsonTree.parse(text));
                for (final Object e : JsonTree.array(root.get("tiers"))) names.add((String) e);
                if (root.containsKey("recipeTiers")) for (final Object e : JsonTree.array(root.get("recipeTiers"))) {
                    final List<Object> pair = JsonTree.array(e);
                    recipeNames.add((String) pair.get(0));
                    recipeVoltages.add((Double) pair.get(1));
                }
                for (final Object e : JsonTree.array(root.get("coils"))) {
                    final Map<String, Object> c = JsonTree.object(e);
                    coilList.add(
                        new Coil(
                            (String) c.get("key"),
                            (String) c.get("label"),
                            (Double) c.get("heat")));
                }
                for (final Map.Entry<String, Object> e : JsonTree.object(root.get("controls"))
                    .entrySet()) byRef.put(e.getKey(), control(compactControl(JsonTree.object(e.getValue()))));
                for (final Object e : JsonTree.array(root.get("maps"))) {
                    final MapEntry m = mapEntry(JsonTree.object(e));
                    byId.put(m.id, m);
                }
            }
        } catch (final RuntimeException ignored) {
            // Without the file every recipe falls back to a machine named for its map, as the website's would.
        }
        tierNames = names.isEmpty() ? List.of(Tiers.NAMES) : names;
        if (recipeNames.isEmpty()) for (int i = 0; i < Tiers.NAMES.length - 1; i++) {
            recipeNames.add(Tiers.NAMES[i]);
            recipeVoltages.add(Tiers.MAX_EUT[i]);
        }
        recipeTierNames = recipeNames;
        recipeTierVoltages = recipeVoltages;
        coils = coilList;
        controls = byRef;
        maps = byId;
    }

    private static MapEntry mapEntry(final Map<String, Object> o) {
        final MapEntry m = new MapEntry();
        m.id = JsonTree.str(o, "id");
        m.name = JsonTree.str(o, "name");
        m.handlersOnRecipes = WebJson.flag(o, "handlersOnRecipes");
        m.handlers = WebJson.list(o, "handlers", HandlerData::template);
        m.controls = WebJson.strings(o, "controls");
        m.coilFromSpecialValue = WebJson.flag(o, "coilFromSpecialValue");
        m.app = WebJson.list(o, "app", HandlerData::appHandler);
        return m;
    }

    private static Template template(final Map<String, Object> o) {
        final Template t = new Template();
        t.id = JsonTree.str(o, "id");
        t.label = JsonTree.str(o, "label");
        t.kind = JsonTree.str(o, "kind");
        t.primary = WebJson.flag(o, "primary");
        t.minimumTier = JsonTree.str(o, "minimumTier");
        t.maximumTier = JsonTree.str(o, "maximumTier");
        t.availableTiers = WebJson.strings(o, "availableTiers");
        t.perfectOverclock = WebJson.flag(o, "perfectOverclock");
        t.durationMultiplier = JsonTree.boxed(o, "durationMultiplier");
        t.eutMultiplier = JsonTree.boxed(o, "eutMultiplier");
        t.durationTicks = JsonTree.boxed(o, "durationTicks");
        t.eut = JsonTree.boxed(o, "eut");
        t.controls = WebJson.strings(o, "controls");
        t.items = WebJson.strings(o, "items");
        return t;
    }

    private static AppHandler appHandler(final Map<String, Object> o) {
        final AppHandler h = new AppHandler();
        h.id = JsonTree.str(o, "id");
        h.label = JsonTree.str(o, "label");
        h.machineType = JsonTree.str(o, "machineType");
        h.kind = JsonTree.str(o, "kind");
        h.durationTicks = JsonTree.boxed(o, "durationTicks");
        h.eut = JsonTree.boxed(o, "eut");
        h.table = JsonTree.str(o, "table");
        h.multiblock = WebJson.flag(o, "multiblock");
        h.items = WebJson.strings(o, "items");
        return h;
    }

    private static CompactControl compactControl(final Map<String, Object> o) {
        final CompactControl c = new CompactControl();
        c.id = JsonTree.str(o, "id");
        c.label = JsonTree.str(o, "label");
        c.minimumKey = JsonTree.str(o, "minimumKey");
        c.defaultKey = JsonTree.str(o, "defaultKey");
        c.numeric = o.get("numeric") instanceof Map ? WebJson.numeric(JsonTree.object(o.get("numeric"))) : null;
        c.minimumFromSpecialValue = JsonTree.bool(o, "minimumFromSpecialValue");
        c.minimumHeatFromSpecialValue = JsonTree.bool(o, "minimumHeatFromSpecialValue");
        c.tiers = WebJson.list(o, "tiers", HandlerData::compactOption);
        return c;
    }

    private static CompactOption compactOption(final Map<String, Object> o) {
        final CompactOption c = new CompactOption();
        c.key = JsonTree.str(o, "key");
        c.label = JsonTree.str(o, "label");
        c.icon = JsonTree.str(o, "icon");
        c.name = JsonTree.str(o, "name");
        c.heat = JsonTree.boxed(o, "heat");
        c.durationMultiplier = JsonTree.boxed(o, "durationMultiplier");
        c.eutMultiplier = JsonTree.boxed(o, "eutMultiplier");
        c.outputMultiplier = JsonTree.boxed(o, "outputMultiplier");
        c.parallelMultiplier = JsonTree.boxed(o, "parallelMultiplier");
        c.parallelPerVoltageTier = JsonTree.boxed(o, "parallelPerVoltageTier");
        c.parallelVoltageBase = JsonTree.boxed(o, "parallelVoltageBase");
        return c;
    }

    private static Web.Control control(final CompactControl c) {
        final Web.Control out = new Web.Control();
        out.id = c.id;
        out.label = c.label;
        out.minimumKey = c.minimumKey;
        out.defaultKey = c.defaultKey;
        out.numeric = c.numeric;
        out.minimumFromSpecialValue = c.minimumFromSpecialValue;
        out.minimumHeatFromSpecialValue = c.minimumHeatFromSpecialValue;
        out.tiers = new ArrayList<>();
        if (c.tiers != null) for (final CompactOption o : c.tiers) {
            final Web.TierOption t = new Web.TierOption();
            t.key = o.key;
            t.label = o.label;
            t.heat = o.heat;
            t.durationMultiplier = o.durationMultiplier;
            t.eutMultiplier = o.eutMultiplier;
            t.outputMultiplier = o.outputMultiplier;
            t.parallelMultiplier = o.parallelMultiplier;
            t.parallelPerVoltageTier = o.parallelPerVoltageTier;
            t.parallelVoltageBase = o.parallelVoltageBase;
            if (o.icon != null || o.name != null) {
                final Web.Resource r = new Web.Resource();
                r.kind = "item";
                r.id = o.icon;
                r.amount = 1;
                r.displayName = o.name;
                r.consumed = false;
                t.resource = r;
            }
            out.tiers.add(t);
        }
        return out;
    }
}
