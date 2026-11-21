package com.gtnhplanner.power;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.JsonTree;

/**
 * The tables extracted from the community "GTNH Power Planner 2.9" workbook (the website's
 * {@code src/lib/power/data/power-planner-data.json}, copied verbatim to
 * {@code assets/gtnhplanner/power/}). Never hand-edit that file: it is regenerated on the website from the workbook
 * with Java corrections, and copied here.
 *
 * <p>
 * Plain classes filled from a parsed JSON tree ({@link JsonTree}), by hand: no reflection. Every number is a double,
 * as in JavaScript; an optional number is a nullable {@link Double}, and a sparse ladder
 * ({@code Array<number | null>}) a {@code Double[]} with nulls. A field the file lacks reads as 0, null or empty.
 */
public final class PowerData {

    public static final String RESOURCE = "/assets/gtnhplanner/power/power-planner-data.json";

    private static PowerData data;

    /** The tables, read once. */
    public static synchronized PowerData get() {
        if (data == null) data = parse(readText(RESOURCE));
        return data;
    }

    /** The tables a workbook JSON document holds. */
    public static PowerData parse(final String json) {
        return bind(JsonTree.object(JsonTree.parse(json)));
    }

    /** A resource file of this module, as text. */
    static String readText(final String resource) {
        try (InputStream in = PowerData.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("Missing " + resource);
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new IllegalStateException("Cannot read " + resource, e);
        }
    }

    public static class Fuel {

        public String name;
        public Double euPerLiter;
        public Double euPerItem;
        /** Large-boiler seconds (boilerFuels); for boilers {@code euPerItem} is burn ticks. */
        public Double burnTime;
        public Double eheMaxLps;
        public Double promoterCoefficient;
    }

    public static class LneBase {

        public String name;
        public double multiplier;
        public double litersPerSecond;
        public double boostTicks;
    }

    public static class LneStructureTier {

        public String name;
        public double residueCapacity;
        public double baseDecay;
    }

    public static class LneRobotArm {

        public String name;
        public double tier;
    }

    public static class LnrFuel {

        public String name;
        public double euPerTick;
        public double secondsPerCell;
        public double euPerThousandLiters;
    }

    public static class LnrCoolant {

        public String name;
        public double efficiency;
        public double litersPerSecond;
    }

    public static class LnrBooster {

        public String name;
        public double multiplier;
        public double litersPerSecond;
    }

    public static class LftrFuel {

        public String name;
        public double euPerLiter;
        public String powerLabel;
        public double uSalt;
        public double tSalt;
        public double tbSalt;
        public double uf6;
        public double uranium233PerSecond;
    }

    public static class LftbRecipe {

        public String name;
        public String fuelName;
        public double eut;
        public double durationSeconds;
        public String baseTier;
        public double outputLiters;
    }

    public static class HtgrPebble {

        public String name;
        public double base;
        public double mult;
        public double exp;
    }

    public static class HeatExchangerFluidRule {

        public double threshold;
        public double max;
        public double throttle;
        public double underRatio;
        public double overRatio;
    }

    public static class HeatExchanger {

        public String name;
        /** By hot fluid name, in the workbook's order. */
        public Map<String, HeatExchangerFluidRule> fluids;
    }

    public static class FusionRecipe {

        public String name;
        public double minMark;
        public double euPerLiter;
        public double startupEu;
        public String input1;
        public Double ratio1;
        public String input2;
        public Double ratio2;
        public Double eheMaxLps;
        public String decayOutput;
        public Double[] outputLpsByMark;
        public Double[] drainEutByMark;
        public Double[] compactOutputLpsByMark;
        public Double[] compactDrainEutByMark;
    }

    public static class RotorClass {

        public Double[] efficiencyTight;
        public Double[] efficiencyLoose;
        public Double[] optimalTight;
        public Double[] optimalLoose;
        /** Plasma only. */
        public Double[] euAtOptimalTight;
    }

    public static class Rotor {

