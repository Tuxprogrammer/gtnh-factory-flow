package com.gtnhplanner.machines;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.ExactMath;
import com.gtnhplanner.core.base.Float32Ops;
import com.gtnhplanner.core.machines.GameRates;

/**
 * What a bee makes, worked from the game's code: Forestry 4.11.39's Bee.produceStacks, Bee.getFinalChance and
 * BeekeepingLogic (the Apiary, and MagicBees' Magic Apiary, which runs on them), and GregTech 5.09.54.205's
 * MTEIndustrialApiary and GTApiaryUpgrade. Pure: the game side is machines/game/BeeCards.
 *
 * <p>
 * A product with base chance b (a fraction) comes out with Forestry's getFinalChance,
 * {@code F(b) = 2.8 (100 b)^0.52 (p + t)^0.52 s^0.37 / 100}: p the housing's production modifier, t 1 in Forestry's
 * housings and 8 in the Industrial Apiary, s the speed gene. A pure-bred bee rolls every product twice, at b for its
 * first species and at b / 2 for its second, and every specialty once at b, only while it is jubilant (its climate,
 * or its own condition; a Mega Apiary species never is anywhere else).
 *
 * <p>
 * Forestry's housings roll once a bee cycle (550 ticks), each roll at most certain. The Industrial Apiary runs a
 * queen's whole life as one operation: F(b) times the cycles she lives (her lifespan, 1.5 times over in GTNH's HARD
 * beekeeping mode), no cap, in 550 ticks a cycle divided by its acceleration; when that would end an operation in
 * under 100 ticks it stops at 100 and makes up only the whole multiples. It draws 37 EU/t times its upgrades' energy
 * and the acceleration, plus 32 x 4^(speed - 1).
 *
 * <p>
 * Settings are kept under the website's own keys (its control ids), so plans go between the two unchanged; the housing
 * under {@link #HOUSING} (the website's machineHandlerId).
 */
public final class Bees {

    private Bees() {}

    // region Keys

    public static final String SPEED = "beeSpeedGene", CLIMATE = "beeEnvironment", FRAME = "beeFrameSlot",
        AURA = "beeMagicAura", IA_SPEED = "beeIndustrialSpeed", IA_PRODUCTION = "beeIndustrialProduction",
        AL_FRAMES = "beeAlvearyFrameHousing", AL_STIMULATOR = "beeAlvearyStimulator",
        AL_SUPPORT = "beeAlvearySupport", JELLY = "beeMegaRoyalJelly";

    /** The Mega Apiary's voltage tier (a tier name, LuV and up), as the mod keeps a GregTech card's: the website's node tier. */
    public static final String VOLTAGE = "voltage";

    /** The housing: the website keeps it as the card's machineHandlerId, the mod as this setting. */
    public static final String HOUSING = "beeHousing";

    public static final int FRAME_SLOTS = 3;

    /** Every bee setting, as plans carry them. */
    public static final Set<String> SETTING_KEYS = Set.of(
        SPEED,
        CLIMATE,
        FRAME + 1,
        FRAME + 2,
        FRAME + 3,
        AURA,
        IA_SPEED,
        IA_PRODUCTION,
        AL_FRAMES,
        AL_STIMULATOR,
        AL_SUPPORT,
        JELLY,
        HOUSING);

    // endregion

    private static final String NONE_KEY = "none";

    // region The game's constants

    /** BeekeepingLogic's queen work cycle and MTEIndustrialApiary.beeCycleLength. */
    public static final int CYCLE_TICKS = 550;
    /**
     * The beekeeping mode's lifespan modifier: GTNH ships Forestry on HARD (its config/forestry/apiculture.cfg), whose
     * queens live 1.5 times as long. Its production modifier is 0, as Normal's.
     */
    public static final float MODE_LIFESPAN = 1.5f;
    /** MTEIndustrialApiary.baseEUtUsage. */
    public static final int INDUSTRIAL_EUT = 37;
    /** GTApiaryUpgrade: the Upgraded Acceleration Upgrade x256 sets production to 4 x 1.2^8 (as a float). */
    public static final float UPGRADED_PRODUCTION = 17.199268f;

    // endregion

    // region The bee

    /**
     * One product: its item (the website's id), its name, how many a roll gives, its base chance (a fraction, as the
     * species registers it), and whether it is a specialty (made only while the bee is jubilant).
     */
    public record Product(String id, String name, int stack, float chance, boolean specialty) {}

    /**
     * A species as the card farms it, pure-bred: its Forestry id, name, the speed and lifespan of its own genes (the
     * card's speed gene starts there), whether its specialties are only ever made in a Mega Apiary
     * (JubilanceMegaApiary), and its products then specialties in the species' order.
     */
    public record Species(String uid, String name, float speed, int lifespan, boolean megaOnly,
        List<Product> products) {}

