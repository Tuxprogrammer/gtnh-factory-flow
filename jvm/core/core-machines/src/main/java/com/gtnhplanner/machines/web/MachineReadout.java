package com.gtnhplanner.machines.web;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.JsonTree;

/**
 * A recipe on a node as the website's tests read it, for hosts that cannot call the maths object by object: the host
 * hands over a recipe once (JSON in the website's shape) and then a node per question, and gets back one JSON object
 * with every number the card shows (tier, overclock steps, duration, EU/t, parallels, output multipliers, power state,
 * steam, tier controls). The keys are the ones machine-goldens.jsonl.gz records under "expected", so the same file
 * judges the JVM, the JavaScript target and the WebAssembly target. A number that is not finite is the text
 * {@code "Infinity"}, {@code "-Infinity"} or {@code "NaN"}, as in that file.
 */
public final class MachineReadout {

    private final Map<String, Web.Recipe> recipes = new HashMap<>();
    /** Control tables kept under the host's names for them ({@link #addControl}), as parsed JSON. */
    private final Map<String, Object> controls = new HashMap<>();

    /**
     * Keeps a control table (one entry of a {@code machineConfigControls} list, the website's JSON) under a name the
     * host picks; a recipe sent later may then carry {@code {"$ref": name}} in that list instead of the table. The
     * tables are most of a recipe's text and the same few on most of a plan's recipes, so each crosses and is read once.
     */
    public void addControl(final String ref, final String controlJson) {
        controls.put(ref, JsonTree.parse(controlJson));
    }

    /**
     * Keeps a recipe (the website's JSON) under an id for later questions; a second recipe under an id replaces it. A
     * control written {@code {"$ref": name}} is the table kept under that name: the recipe reads exactly as if the
     * table's text stood there.
     */
    public void addRecipe(final String id, final String recipeJson) {
        final Object tree = JsonTree.parse(recipeJson);
        if (tree instanceof Map) {
            final Map<String, Object> o = JsonTree.object(tree);
            resolveControls(o);
            if (o.get("machineHandlers") instanceof List)
                for (final Object h : JsonTree.array(o.get("machineHandlers"))) if (h instanceof Map) resolveControls(JsonTree.object(h));
        }
        final Web.Recipe recipe = WebJson.recipe(tree);
        if (recipe == null) throw new IllegalArgumentException("Not a recipe: " + id);
        recipes.put(id, recipe);
    }