        public String name;
        public String unlock;
        public double durability;
        public double overflowTier;
        public RotorClass steam;
        public RotorClass gas;
        public RotorClass plasma;
    }

    public static class RotorSize {

        public String name;
        public double durabilityMult;
        public double efficiencyDelta;
        public double damage;
    }

    public static class SingleblockTier {

        public String tier;
        public double voltage;
        public double ampLoss;
    }

    public static class EohStar {

        public String name;
        public double tier;
        public double durationSeconds;
        public double baseSuccess;
        public double efficiency;
        public double euInput;
        public double euOutput;
        public double starMatter;
    }

    public String source;
    public String extractedAt;
    public List<Fuel> steamGrades;
    public List<Fuel> gasFuels;
    public List<Fuel> gasFuelsXl;
    public List<Fuel> plasmas;
    public List<Fuel> combustionFuels;
    public List<Fuel> eceFuels;
    public List<Fuel> semifluidFuels;
    public List<Fuel> chemFuels;
    public List<Fuel> frostFuels;
    public List<LneBase> lneBases;
    public List<LneStructureTier> lneStructureTiers;
    public List<LneRobotArm> lneRobotArms;
    public List<Fuel> ucfeFuels;
    public List<Fuel> magicSolids;
    public List<Fuel> naquadahRods;
    public List<Fuel> rocketFuels;
    public List<LnrFuel> lnrFuels;
    public List<LnrCoolant> lnrCoolants;
    public List<LnrBooster> lnrBoosters;
    public List<LftrFuel> lftrFuels;
    public List<LftbRecipe> lftbRecipes;
    public List<HtgrPebble> htgrPebbles;
    /** bronzeLiquid, bronzeSolid, steelLiquid, ... tungstensteelSolid. */
    public Map<String, List<Fuel>> boilerFuels;
    public List<HeatExchanger> heatExchangers;
    public List<FusionRecipe> fusionRecipes;
    public List<Rotor> rotors;
    public List<RotorSize> rotorSizes;
    public List<SingleblockTier> singleblockTiers;
    /** Per family, one efficiency per {@link #singleblockTiers} row, null where the family has no machine. */
    public Map<String, Double[]> singleblockEfficiency;
    public List<EohStar> eohStars;

    // ---- filling the tables from the parsed tree

    private static <T> List<T> rows(final Object value, final Function<Map<String, Object>, T> row) {
        final List<T> out = new ArrayList<>();
        for (final Object entry : JsonTree.array(value)) out.add(row.apply(JsonTree.object(entry)));
        return out;
    }

    private static Fuel fuel(final Map<String, Object> m) {
        final Fuel f = new Fuel();
        f.name = JsonTree.str(m, "name");
        f.euPerLiter = JsonTree.boxed(m, "euPerLiter");
        f.euPerItem = JsonTree.boxed(m, "euPerItem");
        f.burnTime = JsonTree.boxed(m, "burnTime");
        f.eheMaxLps = JsonTree.boxed(m, "eheMaxLps");
        f.promoterCoefficient = JsonTree.boxed(m, "promoterCoefficient");
        return f;
    }

    private static LneBase lneBase(final Map<String, Object> m) {
        final LneBase r = new LneBase();
        r.name = JsonTree.str(m, "name");
        r.multiplier = JsonTree.num(m, "multiplier");
        r.litersPerSecond = JsonTree.num(m, "litersPerSecond");
        r.boostTicks = JsonTree.num(m, "boostTicks");
        return r;
    }

    private static LneStructureTier lneStructureTier(final Map<String, Object> m) {
        final LneStructureTier r = new LneStructureTier();
        r.name = JsonTree.str(m, "name");
        r.residueCapacity = JsonTree.num(m, "residueCapacity");
        r.baseDecay = JsonTree.num(m, "baseDecay");
        return r;
    }

    private static LneRobotArm lneRobotArm(final Map<String, Object> m) {
        final LneRobotArm r = new LneRobotArm();
        r.name = JsonTree.str(m, "name");
        r.tier = JsonTree.num(m, "tier");
        return r;
    }