    /** The website's id for a species, as its dataset slugs it: {@code factoryflow:bee_species:<slug>}. */
    public static String websiteId(final String uid) {
        return "factoryflow:bee_species:" + slug(uid);
    }

    /** The website's slug (lower case, every run of other characters a dash, none at the ends). */
    public static String slug(final String value) {
        final String s = value.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-");
        int start = 0, end = s.length();
        if (start < end && s.charAt(start) == '-') start++;
        if (end > start && s.charAt(end - 1) == '-') end--;
        return s.substring(start, end);
    }

    // endregion

    // region The card's choices

    /**
     * A housing: its key (the website's handler id), name, production modifier before frames, Forestry's t, whether it
     * takes frames, and whether it is GregTech's Industrial Apiary.
     */
    public record Housing(String key, String label, float production, float t, boolean frames, boolean industrial) {}

    public static final Housing APIARY = new Housing("apiary", "Apiary", -0.9f, 1f, true, false);
    /**
     * Forestry's Bee House (TileBeehouse): no frame slots and one modifier, BeehouseBeeModifier, production -0.75
     * (its lifespan x3 and mutation 0 reach no rate here).
     */
    public static final Housing BEE_HOUSE = new Housing("bee-house", "Bee House", -0.75f, 1f, false, false);
    /** MagicBees' TileEntityMagicApiary: -0.1, or 0.8 with a production aura. */
    public static final Housing MAGIC_APIARY = new Housing("magic-apiary", "Magic Apiary", -0.1f, 1f, true, false);
    /**
     * Forestry's Alveary: AlvearyBeeModifier changes territory only, so its production comes from its frame housings
     * and stimulators (ExtraBees' alveary parts, {@link #ALVEARY_FRAMES}, {@link #STIMULATORS}).
     */
    public static final Housing ALVEARY = new Housing("alveary", "Alveary", 0f, 1f, false, false);
    public static final Housing INDUSTRIAL = new Housing("industrial-apiary", "Industrial Apiary", 2f, 8f, false, true);
    /**
     * kubatech's Mega Apiary (MTEMegaIndustrialApiary), on the website's older scaling: a 100-tick batch of 6400 / 550
     * bee cycles at (17.2 + 6 + its voltage tiers past LuV) over the Apiary's 0.1, each tier past LuV (up to three)
     * four times the batch and the draw. Never jubilant-gated: it makes every specialty, its own included.
     */
    public static final Housing MEGA_APIARY = new Housing("mega-apiary", "Mega Apiary", 17.19926784f + 5f, 1f, false,
        false);

    /** The website's order: a card with no housing of its own runs in the first. */
    public static final List<Housing> HOUSINGS = List
        .of(APIARY, BEE_HOUSE, MAGIC_APIARY, ALVEARY, INDUSTRIAL, MEGA_APIARY);

    /** The Magic Apiary's production with an aura provider charged for production. */
    public static final float MAGIC_AURA_PRODUCTION = 0.8f;

    /** An Alveary part as the website offers it: its key, its label, its item and what it adds to production. */
    public record Part(String key, String label, @Nullable String itemId, float production) {}

    /** The Alveary's frame housings: one Proven Frame (1), one Magic Frame (2), four Proven Frames (4). */
    public static final List<Part> ALVEARY_FRAMES = List.of(
        new Part(NONE_KEY, "None", null, 0),
        new Part("proven", "1x Proven Frame", "ExtraBees:alveary@1", 1),
        new Part("magic", "1x Magic Frame", "ExtraBees:alveary@1", 2),
        new Part("four-proven", "4x Proven Frames", "ExtraBees:alveary@1", 4));
    /** The Alveary's stimulators (ExtraBees' alveary stimulator on a low or high voltage circuit). */
    public static final List<Part> STIMULATORS = List.of(
        new Part(NONE_KEY, "None", null, 0),
        new Part("low-voltage", "1x Low Voltage", "ExtraBees:alveary@4", 0.5f),
        new Part("high-voltage", "1x High Voltage", "ExtraBees:alveary@4", 1.5f),
        new Part("four-high-voltage", "4x High Voltage", "ExtraBees:alveary@4", 6));
    /** The Alveary's utility blocks: none changes a rate. */
    public static final List<Part> ALVEARY_SUPPORTS = List.of(
        new Part("plain", "Plain", "Forestry:alveary", 0),
        new Part("climate", "Climate", "Forestry:alveary@5", 0),
        new Part("lighting-rain", "Light/Rain", "ExtraBees:alveary@2", 0),
        new Part("sieve", "Sieve", "Forestry:alveary@7", 0));
    /** The Mega Apiary's royal jelly: an output multiplier of 1, 2 or 3. */
    public static final List<Part> JELLIES = List.of(
        new Part(NONE_KEY, "None", null, 1),
        new Part("partial", "Partial", "Forestry:royalJelly", 2),
        new Part("full", "Full", "Forestry:royalJelly", 3));

