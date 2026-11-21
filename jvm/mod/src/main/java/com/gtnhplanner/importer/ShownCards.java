package com.gtnhplanner.importer;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.gtnhplanner.core.model.ResourceKey;
import com.gtnhplanner.core.model.Shown;
import com.gtnhplanner.data.flowchart.GsonTrees;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.importer.FfPlan.FfRecipe;
import com.gtnhplanner.importer.NodeMaker.PortInfo;

/**
 * The importer's side of display-only cards ({@code core-codec DisplayCards}): why a plan card is one, what its ports
 * hold for wiring, and the report's line for it.
 */
public final class ShownCards {

    private ShownCards() {}

    /**
     * Why the board shows a card it cannot run: switched off, no recipe in the plan, a recipe the game looks for but
     * has
     * none like, or a kind the game has no page for.
     */
    public static Shown.Reason reason(final boolean enabled, @Nullable final FfRecipe recipe) {
        if (!enabled) return Shown.Reason.OFF;
        if (recipe == null) return Shown.Reason.ABSENT;
        return looksFor(recipe) ? Shown.Reason.MISSING : Shown.Reason.KIND;
    }

    /** {@link #reason(boolean, FfRecipe)} from the plan's own spelling of the card and of its recipe. */
    public static Shown.Reason reason(final Map<String, Object> card, @Nullable final Map<String, Object> recipe) {
        final FfRecipe r = recipe == null ? null : FfPlanParser.recipe(GsonTrees.object(recipe));
        return reason(!Boolean.FALSE.equals(card.get("enabled")), r);
    }

    /**
     * Whether the game looks for such a recipe at all (importer.game.NeiRecipeIndex's routes): one NEI names, a
     * crafting or furnace page, a crop, a bee, a crusher's mob, or a GregTech map.
     */
    static boolean looksFor(final FfRecipe recipe) {
        return recipe.neiRecipe() != null || "crafting".equals(recipe.category())
            || recipe.machineType()
                .endsWith(" Crafting")
            || "furnace".equals(recipe.category())
            || "Furnace".equals(recipe.machineType())
            || recipe.isCrop()
            || recipe.isBee()
            || recipe.isEec()
            || recipe.gregtech();
    }

    /** What a display-only card's port holds, for wiring it as the plan does: the plan's own id for it. */
    static PortInfo describe(final Port<?> port) {
        final ResourceKey key = port.resource();
        final String kind = key.kind()
            .json();
        final String board = key.kind() == ResourceKey.Kind.ASPECT ? key.toString()
            : key.kind() == ResourceKey.Kind.POWER ? com.gtnhplanner.power.Energy.KEY : FfIds.toKey(kind, key.id());
        return new PortInfo(kind, board, port.getDisplayName(), List.of(key.id()));
    }

    /** The import report's line for a display-only card (after what kept it off the board as the game's own). */
    static String reportLine(final boolean solved) {
        return "shown as it is, not editable here, "
            + (solved ? "solved from the plan's numbers" : "outside the solve");
    }
}