    private static LnrFuel lnrFuel(final Map<String, Object> m) {
        final LnrFuel r = new LnrFuel();
        r.name = JsonTree.str(m, "name");
        r.euPerTick = JsonTree.num(m, "euPerTick");
        r.secondsPerCell = JsonTree.num(m, "secondsPerCell");
        r.euPerThousandLiters = JsonTree.num(m, "euPerThousandLiters");
        return r;
    }

    private static LnrCoolant lnrCoolant(final Map<String, Object> m) {
        final LnrCoolant r = new LnrCoolant();
        r.name = JsonTree.str(m, "name");
        r.efficiency = JsonTree.num(m, "efficiency");
        r.litersPerSecond = JsonTree.num(m, "litersPerSecond");
        return r;
    }

    private static LnrBooster lnrBooster(final Map<String, Object> m) {
        final LnrBooster r = new LnrBooster();
        r.name = JsonTree.str(m, "name");
        r.multiplier = JsonTree.num(m, "multiplier");
        r.litersPerSecond = JsonTree.num(m, "litersPerSecond");
        return r;
    }

    private static LftrFuel lftrFuel(final Map<String, Object> m) {
        final LftrFuel r = new LftrFuel();
        r.name = JsonTree.str(m, "name");
        r.euPerLiter = JsonTree.num(m, "euPerLiter");
        r.powerLabel = JsonTree.str(m, "powerLabel");
        r.uSalt = JsonTree.num(m, "uSalt");
        r.tSalt = JsonTree.num(m, "tSalt");
        r.tbSalt = JsonTree.num(m, "tbSalt");
        r.uf6 = JsonTree.num(m, "uf6");
        r.uranium233PerSecond = JsonTree.num(m, "uranium233PerSecond");
        return r;
    }

    private static LftbRecipe lftbRecipe(final Map<String, Object> m) {
        final LftbRecipe r = new LftbRecipe();
        r.name = JsonTree.str(m, "name");
        r.fuelName = JsonTree.str(m, "fuelName");
        r.eut = JsonTree.num(m, "eut");
        r.durationSeconds = JsonTree.num(m, "durationSeconds");
        r.baseTier = JsonTree.str(m, "baseTier");
        r.outputLiters = JsonTree.num(m, "outputLiters");
        return r;
    }

    private static HtgrPebble htgrPebble(final Map<String, Object> m) {
        final HtgrPebble r = new HtgrPebble();
        r.name = JsonTree.str(m, "name");
        r.base = JsonTree.num(m, "base");
        r.mult = JsonTree.num(m, "mult");
        r.exp = JsonTree.num(m, "exp");
        return r;
    }