    /** The part under {@code key}, else the list's first. */
    public static Part part(final List<Part> parts, @Nullable final String key) {
        for (final Part p : parts) if (p.key()
            .equals(key)) return p;
        return parts.getFirst();
    }

    /** MEGA_APIARY_BATCH_CYCLES: a Mega Apiary batch is 6400 ticks of bee cycles. */
    public static final double MEGA_BATCH_CYCLES = 6400.0 / 550;
    /** The Mega Apiary's draw at LuV, and its batch. */
    public static final long MEGA_EUT = 8110;
    public static final int MEGA_TICKS = 100;
    /** The Mega Apiary's production term at LuV: the Upgraded Acceleration's 17.2 plus 6. */
    public static final double MEGA_TERM = 17.19926784 + 6;

    @Nullable
    public static Housing housing(@Nullable final String key) {
        for (final Housing h : HOUSINGS) if (h.key()
            .equals(key)) return h;
        return null;
    }

    /** A speed gene: Forestry's EnumAllele.Speed and MagicBees' Blinding. */
    public record Allele(String key, String label, float value) {}

    public static final List<Allele> SPEEDS = List.of(
        new Allele("slowest", "Slowest", 0.3f),
        new Allele("slower", "Slower", 0.6f),
        new Allele("slow", "Slow", 0.8f),
        new Allele("normal", "Normal", 1.0f),
        new Allele("fast", "Fast", 1.2f),
        new Allele("faster", "Faster", 1.4f),
        new Allele("fastest", "Fastest", 1.7f),
        new Allele("blinding", "Blinding", 2.0f));

    @Nullable
    public static Allele speed(@Nullable final String key) {
        for (final Allele a : SPEEDS) if (a.key()
            .equals(key)) return a;
        return null;
    }

    /** The allele nearest a gene's value (a species' own speed). */
    public static Allele nearestSpeed(final float value) {
        Allele best = SPEEDS.get(3);
        for (final Allele a : SPEEDS) if (Math.abs(Float32Ops.sub(a.value(), value)) < Math.abs(Float32Ops.sub(best.value(), value))) best = a;
        return best;
    }

    /** How a frame's production adds up, each against what the housing and earlier frames have made so far. */
    public enum FrameRule {
        /** Forestry's ItemHiveFrame: 1 while the sum is below 10, else 0. */
        FORESTRY,
        /** MagicBees' HiveFrameType: its own number. */
        CONSTANT,
        /** ExtraBees' BeeModifierLogic: a cut as it is; a boost no further than its ceiling. */
        CEILING
    }

    /** A frame: the website's key, its name, its item, and its production by its rule. */
    public record Frame(String key, String label, String itemId, FrameRule rule, float value, float max) {

        /** Its production modifier with {@code sum} so far (BeeHousingModifier passes the running sum). */
        public float production(final float sum) {
            return switch (rule) {
                case FORESTRY -> sum < 10.0f ? 1.0f : 0.0f;
                case CONSTANT -> value;
                case CEILING -> value < 0.0f ? value : sum >= max ? 0.0f : Math.min(value, Float32Ops.sub(max, sum));
            };
        }
    }

    public static final String NONE = NONE_KEY;

    public static final List<Frame> FRAMES = List.of(
        new Frame("forestry:untreated", "Untreated Frame", "Forestry:frameUntreated", FrameRule.FORESTRY, 1, 0),
        new Frame("forestry:impregnated", "Impregnated Frame", "Forestry:frameImpregnated", FrameRule.FORESTRY, 1, 0),
        new Frame("forestry:proven", "Proven Frame", "Forestry:frameProven", FrameRule.FORESTRY, 1, 0),
        new Frame("extrabees:cocoa", "Chocolate Frame", "ExtraBees:hiveFrame.cocoa", FrameRule.CEILING, 0.5f, 2.0f),
        new Frame("extrabees:cage", "Restraint Frame", "ExtraBees:hiveFrame.cage", FrameRule.CEILING, -0.25f, 0),
        new Frame("extrabees:soul", "Soul Frame", "ExtraBees:hiveFrame.soul", FrameRule.CEILING, -0.75f, 0),
        new Frame("extrabees:clay", "Healing Frame", "ExtraBees:hiveFrame.clay", FrameRule.CEILING, -0.25f, 0),
        new Frame("magicbees:magic", "Magic Frame", "MagicBees:frameMagic", FrameRule.CONSTANT, 2.0f, 0),
        new Frame("magicbees:resilient", "Resilient Frame", "MagicBees:frameResilient", FrameRule.CONSTANT, 2.0f, 0),
        new Frame("magicbees:gentle", "Gentle Frame", "MagicBees:frameGentle", FrameRule.CONSTANT, 0.4f, 0),
        new Frame("magicbees:metabolic", "Metabolic Frame", "MagicBees:frameMetabolic", FrameRule.CONSTANT, 0.2f, 0),
        new Frame("magicbees:necrotic", "Necrotic Frame", "MagicBees:frameNecrotic", FrameRule.CONSTANT, -0.25f, 0),
        new Frame("magicbees:temporal", "Temporal Frame", "MagicBees:frameTemporal", FrameRule.CONSTANT, 0.0f, 0),
        new Frame("magicbees:oblivion", "Oblivion Frame", "MagicBees:frameOblivion", FrameRule.CONSTANT, -9001.0f, 0));

