package com.gtnhplanner.core.model.read;

import java.util.ArrayList;
import java.util.List;

/**
 * One GregTech recipe-map recipe as the game holds it, read once by the game-side reader (the mod's
 * {@code com.gtnhplanner.read.gt.GtRecipeReader}) for both hosts: the export run writes it into the raw export the
 * dataset is normalized from, and the mod builds a card's ports and its dataset record ({@link GtDatasetRecipe}) from
 * it. Every field says what the game does, not what a host shows: the time is the one the machine runs, a slot the
 * recipe keeps is marked so, a chance is GregTech's own ten-thousandths.
 *
 * <p>
 * The export run compiles exactly this file with Java 8's library (Jabel), so it uses nothing newer and carries no
 * annotations. Fields that may be absent are boxed and null when absent.
 */
public final class GtRecipeRecord {

    /** The fusion reactor's map: its recipes always carry {@link #fusionStartupEu}. */
    public static final String FUSION_MAP = "gt.recipe.fusionreactor";

    /** The map's unlocalized name ({@code gt.recipe.blastfurnace}). */
    public String mapId;
    /** The map's NEI name in the game's language; its id when it has none. */
    public String mapName;
    /** Amps of its tier the map's single blocks draw (the Thermal Centrifuge 2, the Mass Fabricator 8); at least 1. */
    public int amperage = 1;
    /**
     * Ticks the machine runs the recipe, at least 1. The Rock Breaker and the Scanner run their own lists, not the
     * display copies in their maps; every other map runs the recipe's own time.
     */
    public int durationTicks;
    /** EU/t as the recipe declares it (a generator map's fuel declares its output here, or nothing). */
    public long eut;
    /** GregTech's special value: a blast furnace's heat, a fusion tier, a research marker. */
    public int specialValue;
    /** EU a fusion reactor needs stored to start the recipe: always on the fusion map, elsewhere when set. */
    public Long fusionStartupEu;
    /** Metadata the hosts' machine models read; each 0 (glass tier 3, exact false) when the recipe sets none. */
    public int coilHeat;
    public int glassTier = 3;
    public int sievert;
    public boolean sievertExact;
    public int mass;

    /** Item inputs in slot order, empty slots left out; a slot the recipe keeps is {@code consumed = false}. */
    public final List<Stack> itemInputs = new ArrayList<Stack>();
    /** Fluid inputs in slot order, empty slots left out. */
    public final List<Stack> fluidInputs = new ArrayList<Stack>();
    /** Item outputs in slot order, empty slots left out. */
    public final List<Stack> itemOutputs = new ArrayList<Stack>();
    /** Fluid outputs in slot order, empty slots left out. */
    public final List<Stack> fluidOutputs = new ArrayList<Stack>();
    /** What the special slot must hold and keeps (a data stick, a lens): every one {@code consumed = false}. */
    public final List<Stack> nonConsumedInputs = new ArrayList<Stack>();

    /** The Sparge Tower's rolled byproducts (every fluid output with its gas), set on that map only. */
    public List<Stack> spargeFluidOutputs;
    public Integer spargeGasAmount;
    public Integer spargeMaxByproduct;
    public String spargeError;

    /** Inputs a machine wears down rather than eats (chemical plant catalysts, IsaMill balls); null when none. */
    public List<Wearing> wearingInputs;
    public String wearError;

    /** The game's overclock ladder for the recipe; never null once read. */
    public Runtime runtime;

    /** One slot: an item or a fluid with its amount and what the recipe does with it. */
    public static final class Stack {

