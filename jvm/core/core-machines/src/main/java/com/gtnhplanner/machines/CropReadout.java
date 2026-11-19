package com.gtnhplanner.machines;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.gtnhplanner.core.base.JsonTree;
import com.gtnhplanner.core.machines.GameRates;

/**
 * A crop card as the website's crop tests read it (crop-goldens.jsonl.gz), for hosts that cannot call the model object
 * by object: JSON in with a crop's stats, the harvester, the card's settings, the crop count and the pack's rates, JSON
 * out with every number the card shows under the keys that file records as "expected". A number that is not finite is
 * the text {@code "Infinity"}, {@code "-Infinity"} or {@code "NaN"}, as there.
 */
public final class CropReadout {

    private CropReadout() {}

    /**
     * The case {"stats":{...},"handler":id,"tiers":{setting:value},"crops":n,"gameRates":{...}|absent} as the card's
     * numbers. The case's rates are in force for the call and put back as they were.
     */
    public static String read(final String caseJson) {
        final Map<String, Object> c = JsonTree.object(JsonTree.parse(caseJson));
        final Map<String, Object> r = JsonTree.object(c.get("gameRates"));
        final GameRates before = GameRates.active();
        GameRates.install(
            new GameRates(factor(r, "beesYield"), factor(r, "ic2CropsYield"), factor(r, "recipeDurationDivisor")));
        try {
            return JsonTree.write(card(c));
        } finally {
            GameRates.install(before);
        }
    }

    private static double factor(final Map<String, Object> from, final String key) {
        final Double v = JsonTree.boxed(from, key);
        return v != null ? v : 1;
    }

    private static Object n(final double value) {
        if (Double.isNaN(value)) return "NaN";
        if (Double.isInfinite(value)) return value > 0 ? "Infinity" : "-Infinity";
        return value;
    }

    private static Map<String, Object> card(final Map<String, Object> c) {
        final CropsNh.Stats stats = stats(JsonTree.object(c.get("stats")));
        final Map<String, Object> settings = new HashMap<>();
        for (final Map.Entry<String, Object> e : JsonTree.object(c.get("tiers")).entrySet())
            settings.put(e.getKey(), String.valueOf(e.getValue()));
        settings.put(CropsNh.HARVESTER, JsonTree.str(c, "handler"));
        final long crops = (long) JsonTree.num(c, "crops");

        final Map<String, Object> out = new LinkedHashMap<>();
        final CropsNh.Setup setup = CropsNh.setup(settings, stats);
        final Map<String, Object> s = new LinkedHashMap<>();
        s.put("id", setup.id());
        s.put("tierIndex", setup.tier());
        s.put("growthUnits", setup.growthUnits());
        s.put("fertilizerUnits", setup.fertilizerUnits());
        s.put("harvestUnits", setup.harvestUnits());
        s.put("environmentUnits", setup.environmentUnits());
        s.put("overclocks", setup.overclocks());
        s.put("fertilized", setup.fertilized());
        s.put("subSoil", setup.subSoil());
        out.put("setup", s);

        final CropsNh.Environment env = CropsNh.harvesterEnvironment(setup, CropsNh.environment(settings));
        final Map<String, Object> e = new LinkedHashMap<>();
        e.put("water", env.water());
        e.put("fertilizer", env.fertilizer());
        e.put("biomeBonus", env.biomeBonus());
        out.put("envH", e);
        out.put("nutrient", CropsNh.nutrientScore(env));
        out.put("rate", CropsNh.growthRate(stats, env));
        out.put("ticks", n(CropsNh.harvestTicks(stats, env)));
        out.put("rounds", n(CropsNh.harvestRoundMultiplier(setup)));
        out.put("speed", n(CropsNh.growthSpeedMultiplier(setup)));
        out.put("perMachine", CropsNh.cropsPerMachine(setup));
        out.put("machines", CropsNh.machineCount(setup, crops));
        out.put("slots", CropsNh.upgradeSlots(setup.tier()));
        out.put("used", CropsNh.unitSlotsUsed(setup));
        out.put("eutPerCrop", n(CropsNh.eutPerCrop(setup)));
        out.put("farmEut", n(CropsNh.farmEut(setup)));
        out.put("managerEu", n(CropsNh.managerEuPerHarvest(setup)));
        out.put("durationMultiplier", n(CropsNh.durationMultiplier(stats, settings)));
        out.put("draw", n(CropsNh.drawEut(stats, settings, crops)));
        final List<Object> outputs = new ArrayList<>();
        for (final CropsNh.Drop drop : stats.drops()) outputs.add(n(CropsNh.outputMultiplier(stats, settings, drop.id())));
        out.put("outputMultipliers", outputs);
        // The formulas run on every case without failing, and end on the power line.
        final List<FormulaLine> lines = CropsNh.formulas(stats, settings, crops);
        out.put("formulasEndOnPower", !lines.isEmpty() && "power".equals(lines.get(lines.size() - 1).label()));
        return out;
    }

    private static CropsNh.Stats stats(final Map<String, Object> o) {
        final List<CropsNh.Drop> drops = new ArrayList<>();
        for (final Object d : JsonTree.array(o.get("drops"))) {
            final Map<String, Object> dd = JsonTree.object(d);
            drops.add(
                new CropsNh.Drop(
                    JsonTree.str(dd, "id"),
                    (int) JsonTree.num(dd, "stackSize"),
                    (int) JsonTree.num(dd, "weight")));
        }
        return new CropsNh.Stats(
            (int) JsonTree.num(o, "tier"),
            (int) JsonTree.num(o, "growthPoints"),
            JsonTree.num(o, "dropChance"),
            (int) JsonTree.num(o, "growthCycleTicks"),
            JsonTree.num(o, "growthMultiplier"),
            Boolean.TRUE.equals(JsonTree.bool(o, "machineOnly")),
            (int) JsonTree.num(o, "minSeedBedTier"),
            JsonTree.str(o, "subSoil"),
            drops);
    }
}