    @Nullable
    public static Frame frame(@Nullable final String key) {
        for (final Frame f : FRAMES) if (f.key()
            .equals(key)) return f;
        return null;
    }

    /**
     * The climate, the website's knob for whether the bee works and is jubilant: a bee in the wrong climate does not
     * work; in one it only tolerates it works without its specialties.
     */
    public record Climate(String key, String label, boolean works, boolean jubilant) {}

    public static final Climate PREFERRED = new Climate("preferred", "Yes", true, true);
    public static final List<Climate> CLIMATES = List.of(
        PREFERRED,
        new Climate("tolerated", "No", true, false),
        new Climate("wrong", "Wrong climate", false, false),
        new Climate("controlled", "Yes", true, true));

    public static Climate climate(@Nullable final String key) {
        for (final Climate c : CLIMATES) if (c.key()
            .equals(key)) return c;
        return PREFERRED;
    }

    /** An Industrial Apiary acceleration upgrade (GTApiaryUpgrade speed1..8, speed8upgraded): its item's meta. */
    public record Acceleration(String key, String label, int speed, boolean upgraded, int meta) {}

    public static final List<Acceleration> ACCELERATIONS;

    static {
        final List<Acceleration> list = new ArrayList<>();
        list.add(new Acceleration(NONE, "None", 0, false, -1));
        for (int s = 1; s <= 8; s++) list.add(new Acceleration("speed-" + s, "x" + (1 << s), s, false, 32199 + s));
        list.add(new Acceleration("speed-8-upgraded", "Upgraded x256", 8, true, 32208));
        ACCELERATIONS = List.copyOf(list);
    }

    public static Acceleration acceleration(@Nullable final String key) {
        for (final Acceleration a : ACCELERATIONS) if (a.key()
            .equals(key)) return a;
        return ACCELERATIONS.getFirst();
    }

    /** GTApiaryUpgrade's production upgrade: up to 8 in a stack. */
    public static final int MAX_PRODUCTION_UPGRADES = 8;
    /** The production upgrade's GregTech meta item. */
    public static final int PRODUCTION_UPGRADE_META = 32209;

    // endregion

    // region The setup

    /**
     * What a card runs: its housing, speed gene, frames (Forestry's housings; null for an empty slot), the Magic
     * Apiary's aura, climate, and the Industrial Apiary's acceleration and production upgrades.
     */
    public record Setup(Housing housing, Allele speed, List<Frame> frames, boolean aura, Climate climate,
        Acceleration acceleration, int productionUpgrades, Part alvearyFrames, Part stimulator, Part support,
        Part jelly, int voltageTier) {}

    /** The Mega Apiary's lowest tier, LuV (ordinal 6, ULV 0). */
    public static final int MEGA_MIN_TIER = 6;

    /** getBeeMegaApiaryVoltageOffset: tiers past LuV, at most three. */
    public static int megaVoltageOffset(final int voltageTier) {
        return Math.max(0, Math.min(3, voltageTier - MEGA_MIN_TIER));
    }

    /** The card's settings, each unset one at its default: an Apiary, the species' own speed, preferred climate. */
    public static Setup setup(final Map<String, ?> settings, final Species species) {
        final Housing housing = housing(string(settings, HOUSING));
        final Allele speed = speed(string(settings, SPEED));
        final List<Frame> frames = new ArrayList<>();
        for (int i = 1; i <= FRAME_SLOTS; i++) frames.add(frame(string(settings, FRAME + i)));
        int production = 0;
        try {
            final String p = string(settings, IA_PRODUCTION);
            if (p != null) production = Math.max(0, Math.min(MAX_PRODUCTION_UPGRADES, Integer.parseInt(p.trim())));
        } catch (final NumberFormatException ignored) {}
        return new Setup(
            housing != null ? housing : APIARY,
            speed != null ? speed : nearestSpeed(species.speed()),
            frames,
            "production-aura".equals(string(settings, AURA)),
            climate(string(settings, CLIMATE)),
            acceleration(string(settings, IA_SPEED)),
            production,
            part(ALVEARY_FRAMES, string(settings, AL_FRAMES)),
            part(STIMULATORS, string(settings, AL_STIMULATOR)),
            part(ALVEARY_SUPPORTS, string(settings, AL_SUPPORT)),
            part(JELLIES, string(settings, JELLY)),
            voltageTier(string(settings, VOLTAGE)));
    }

