package com.gtnhplanner.web.exports;

import javax.annotation.Nullable;

import org.teavm.jso.JSExport;

import com.gtnhplanner.core.machines.GameRates;
import com.gtnhplanner.machines.BeeReadout;
import com.gtnhplanner.machines.CropReadout;
import com.gtnhplanner.machines.web.MachineReadout;

/**
 * core-machines for JavaScript: the machine table's maths over a recipe on a node, a bee card and a crop card, each as
 * JSON in and JSON out in the shapes conformance/machines records (machine-goldens, bee-goldens, crop-goldens under
 * "expected"). A recipe crosses once ({@code machinesAddRecipe}, the website's recipe JSON) and later questions name it,
 * so a recipe's tables are not parsed again for each node. The handler table and the fusion start-up table the maths
 * read are inside the module (web/build/MachinesResources), so there is nothing to load first.
 */
public final class MachinesExports {

    private MachinesExports() {}

    private static final MachineReadout MACHINES = new MachineReadout();

    /** The pack's rate-mod factors (GTNHRates), each 1 without the mod; a factor that is not a positive number reads as 1. */
    @JSExport
    public static void machinesSetRates(final double beesYield, final double ic2CropsYield,
        final double recipeDurationDivisor) {
        GameRates.install(new GameRates(beesYield, ic2CropsYield, recipeDurationDivisor));
    }

    /**
     * Keeps a control table (one entry of a recipe's or handler's machineConfigControls, the website's JSON) under a name
     * the host picks; a recipe sent later may write {"$ref": name} in its place.
     */
    @JSExport
    public static void machinesAddControl(final String ref, final String controlJson) {
        MACHINES.addControl(ref, controlJson);
    }

    /**
     * Runs the card maths on a recipe of each of the first {@code maps} recipe maps, so the tables are read and the code
     * is warm before a plan's cards ask (a host calls it in idle time after loading); answers the cards computed.
     */
    @JSExport
    public static int machinesWarm(final int maps) {
        return MachineReadout.warm(maps);
    }

    /**
     * Keeps a recipe (the website's JSON shape) under its id; a second recipe under the same id replaces it. A control
     * written {"$ref": name} is the table machinesAddControl kept under that name.
     */
    @JSExport
    public static void machinesAddRecipe(final String recipeId, final String recipeJson) {
        MACHINES.addRecipe(recipeId, recipeJson);
    }

    /**
     * The card's numbers for a kept recipe on a node (JSON in the website's node shape), as JSON; null for a machine the
     * module leaves to the mod's own models (Tree Growth Simulator, Bacterial Vat).
     */
    @JSExport
    @Nullable
    public static String machinesReadout(final String recipeId, final String nodeJson) {
        return MACHINES.read(recipeId, nodeJson);
    }

    /**
     * A board card's machine maths for a kept recipe on a node (JSON in the website's node shape), as JSON in the
     * website's own shapes: {"stats", "power"?, "steam"?, "parallel", "structuralParallel", "outputMultipliers"}; null for
     * a machine the module leaves to the mod's own models (Tree Growth Simulator, Bacterial Vat) or a bee or crop card.
     */
    @JSExport
    @Nullable
    public static String machinesCard(final String recipeId, final String nodeJson) {
        return MACHINES.card(recipeId, nodeJson);
    }

    /** A bee card: {"housing","tiers","tier","beesYield"} in, the housing and its multipliers out. */
    @JSExport
    public static String machinesBeeReadout(final String caseJson) {
        return BeeReadout.read(caseJson);
    }

    /** A crop card: {"stats","handler","tiers","crops","gameRates"} in, every number the card shows out. */
    @JSExport
    public static String machinesCropReadout(final String caseJson) {
        return CropReadout.read(caseJson);
    }
}
