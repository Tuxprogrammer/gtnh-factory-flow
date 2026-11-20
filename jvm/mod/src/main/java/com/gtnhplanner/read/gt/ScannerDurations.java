package com.gtnhplanner.read.gt;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import cpw.mods.fml.common.registry.GameRegistry;
import gregtech.api.enums.ItemList;
import gregtech.api.enums.Materials;
import gregtech.api.enums.OrePrefixes;
import gregtech.api.objects.ItemData;
import gregtech.api.util.GTOreDictUnificator;
import gregtech.api.util.GTRecipe;

/**
 * The time a Scanner really runs a scan.
 *
 * The recipes in the "gt.recipe.scanner" map are display copies. The machine
 * ({@code MTEScanner}) does not run them: its handlers in {@code ScannerHandlerLoader}
 * return the scan time as a literal ({@code GTScannerResult.duration}), and the pack's
 * rate mod (GTNHRates) divides only the display copies, in its pass over every recipe
 * map. A stock 128 tick book scan shows 2 ticks and runs 128.
 *
 * Assembly line research is the exception: the rate mod divides the research time the
 * handler reads ({@code RecipeAssemblyLine.mResearchTime}) with the scanner map's own
 * divisor, so those display copies already match the machine and are left alone
 * (they carry specialValue -201).
 *
 * A display copy is matched to its kind by what it takes in, the same way the handlers
 * tell them apart: written book, filled map, data orb copy, data stick copy, prospecting
 * data, rocket schematic, Forestry individual (takes honey), Crops NH seed, and the
 * element scan of a dust or cell (atomic mass times a constant). Every time is read from
 * the loaded mods, so a pack with no rate mod gets the values the display copy already had.
 */
final class ScannerDurations {

    static final String MAP_ID = "gt.recipe.scanner";

    private static final int RESEARCH_SPECIAL_VALUE = -201;
    private static final String HANDLERS = "gregtech.loaders.postload.ScannerHandlerLoader";
    // Crops NH adds its own seed scan to the same map and handler list.
    private static final String SEED_SCAN = "com.gtnewhorizon.cropsnh.loaders.gtrecipes.ScannerLoader";

    private final Class<?> handlers;

    private ScannerDurations(Class<?> handlers) {
        this.handlers = handlers;
    }

    static ScannerDurations load() {
        try {
            return new ScannerDurations(Class.forName(HANDLERS));
        } catch (Throwable ignored) {
            return new ScannerDurations(null);
        }
    }

    /** The machine's own time for this display recipe, or -1 when it is not a literal-time scan. */
    int durationFor(GTRecipe display) {
        if (handlers == null || display.mSpecialValue == RESEARCH_SPECIAL_VALUE) {
            return -1;
        }
        try {
            ItemStack input = firstInput(display);
            if (input == null) {
                return -1;
            }
            if (display.mFluidInputs != null && display.mFluidInputs.length > 0 && isHoney(display.mFluidInputs[0])) {
                return constant("FORESTRY_INDIVIDUAL_SCAN_DURATION");
            }
            if (input.getItem() == Items.written_book) {
                return constant("SCAN_WRITTEN_BOOK_DURATION");
            }
            if (input.getItem() == Items.filled_map) {
                return constant("SCAN_FILLED_MAP_DURATION");
            }
            if (ItemList.Tool_DataOrb.isStackEqual(input, false, true)) {
                return constant("DATA_ORB_COPY_DURATION");
            }
            if (ItemList.Tool_DataStick.isStackEqual(input, false, true)) {
                return hasSpecial(display) ? constant("DATA_STICK_COPY_DURATION")
                    : constant("SCAN_PROSPECTING_DATA_DURATION");
            }
            if (isSchematic(input)) {
                return constant("SCAN_GALACTICRAFT_SCHEMATIC_DURATION");
            }
            if (isCropSeed(input)) {
                return constant(Class.forName(SEED_SCAN), "SEED_SCAN_DURATION");
            }
            return elementDuration(input);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private int constant(String name) throws Exception {
        return constant(handlers, name);
    }

    private static int constant(Class<?> owner, String name) throws Exception {
        Field field = owner.getField(name);
        return field.getInt(null);
    }

    /** Dust or cell of a raw element: the scan takes its atomic mass times a constant. */
    private int elementDuration(ItemStack input) throws Exception {
        ItemData data = GTOreDictUnificator.getAssociation(input);
        if (data == null || data.mMaterial == null || data.mMaterial.mMaterial == null) {
            return -1;
        }
        if (data.mPrefix != OrePrefixes.dust && data.mPrefix != OrePrefixes.cell) {
            return -1;
        }
        Method method = handlers.getMethod("getElementScanResult", Materials.class);
        Object result = method.invoke(null, data.mMaterial.mMaterial);
        if (result == null) {
            return -1;
        }
        return ((Number) result.getClass()
            .getField("duration")
            .get(result)).intValue();
    }

    private static ItemStack firstInput(GTRecipe display) {
        if (display.mInputs == null) {
            return null;
        }
        for (ItemStack stack : display.mInputs) {
            if (stack != null) {
                return stack;
            }
        }
        return null;
    }

    private static boolean hasSpecial(GTRecipe display) {
        Object special = display.mSpecialItems;
        if (special instanceof ItemStack) {
            return true;
        }
        if (special instanceof ItemStack[]) {
            for (ItemStack stack : (ItemStack[]) special) {
                if (stack != null) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isHoney(FluidStack fluid) {
        FluidStack honey = Materials.Honey.getFluid(1L);
        return fluid != null && fluid.getFluid() != null && honey != null && fluid.getFluid() == honey.getFluid();
    }

    private static boolean isCropSeed(ItemStack input) {
        Item item = input.getItem();
        GameRegistry.UniqueIdentifier id = item == null ? null : GameRegistry.findUniqueIdentifierFor(item);
        return id != null && "cropsnh".equalsIgnoreCase(id.modId) && "genericSeed".equalsIgnoreCase(id.name);
    }

    /** Galacticraft, Galactic Mars and GalaxySpace rocket schematics. */
    private static boolean isSchematic(ItemStack input) {
        Item item = input.getItem();
        if (item == null) {
            return false;
        }
        GameRegistry.UniqueIdentifier id = GameRegistry.findUniqueIdentifierFor(item);
        if (id == null || id.name == null) {
            return false;
        }
        String name = id.name.toLowerCase(Locale.ROOT);
        String mod = id.modId == null ? "" : id.modId.toLowerCase(Locale.ROOT);
        return name.contains("schematic") && (mod.startsWith("galacticraft") || mod.equals("galaxyspace"));
    }
}