    private static final String[] TIER_NAMES = { "ULV", "LV", "MV", "HV", "EV", "IV", "LuV", "ZPM", "UV", "UHV",
        "UEV", "UIV", "UMV", "UXV", "MAX" };

    /** A voltage tier's ordinal (ULV 0), the Mega Apiary's LuV when unset or not a tier. */
    private static int voltageTier(@Nullable final String name) {
        for (int i = 0; i < TIER_NAMES.length; i++) if (TIER_NAMES[i].equals(name)) return i;
        return MEGA_MIN_TIER;
    }

    @Nullable
    private static String string(final Map<String, ?> settings, final String key) {
        final Object v = settings.get(key);
        return v == null || "".equals(v) ? null : String.valueOf(v);
    }

    /**
     * The production modifier p, as the housing sums its modifiers: Forestry's housings their own, then each frame in
     * its slot against the sum so far (BeeHousingModifier); the Industrial Apiary 2, 4 x 1.2^n with n production
     * upgrades, or the Upgraded Acceleration's 17.2 (which wins, as it does placed after them). The beekeeping mode
     * adds
     * nothing.
     */
    public static float productionModifier(final Setup s) {
        final Housing h = s.housing();
        if (h.industrial()) {
            if (s.acceleration()
                .upgraded()) return UPGRADED_PRODUCTION;
            return s.productionUpgrades() > 0 ? single(4.0 * single(powInt(1.2, s.productionUpgrades()))) : h.production();
        }
        if (h == ALVEARY) return Float32Ops.add(
            s.alvearyFrames()
                .production(),
            s.stimulator()
                .production());
        float sum = h == MAGIC_APIARY && s.aura() ? MAGIC_AURA_PRODUCTION : h.production();
        if (h.frames()) for (final Frame f : s.frames()) if (f != null) sum = Float32Ops.add(sum, f.production(sum));
        return sum;
    }

    /** p + t, the production term the chance grows with. */
    public static float term(final Setup s) {
        return Float32Ops.add(
            productionModifier(s),
            s.housing()
                .t());
    }

    /** Forestry's Bee.getFinalChance, in the game's float arithmetic: a roll's chance (above 1 in the GT apiary). */
    public static float finalChance(float chance, final float beeSpeed, final float productionModifier, final float t) {
        final float total = Float32Ops.add(productionModifier, t);
        if (total <= 0.0F) return 0.0F;
        chance = Float32Ops.mul(chance, 100.0F);
        return Float32Ops.div(
            single(
                2.8D * ExactMath.pow(chance, 0.52D) * ExactMath.pow(total, 0.52D)
                    * ExactMath.pow(beeSpeed, 0.37D)),
            100.0F);
    }

    /**
     * A float result. On the JavaScript target a float is a double and arithmetic on floats does not narrow, so each
     * float operation rounds here, as it does on the JVM and in WebAssembly.
     */
    private static float single(final double value) {
        return Float32Ops.of(value);
    }

    /** {@code Float::sum}, rounded to a float on every target. */
    private static Float sum(final Float a, final Float b) {
        return Float32Ops.add(a, b);
    }

    /** GTUtility.powInt: by squaring, as GTApiaryUpgrade takes its powers. */
    static double powInt(double base, int exp) {
        double result = 1;
        while (exp > 0) {
            if ((exp & 1) == 1) result *= base;
            base *= base;
            exp >>= 1;
        }
        return result;
    }

    // endregion

    // region The run

    /**
     * One craft: its ticks, the expected items of each product a craft (by item id, in the species' order), its EU/t,
     * and for the Industrial Apiary the cycles a queen lives, its acceleration, what the life is divided by (the
     * acceleration, or less where that would end it in under 100 ticks) and how many times over the outputs come then.
     */
    public record Run(int ticks, Map<String, Double> perCraft, long euPerTick, float cycles, int acceleration,
        int divisor, int factor) {}