    private static HeatExchanger heatExchanger(final Map<String, Object> m) {
        final HeatExchanger r = new HeatExchanger();
        r.name = JsonTree.str(m, "name");
        r.fluids = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> e : JsonTree.object(m.get("fluids"))
            .entrySet()) {
            final Map<String, Object> rule = JsonTree.object(e.getValue());
            final HeatExchangerFluidRule x = new HeatExchangerFluidRule();
            x.threshold = JsonTree.num(rule, "threshold");
            x.max = JsonTree.num(rule, "max");
            x.throttle = JsonTree.num(rule, "throttle");
            x.underRatio = JsonTree.num(rule, "underRatio");
            x.overRatio = JsonTree.num(rule, "overRatio");
            r.fluids.put(e.getKey(), x);
        }
        return r;
    }

    private static FusionRecipe fusionRecipe(final Map<String, Object> m) {
        final FusionRecipe r = new FusionRecipe();
        r.name = JsonTree.str(m, "name");
        r.minMark = JsonTree.num(m, "minMark");
        r.euPerLiter = JsonTree.num(m, "euPerLiter");
        r.startupEu = JsonTree.num(m, "startupEu");
        r.input1 = JsonTree.str(m, "input1");
        r.ratio1 = JsonTree.boxed(m, "ratio1");
        r.input2 = JsonTree.str(m, "input2");
        r.ratio2 = JsonTree.boxed(m, "ratio2");
        r.eheMaxLps = JsonTree.boxed(m, "eheMaxLps");
        r.decayOutput = JsonTree.str(m, "decayOutput");
        r.outputLpsByMark = JsonTree.ladder(m, "outputLpsByMark");
        r.drainEutByMark = JsonTree.ladder(m, "drainEutByMark");
        r.compactOutputLpsByMark = JsonTree.ladder(m, "compactOutputLpsByMark");
        r.compactDrainEutByMark = JsonTree.ladder(m, "compactDrainEutByMark");
        return r;
    }

    @Nullable
    private static RotorClass rotorClass(@Nullable final Object value) {
        if (!(value instanceof Map)) return null;
        final Map<String, Object> m = JsonTree.object(value);
        final RotorClass r = new RotorClass();
        r.efficiencyTight = JsonTree.ladder(m, "efficiencyTight");
        r.efficiencyLoose = JsonTree.ladder(m, "efficiencyLoose");
        r.optimalTight = JsonTree.ladder(m, "optimalTight");
        r.optimalLoose = JsonTree.ladder(m, "optimalLoose");
        r.euAtOptimalTight = JsonTree.ladder(m, "euAtOptimalTight");
        return r;
    }

    private static Rotor rotor(final Map<String, Object> m) {
        final Rotor r = new Rotor();
        r.name = JsonTree.str(m, "name");
        r.unlock = JsonTree.str(m, "unlock");
        r.durability = JsonTree.num(m, "durability");
        r.overflowTier = JsonTree.num(m, "overflowTier");
        r.steam = rotorClass(m.get("steam"));
        r.gas = rotorClass(m.get("gas"));
        r.plasma = rotorClass(m.get("plasma"));
        return r;
    }

    private static RotorSize rotorSize(final Map<String, Object> m) {
        final RotorSize r = new RotorSize();
        r.name = JsonTree.str(m, "name");
        r.durabilityMult = JsonTree.num(m, "durabilityMult");
        r.efficiencyDelta = JsonTree.num(m, "efficiencyDelta");
        r.damage = JsonTree.num(m, "damage");
        return r;
    }

    private static SingleblockTier singleblockTier(final Map<String, Object> m) {
        final SingleblockTier r = new SingleblockTier();
        r.tier = JsonTree.str(m, "tier");
        r.voltage = JsonTree.num(m, "voltage");
        r.ampLoss = JsonTree.num(m, "ampLoss");
        return r;
    }

    private static EohStar eohStar(final Map<String, Object> m) {
        final EohStar r = new EohStar();
        r.name = JsonTree.str(m, "name");
        r.tier = JsonTree.num(m, "tier");
        r.durationSeconds = JsonTree.num(m, "durationSeconds");
        r.baseSuccess = JsonTree.num(m, "baseSuccess");
        r.efficiency = JsonTree.num(m, "efficiency");
        r.euInput = JsonTree.num(m, "euInput");
        r.euOutput = JsonTree.num(m, "euOutput");
        r.starMatter = JsonTree.num(m, "starMatter");
        return r;
    }

    private static PowerData bind(final Map<String, Object> m) {
        final PowerData d = new PowerData();
        d.source = JsonTree.str(m, "source");
        d.extractedAt = JsonTree.str(m, "extractedAt");
        d.steamGrades = rows(m.get("steamGrades"), PowerData::fuel);
        d.gasFuels = rows(m.get("gasFuels"), PowerData::fuel);
        d.gasFuelsXl = rows(m.get("gasFuelsXl"), PowerData::fuel);
        d.plasmas = rows(m.get("plasmas"), PowerData::fuel);
        d.combustionFuels = rows(m.get("combustionFuels"), PowerData::fuel);
        d.eceFuels = rows(m.get("eceFuels"), PowerData::fuel);
        d.semifluidFuels = rows(m.get("semifluidFuels"), PowerData::fuel);
        d.chemFuels = rows(m.get("chemFuels"), PowerData::fuel);
        d.frostFuels = rows(m.get("frostFuels"), PowerData::fuel);
        d.lneBases = rows(m.get("lneBases"), PowerData::lneBase);
        d.lneStructureTiers = rows(m.get("lneStructureTiers"), PowerData::lneStructureTier);
        d.lneRobotArms = rows(m.get("lneRobotArms"), PowerData::lneRobotArm);
        d.ucfeFuels = rows(m.get("ucfeFuels"), PowerData::fuel);
        d.magicSolids = rows(m.get("magicSolids"), PowerData::fuel);
        d.naquadahRods = rows(m.get("naquadahRods"), PowerData::fuel);
        d.rocketFuels = rows(m.get("rocketFuels"), PowerData::fuel);
        d.lnrFuels = rows(m.get("lnrFuels"), PowerData::lnrFuel);
        d.lnrCoolants = rows(m.get("lnrCoolants"), PowerData::lnrCoolant);
        d.lnrBoosters = rows(m.get("lnrBoosters"), PowerData::lnrBooster);
        d.lftrFuels = rows(m.get("lftrFuels"), PowerData::lftrFuel);
        d.lftbRecipes = rows(m.get("lftbRecipes"), PowerData::lftbRecipe);
        d.htgrPebbles = rows(m.get("htgrPebbles"), PowerData::htgrPebble);
        d.boilerFuels = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> e : JsonTree.object(m.get("boilerFuels"))
            .entrySet()) d.boilerFuels.put(e.getKey(), rows(e.getValue(), PowerData::fuel));
        d.heatExchangers = rows(m.get("heatExchangers"), PowerData::heatExchanger);
        d.fusionRecipes = rows(m.get("fusionRecipes"), PowerData::fusionRecipe);
        d.rotors = rows(m.get("rotors"), PowerData::rotor);
        d.rotorSizes = rows(m.get("rotorSizes"), PowerData::rotorSize);
        d.singleblockTiers = rows(m.get("singleblockTiers"), PowerData::singleblockTier);
        d.singleblockEfficiency = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> e : JsonTree.object(m.get("singleblockEfficiency"))
            .entrySet()) {
            final Map<String, Object> holder = new LinkedHashMap<>();
            holder.put("v", e.getValue());
            d.singleblockEfficiency.put(e.getKey(), JsonTree.ladder(holder, "v"));
        }
        d.eohStars = rows(m.get("eohStars"), PowerData::eohStar);
        return d;
    }

    // ---- planner-data.ts helpers

    /** The entry with this name, else the table's first (findFuel). */
    public static Fuel findFuel(final List<Fuel> table, final String name) {
        for (final Fuel entry : table) {
            if (entry.name.equals(name)) return entry;
        }
        return table.get(0);
    }

    /** One option per entry, keyed and labelled by name (fuelOptions). */
    public static List<PowerSetting.Option> fuelOptions(final List<Fuel> table) {
        final List<PowerSetting.Option> options = new ArrayList<>(table.size());
        for (final Fuel entry : table) options.add(new PowerSetting.Option(entry.name, entry.name));
        return options;
    }

    public static final List<String> ROTOR_SIZE_NAMES = List.of("Small", "Normal", "Large", "Huge");

    /** The rotor with this name, else the first (findRotor). */
    public Rotor findRotor(final String name) {
        for (final Rotor rotor : rotors) {
            if (rotor.name.equals(name)) return rotor;
        }
        return rotors.get(0);
    }

    /** Durability of a rotor at a size index (rotorDurability). */
    public double rotorDurability(final Rotor rotor, final int sizeIndex) {
        final double mult = sizeIndex >= 0 && sizeIndex < rotorSizes.size() ? rotorSizes.get(sizeIndex).durabilityMult
            : 1;
        return rotor.durability * mult;
    }
}