        /** {@code item} or {@code fluid}. */
        public String kind;
        /** The planner's id: lowercase registry name with {@code @meta} when not 0, or the fluid's name. */
        public String id;
        /** An item's registry name as the game spells it; null for a fluid. */
        public String registryId;
        public int meta;
        /** Items per craft, or litres. */
        public int amount;
        public String displayName;
        /** The registry name's domain; null for a fluid. */
        public String modId;
        /** The item's NBT as the game prints it; null when it has none. */
        public String nbt;
        /** FALSE when the slot must hold this and keeps it (a programmed circuit, a mold); null when it is used up. */
        public Boolean consumed;
        /** An input used up only this often, out of 10000, when between 0 and 10000. */
        public Integer inputChance;
        /** An output made only this often (a fraction, 0 never), when less than certain. */
        public Double chance;
        /** What else this exact slot accepts, itself first; null when it accepts only itself. */
        public List<Stack> alternatives;
        /** The game's stack this was read from (an ItemStack or FluidStack): ports and icons need it. Never saved. */
        public transient Object host;

        public boolean isItem() {
            return "item".equals(kind);
        }

        public boolean isFluid() {
            return "fluid".equals(kind);
        }
    }

    /** An input the machine wears down: its item id, how many uses it lasts, and what it leaves when spent. */
    public static final class Wearing {

        public String id;
        /** The durability as the game reports it (a whole number). */
        public Object durability;
        public Stack spent;
    }

    /** The recipe at each voltage tier from its own up, as GregTech's OverclockCalculator runs it. */
    public static final class Runtime {

        /** {@code computed}, or {@code missing} when the calculator gave no variant. */
        public String status = "computed";
        public boolean oracleEligible = true;
        public boolean strict = true;
        /** {@code standard}, {@code blast-furnace-heat}, {@code pyrolyse-coil}, {@code oil-cracker-coil} or
         * {@code large-chemical-reactor-perfect}. */
        public String profile = "standard";
        public final List<Variant> variants = new ArrayList<Variant>();
        /** Why there is no variant; null when there are some. */
        public List<String> warnings;
    }

    /** One rung: the tier (0 ULV .. 14 MAX), the time and draw there, and the coil it assumes where one matters. */
    public static final class Variant {

        public int tier;
        public int durationTicks;
        public long eut;
        /** Null for the standard ladder; else {@code ebf-heat}, {@code pyrolyse-coil}, {@code oil-cracker-coil},
         * {@code perfect-oc}. */
        public String profile;
        /** The coil's key ({@code cupronickel} ... {@code eternal}) for a coil profile; else null. */
        public String configKey;
    }

    /** The voltage tiers' names, index = tier: GregTech's own (GTValues.VN). */
    public static final String[] TIER_NAMES = { "ULV", "LV", "MV", "HV", "EV", "IV", "LuV", "ZPM", "UV", "UHV", "UEV",
        "UIV", "UMV", "UXV", "MAX" };

    /** The tiers' voltages, MAX as Long.MAX_VALUE, as the ladder runs them. */
    public static final long[] TIER_VOLTAGES = { 8L, 32L, 128L, 512L, 2048L, 8192L, 32768L, 131072L, 524288L,
        2097152L, 8388608L, 33554432L, 134217728L, 536870912L, Long.MAX_VALUE };

    /** The first tier whose voltage carries |eut|; MAX past UXV. */
    public static int tierForEu(final long eut) {
        final long value = Math.max(0L, Math.abs(eut));
        for (int tier = 0; tier < TIER_VOLTAGES.length; tier++) if (value <= TIER_VOLTAGES[tier]) return tier;
        return TIER_VOLTAGES.length - 1;
    }

    /** The heating coils the coil ladders step through: key, name, the heat it gives, its tier. */
    public static final String[] COIL_KEYS = { "cupronickel", "kanthal", "nichrome", "tpv", "hss_g", "hss_s", "naquadah",
        "naquadah_alloy", "trinium", "electrum_flux", "awakened_draconium", "infinity", "hypogen", "eternal" };
    public static final String[] COIL_NAMES = { "Cupronickel", "Kanthal", "Nichrome", "TPV-Alloy", "HSS-G", "HSS-S",
        "Naquadah", "Naquadah Alloy", "Trinium", "Electrum Flux", "Awakened Draconium", "Infinity", "Hypogen",
        "Eternal" };
    public static final int[] COIL_HEATS = { 1801, 2701, 3601, 4501, 5401, 6301, 7201, 8101, 9001, 9901, 10801, 11701,
        12601, 13501 };
}