    /**
     * A craft of this setup: a bee cycle in Forestry's housings, a queen's whole life in the Industrial Apiary
     * (MTEIndustrialApiary.checkRecipe).
     */
    public static Run run(final Setup s, final Species species) {
        if (s.housing() == MEGA_APIARY) return megaRun(s, species);
        if (!s.housing()
            .industrial()) return new Run(CYCLE_TICKS, perCycle(s, species), 0, 1, 1, 1, 1);
        final float p = productionModifier(s), t = s.housing()
            .t(),
            speed = s.speed()
                .value();
        final boolean works = s.climate()
            .works();
        final boolean jubilant = jubilant(s, species);
        // The queen's life: health / (1 / (housing lifespan x mode lifespan)), the IA's own lifespan modifier 1.
        float lifespan = Float32Ops.mul(1.0F, MODE_LIFESPAN);
        lifespan = Float32Ops.div(1.0F, lifespan);
        final float cycles = Float32Ops.div(Float32Ops.of(species.lifespan()), lifespan);
        // Primary products, the second species' at half chance, then the specialties: summed per item as floats.
        final Map<String, Float> drops = new LinkedHashMap<>();
        for (final Product pr : species.products()) if (!pr.specialty())
            drops.merge(pr.id(), Float32Ops.mul(Float32Ops.mul(finalChance(pr.chance(), speed, p, t), Float32Ops.of(pr.stack())), cycles), Bees::sum);
        for (final Product pr : species.products()) if (!pr.specialty())
            drops.merge(pr.id(), Float32Ops.mul(Float32Ops.mul(finalChance(Float32Ops.div(pr.chance(), 2.0F), speed, p, t), Float32Ops.of(pr.stack())), cycles), Bees::sum);
        for (final Product pr : species.products()) if (pr.specialty()) drops
            .merge(pr.id(), jubilant ? Float32Ops.mul(Float32Ops.mul(finalChance(pr.chance(), speed, p, t), Float32Ops.of(pr.stack())), cycles) : 0f, Bees::sum);
        final int life = (int) Float32Ops.mul(cycles, CYCLE_TICKS);
        final int limit = life / 100;
        final int accel = 1 << s.acceleration()
            .speed();
        final int d = Math.max(1, Math.min(accel, limit));
        final int ticks = Math.max(1, life / d);
        final int factor = accel / d;
        final Map<String, Double> out = new LinkedHashMap<>();
        for (final Map.Entry<String, Float> e : drops.entrySet())
            out.put(e.getKey(), works ? (double) e.getValue() * factor : 0);
        return new Run(ticks, out, euPerTick(s), cycles, accel, d, factor);
    }

    /**
     * The Mega Apiary on the website's older scaling (machine-effects.ts, the bee path where the game's chance maths
     * is not used): every product and specialty line comes out at its base chance times the speed gene's s^0.37, the
     * royal jelly, ((17.2 + 6 + k) / 0.1)^0.52 and a batch of 6400 / 550 cycles x 4^k, k its voltage tiers past LuV
     * (up to three); one batch is 100 ticks at 8110 x 4^k EU/t. Climate and jubilance do not reach it.
     */
    private static Run megaRun(final Setup s, final Species species) {
        final double multiplier = megaMultiplier(s);
        final Map<String, Double> out = new LinkedHashMap<>();
        for (final Product pr : species.products())
            out.merge(pr.id(), (double) pr.stack() * pr.chance() * multiplier, Double::sum);
        return new Run(MEGA_TICKS, out, MEGA_EUT << (2 * megaVoltageOffset(s.voltageTier())), 1, 1, 1, 1);
    }

    /** The Mega Apiary's output multiplier on a base chance (the website's), for a product or a specialty alike. */
    public static double megaMultiplier(final Setup s) {
        final int k = megaVoltageOffset(s.voltageTier());
        return ExactMath.pow(
            s.speed()
                .value(),
            0.37)
            * s.jelly()
                .production()
            * ExactMath.pow((MEGA_TERM + k) / 0.1, 0.52)
            * MEGA_BATCH_CYCLES
            * ExactMath.pow(4, k);
    }

    /** Whether the bee makes its specialties: working, jubilant, and not a species only a Mega Apiary pleases. */
    public static boolean jubilant(final Setup s, final Species species) {
        if (s.housing() == MEGA_APIARY) return true;
        return s.climate()
            .works()
            && s.climate()
                .jubilant()
            && !species.megaOnly();
    }

    /**
     * The expected items of each product a bee cycle, before any acceleration (by item id, in the species' order):
     * every roll F, at most certain in Forestry's housings, where the pack's rate mod then multiplies each roll's
     * stack by its bee yield (GTNHRates' ForestryBees mixin on Bee.produceStacks, {@link GameRates}).
     */
    public static Map<String, Double> perCycle(final Setup s, final Species species) {
        final float p = productionModifier(s), t = s.housing()
            .t(),
            speed = s.speed()
                .value();
        final boolean works = s.climate()
            .works(), jubilant = jubilant(s, species),
            capped = !s.housing()
                .industrial();
        final double yield = GameRates.active()
            .beesYield();
        final Map<String, Double> out = new LinkedHashMap<>();
        for (final Product pr : species.products()) {
            double rolls = 0;
            if (works && pr.specialty()) rolls = jubilant ? roll(finalChance(pr.chance(), speed, p, t), capped) : 0;
            else if (works) rolls = roll(finalChance(pr.chance(), speed, p, t), capped)
                + roll(finalChance(Float32Ops.div(pr.chance(), 2.0F), speed, p, t), capped);
            out.merge(pr.id(), rolls * pr.stack() * (capped ? yield : 1), Double::sum);
        }
        return out;
    }