    private void resolveControls(final Map<String, Object> owner) {
        if (!(owner.get("machineConfigControls") instanceof List)) return;
        final List<Object> list = JsonTree.array(owner.get("machineConfigControls"));
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map)) continue;
            final Map<String, Object> c = JsonTree.object(list.get(i));
            if (c.size() != 1 || !(c.get("$ref") instanceof String)) continue;
            final Object table = controls.get((String) c.get("$ref"));
            if (table == null) throw new IllegalArgumentException("No control kept under " + c.get("$ref"));
            list.set(i, table);
        }
    }

    /**
     * Runs the card maths once on a recipe of each of the first {@code maps} recipe maps (built from handlers.json as the
     * game builds a card's), at two tiers, and through the JSON readers: the static tables are read and the code the
     * board calls has run, so a host can do this in idle time after loading and a plan's first cards do not pay for it.
     * Keeps nothing; answers how many cards it computed.
     */
    public static int warm(final int maps) {
        int done = 0;
        WebJson.node(JsonTree.parse("{\"id\":\"warm\",\"overclockTier\":\"HV\",\"machineCount\":1,\"parallel\":1}"));
        for (final HandlerData.MapEntry entry : HandlerData.maps()
            .values()) {
            if (done >= maps * 2) break;
            final Web.Recipe r = new Web.Recipe();
            r.id = entry.id + ":warm";
            r.name = "warm";
            r.machineType = entry.name;
            r.durationTicks = 100;
            r.eut = 30;
            r.minimumTier = HandlerData.recipeMinimumTier(r.eut);
            r.source = new Web.Source();
            r.source.recipeMap = entry.name;
            r.inputs = new ArrayList<>();
            r.outputs = new ArrayList<>();
            r.machineConfigControls = HandlerData.recipeControls(entry, 0);
            r.machineHandlers = HandlerData.handlers(entry, r.minimumTier, r.durationTicks, r.eut, r.machineConfigControls);
            for (final String tier : new String[] { "LV", "IV" }) {
                final Web.Node node = new Web.Node();
                node.overclockTier = tier;
                try {
                    final Map<String, Object> out = card(r, node);
                    if (out != null) JsonTree.write(out);
                } catch (final RuntimeException ignored) {
                    // A map the plain recipe does not suit: the next one warms the same code.
                }
                done++;
            }
        }
        return done;
    }

    /**
     * The numbers of a kept recipe on a node (JSON in the website's node shape), as JSON; null when the recipe is a
     * machine this module leaves to the mod's own models (Tree Growth Simulator, Bacterial Vat).
     */
    @Nullable
    public String read(final String recipeId, final String nodeJson) {
        final Web.Recipe recipe = recipes.get(recipeId);
        if (recipe == null) throw new IllegalArgumentException("No recipe kept under " + recipeId);
        final Web.Node node = WebJson.node(JsonTree.parse(nodeJson));
        if (node == null) throw new IllegalArgumentException("Not a node");
        final Map<String, Object> out = readout(recipe, node);
        return out == null ? null : JsonTree.write(out);
    }

    /**
     * What a board card reads of the maths for a kept recipe on a node, in the website's own shapes and names
     * (src/lib/solver): "stats" (getOverclockedRecipeStats), "power" (getNodePowerReport, present when hasPowerReport),
     * "steam" (getNodeSteamReport, present for a steam machine), "parallel" and "structuralParallel"
     * (getMachineParallelMultiplier, getMachineStructuralParallels on the machine's recipe) and "outputMultipliers"
     * (getMachineOutputMultiplier per output of the machine's recipe, at the overclocked tier). An absent optional field
     * is left out, never null. Null when this module leaves the machine to the mod's own models.
     */
    @Nullable
    public String card(final String recipeId, final String nodeJson) {
        final Web.Recipe recipe = recipes.get(recipeId);
        if (recipe == null) throw new IllegalArgumentException("No recipe kept under " + recipeId);
        final Web.Node node = WebJson.node(JsonTree.parse(nodeJson));
        if (node == null) throw new IllegalArgumentException("Not a node");
        final Map<String, Object> out = card(recipe, node);
        return out == null ? null : JsonTree.write(out);
    }

    @Nullable
    static Map<String, Object> card(final Web.Recipe recipe, final Web.Node node) {
        if (!NodeMath.covers(recipe, node)) return null;
        final Map<String, Object> out = new LinkedHashMap<>();
        final Overclock.Stats s = Overclock.stats(recipe, node);
        final Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("tier", s.tier());
        stats.put("minimumTier", s.minimumTier());
        stats.put("overclockSteps", s.overclockSteps());
        stats.put("perfectOverclockSteps", s.perfectOverclockSteps());
        stats.put("perfectSpeedFactor", n(s.perfectSpeedFactor()));
        stats.put("perfectEuFactor", n(s.perfectEuFactor()));
        stats.put("durationTicks", n(s.durationTicks()));
        stats.put("eut", n(s.eut()));
        out.put("stats", stats);

        if (PowerReport.has(recipe, node)) {
            final PowerReport.Report p = PowerReport.report(recipe, node);
            final Map<String, Object> power = new LinkedHashMap<>();
            power.put("state", p.state());
            if (p.recipeGateReason() != null) power.put("recipeGateReason", p.recipeGateReason());
            power.put("tier", p.tier());
            power.put("minimumTier", p.minimumTier());
            power.put("hatches", p.hatches());
            if (p.hatchTypeLabel() != null) power.put("hatchTypeLabel", p.hatchTypeLabel());
            if (p.hatchChip() != null) power.put("hatchChip", p.hatchChip());
            power.put("typedBudget", p.typedBudget());
            power.put("isMultiblock", p.isMultiblock());
            power.put("amps", n(p.amps()));
            power.put("poolEuT", n(p.poolEuT()));
            power.put("singleDrawEuT", n(p.singleDrawEuT()));
            power.put("parallels", n(p.parallels()));
            power.put("drawEuT", n(p.drawEuT()));
            power.put("overclockSteps", p.overclockSteps());
            power.put("perfectOverclockSteps", p.perfectOverclockSteps());
            power.put("perfectSpeedFactor", n(p.perfectSpeedFactor()));
            power.put("perfectEuFactor", n(p.perfectEuFactor()));
            power.put("usage", n(p.usage()));
            out.put("power", power);
        }

        final PowerReport.Steam steam = PowerReport.steam(recipe, node);
        if (steam != null) {
            final Map<String, Object> st = new LinkedHashMap<>();
            st.put("drawSteamPerTick", n(steam.drawSteamPerTick()));
            st.put("singleDrawSteamPerTick", n(steam.singleDrawSteamPerTick()));
            st.put("parallels", n(steam.parallels()));
            st.put("isMultiblock", steam.isMultiblock());
            st.put("highPressure", steam.highPressure());
            out.put("steam", st);
        }

        final Web.Recipe effective = RecipeRules.applyHandler(recipe, node);
        out.put("parallel", n(MachineEffects.parallelMultiplier(effective, node)));
        out.put("structuralParallel", n(MachineEffects.structuralParallels(effective, node)));
        final List<Object> multipliers = new ArrayList<>();
        for (final Web.Resource output : effective.outputs)
            multipliers.add(n(MachineEffects.outputMultiplier(effective, node, output, s.tier())));
        out.put("outputMultipliers", multipliers);
        return out;
    }

    /** Whether the number is one the host can read as JSON text: finite as it is, otherwise the file's word for it. */
    static Object n(final double value) {
        if (Double.isNaN(value)) return "NaN";
        if (Double.isInfinite(value)) return value > 0 ? "Infinity" : "-Infinity";
        return value;
    }

    @Nullable
    static Map<String, Object> readout(final Web.Recipe recipe, final Web.Node node) {
        if (!NodeMath.covers(recipe, node)) return null;
        final Map<String, Object> out = new LinkedHashMap<>();
        final Overclock.Stats s = Overclock.stats(recipe, node);
        out.put("tier", s.tier());
        out.put("minimumTier", s.minimumTier());
        out.put("overclockSteps", s.overclockSteps());
        out.put("perfectOverclockSteps", s.perfectOverclockSteps());
        out.put("perfectSpeedFactor", n(s.perfectSpeedFactor()));
        out.put("perfectEuFactor", n(s.perfectEuFactor()));
        out.put("durationTicks", n(s.durationTicks()));
        out.put("eut", n(s.eut()));

        final Web.Recipe effective = RecipeRules.applyHandler(recipe, node);
        final Web.RuntimeVariant runtime = RuntimeCalculation.select(effective, node);
        out.put(
            "parallels",
            n(
                runtime != null && runtime.parallel != null ? runtime.parallel
                    : MachineEffects.parallelMultiplier(effective, node)));
        out.put("structuralParallels", n(MachineEffects.structuralParallels(effective, node)));
        out.put("durationMultiplier", n(MachineEffects.durationMultiplier(effective, node)));
        out.put("eutMultiplier", n(MachineEffects.eutMultiplier(effective, node)));
        final List<Object> multipliers = new ArrayList<>(), chances = new ArrayList<>();
        final List<Double> multiplierValues = new ArrayList<>();
        for (final Web.Resource output : effective.outputs) {
            final double multiplier = MachineEffects.outputMultiplier(effective, node, output, s.tier());
            multiplierValues.add(multiplier);
            multipliers.add(n(multiplier));
            chances.add(n(output.chance != null ? output.chance : 1));
        }
        out.put("outputMultipliers", multipliers);
        final List<Web.Resource> runtimeOutputs = RuntimeCalculation.outputs(effective, node);
        final List<Object> amounts = new ArrayList<>();
        if (runtimeOutputs != null) for (final Web.Resource output : runtimeOutputs) amounts.add(n(output.amount));
        else for (int i = 0; i < effective.outputs.size(); i++)
            amounts.add(n(effective.outputs.get(i).amount * multiplierValues.get(i)));
        out.put("outputAmounts", amounts);
        out.put("chances", chances);
        out.put("machineType", effective.machineType);
        out.put("multiblock", Power.isMultiblock(effective));
        final String runTier = Power.runTier(effective, node);
        out.put("runTier", runTier);
        out.put("amps", n(Power.amps(effective, node)));
        final int ordinal = Power.effectiveVoltageOrdinal(effective, node, runTier);
        out.put("voltageOrdinal", ordinal);
        out.put("heatDiscount", n(Heat.discount(effective, node, runTier, ordinal)));

        if (PowerReport.has(recipe, node)) {
            final PowerReport.Report p = PowerReport.report(recipe, node);
            out.put("powerState", p.state());
            out.put("recipeGateReason", p.recipeGateReason());
            out.put("poolEuT", n(p.poolEuT()));
            out.put("singleDrawEuT", n(p.singleDrawEuT()));
        }

        final PowerReport.Steam steam = PowerReport.steam(recipe, node);
        if (steam != null) {
            final Map<String, Object> st = new LinkedHashMap<>();
            st.put("perTick", n(steam.drawSteamPerTick()));
            st.put("singlePerTick", n(steam.singleDrawSteamPerTick()));
            st.put("parallels", n(steam.parallels()));
            st.put("multiblock", steam.isMultiblock());
            st.put("highPressure", steam.highPressure());
            out.put("steam", st);
        }

        final List<RecipeRules.TierControl> controls = new ArrayList<>(
            RecipeRules.configTierControls(effective, node.machineConfigTiers));
        final RecipeRules.TierControl coil = RecipeRules.coilTierControl(effective, node.coilTier);
        if (coil != null) controls.add(coil);
        final List<Object> list = new ArrayList<>();
        for (final RecipeRules.TierControl c : controls) {
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("label", c.label());
            m.put("minimumKey", c.minimum().key);
            m.put("currentKey", c.current().key);
            m.put("minimumIndex", c.minimumIndex());
            final List<Object> keys = new ArrayList<>();
            for (final Web.TierOption t : c.tiers()) keys.add(t.key);
            m.put("tiers", keys);
            list.add(m);
        }
        out.put("controls", list);
        return out;
    }
}