    /** A roll's expected successes: Forestry's {@code rand.nextFloat() < chance} is at most certain. */
    private static double roll(final float chance, final boolean capped) {
        return capped ? Math.min(1, chance) : chance;
    }

    /** MTEIndustrialApiary's draw: 37 x its upgrades' energy x the acceleration, plus 32 x 4^(speed - 1). */
    public static long euPerTick(final Setup s) {
        if (!s.housing()
            .industrial()) return 0;
        final int speed = s.acceleration()
            .speed();
        final int accel = 1 << speed;
        long eut = (int) Float32Ops.mul(Float32Ops.mul(INDUSTRIAL_EUT, energy(s)), Float32Ops.of(accel));
        if (accel == 2) eut += 32;
        else if (accel > 2) eut += 32L * (accel << (speed - 2));
        return eut;
    }

    /** The upgrades' energy modifier: 1.4^n for n production upgrades, x14.75 for the Upgraded Acceleration. */
    public static float energy(final Setup s) {
        float energy = 1.0f;
        if (s.productionUpgrades() > 0) energy = single(energy * powInt(1.399999976158142, s.productionUpgrades()));
        if (s.acceleration()
            .upgraded()) energy = single(energy * 14.75);
        return energy;
    }

    // endregion

    // region Formulas

    /** The card's working, one line a step, each setting's number in its colour. */
    public static List<FormulaLine> formulas(final Species species, final Setup s) {
        final List<FormulaLine> lines = new ArrayList<>();
        final Housing h = s.housing();
        if (h == MEGA_APIARY) return megaFormulas(species, s);
        final float p = productionModifier(s);
        final List<Object> terms = new ArrayList<>();
        if (h == ALVEARY) {
            terms.add("0 + ");
            terms.add(
                FormulaLine.knob(
                    num(
                        s.alvearyFrames()
                            .production()),
                    AL_FRAMES));
            terms.add(" + ");
            terms.add(
                FormulaLine.knob(
                    num(
                        s.stimulator()
                            .production()),
                    AL_STIMULATOR));
        } else if (h.industrial()) {
            if (s.acceleration()
                .upgraded()) terms.add(FormulaLine.knob("17.2", IA_SPEED));
            else if (s.productionUpgrades() > 0) {
                terms.add("4·1.2");
                terms.add(new FormulaLine.Term(String.valueOf(s.productionUpgrades()), IA_PRODUCTION, true));
            } else terms.add("2");
        } else {
            terms.add(
                FormulaLine.knob(
                    num(h == MAGIC_APIARY && s.aura() ? MAGIC_AURA_PRODUCTION : h.production()),
                    h == MAGIC_APIARY ? AURA : HOUSING));
            float sum = h == MAGIC_APIARY && s.aura() ? MAGIC_AURA_PRODUCTION : h.production();
            if (h.frames()) for (int i = 0; i < s.frames()
                .size(); i++) {
                final Frame f = s.frames()
                    .get(i);
                if (f == null) continue;
                final float add = f.production(sum);
                sum = Float32Ops.add(sum, add);
                terms.add(add < 0 ? " − " : " + ");
                terms.add(FormulaLine.knob(num(Math.abs(add)), FRAME + (i + 1)));
            }
        }
        terms.add(" + " + num(h.t()));
        lines.add(FormulaLine.of("p + t", num(Float32Ops.add(p, h.t())), terms.toArray()));
        lines.add(
            FormulaLine.of(
                "speed",
                "x" + FormulaLine.number(
                    ExactMath.pow(
                        s.speed()
                            .value(),
                        0.37)),
                "s",
                FormulaLine.sup("0.37"),
                ", s = ",
                FormulaLine.knob(
                    num(
                        s.speed()
                            .value()),
                    SPEED)));
        lines.add(
            FormulaLine.of(
                "roll",
                "F(b)",
                "2.8·(100b)",
                FormulaLine.sup("0.52"),
                "·" + num(Float32Ops.add(p, h.t())),
                FormulaLine.sup("0.52"),
                "·s",
                FormulaLine.sup("0.37"),
                " / 100"));
        final Run run = run(s, species);
        final boolean works = s.climate()
            .works(), jubilant = jubilant(s, species);
        final Map<String, Double> cycle = perCycle(s, species);
        final Map<String, String> names = new LinkedHashMap<>();
        for (final Product pr : species.products()) names.putIfAbsent(pr.id(), pr.name());
        for (final Map.Entry<String, String> e : names.entrySet()) {
            // The product named in the working, where it has room: a label is a word or two.
            final List<Object> math = new ArrayList<>();
            math.add(e.getValue() + ": ");
            boolean special = true;
            for (final Product pr : species.products()) {
                if (!pr.id()
                    .equals(e.getKey())) continue;
                special &= pr.specialty();
                if (math.size() > 1) math.add(" + ");
                final String stack = pr.stack() > 1 ? pr.stack() + "·" : "";
                if (pr.specialty()) {
                    math.add(stack + "F(" + pct(pr.chance()) + ")");
                    if (!jubilant) math.add(FormulaLine.knob(" not jubilant", CLIMATE));
                } else math.add(stack + "F(" + pct(pr.chance()) + ") + " + stack + "F(" + pct(Float32Ops.div(pr.chance(), 2f)) + ")");
            }
            lines.add(
                FormulaLine.of(
                    special ? "special" : "product",
                    FormulaLine.number(cycle.getOrDefault(e.getKey(), 0d)) + " a cycle",
                    math.toArray()));
        }
        if (!works) lines.add(FormulaLine.of("climate", "makes nothing", FormulaLine.Tone.BAD, "wrong climate"));
        else if (species.megaOnly() && species.products()
            .stream()
            .anyMatch(Product::specialty))
            lines.add(FormulaLine.of("special", "not made", FormulaLine.Tone.BAD, "only in a Mega Apiary"));
        if (h.industrial()) {
            lines.add(
                FormulaLine.of(
                    "life",
                    FormulaLine.number(run.cycles()) + " cycles",
                    species.lifespan() + "·" + num(MODE_LIFESPAN)));
            final int accel = run.acceleration();
            lines.add(
                FormulaLine.of(
                    "time",
                    FormulaLine.number(run.ticks() / 20.0) + " s",
                    FormulaLine.number(run.cycles()) + "·550 / ",
                    FormulaLine.knob(String.valueOf(run.divisor()), IA_SPEED)));
            // Her life too short for the whole acceleration: the operation stops at 100 ticks, its outputs times over.
            if (run.divisor() < accel) lines.add(
                FormulaLine.of(
                    "short life",
                    "x" + run.factor() + " outputs",
                    FormulaLine.Tone.BAD,
                    accel + " / " + run.divisor()));
            final int speed = s.acceleration()
                .speed();
            final List<Object> power = new ArrayList<>();
            power.add("37·");
            if (energy(s) != 1f) power.add(FormulaLine.knob(num(energy(s)) + "·", IA_PRODUCTION));
            power.add(FormulaLine.knob(String.valueOf(accel), IA_SPEED));
            if (speed > 0) {
                power.add(" + 32·4");
                power.add(FormulaLine.sup(String.valueOf(speed - 1)));
            }
            lines.add(FormulaLine.of("power", FormulaLine.number(run.euPerTick()) + " EU/t", power.toArray()));
        }
        return lines;
    }

    /** The Mega Apiary's working: its batch and what multiplies a base chance. */
    private static List<FormulaLine> megaFormulas(final Species species, final Setup s) {
        final List<FormulaLine> lines = new ArrayList<>();
        final int k = megaVoltageOffset(s.voltageTier());
        lines.add(
            FormulaLine.of(
                "p + t",
                num(MEGA_TERM + k),
                "17.2 + 6 + ",
                FormulaLine.knob(String.valueOf(k), VOLTAGE)));
        lines.add(
            FormulaLine.of(
                "batch",
                FormulaLine.number(MEGA_BATCH_CYCLES * ExactMath.pow(4, k)) + " cycles",
                "6400 / 550 · 4",
                FormulaLine.sup(String.valueOf(k))));
        lines.add(
            FormulaLine.of(
                "multiplier",
                "x" + FormulaLine.number(megaMultiplier(s)),
                FormulaLine.knob(
                    num(
                        s.speed()
                            .value()),
                    SPEED),
                FormulaLine.sup("0.37"),
                " · ",
                FormulaLine.knob(
                    num(
                        s.jelly()
                            .production()),
                    JELLY),
                " · (p + t / 0.1)",
                FormulaLine.sup("0.52"),
                " · batch"));
        final Run run = run(s, species);
        for (final Map.Entry<String, Double> e : run.perCraft()
            .entrySet()) {
            String name = e.getKey();
            for (final Product pr : species.products()) if (pr.id()
                .equals(e.getKey())) name = pr.name();
            lines.add(FormulaLine.of("product", FormulaLine.number(e.getValue()) + " a batch", name));
        }
        lines.add(FormulaLine.of("power", FormulaLine.number(run.euPerTick()) + " EU/t", "8110·4", FormulaLine.sup(String.valueOf(k))));
        return lines;
    }

    private static String num(final double v) {
        return FormulaLine.number(v);
    }

    private static String pct(final float chance) {
        return FormulaLine.number(chance * 100.0) + "%";
    }

    // endregion
}
