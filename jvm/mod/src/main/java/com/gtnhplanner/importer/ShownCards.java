package com.gtnhplanner.importer;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.core.codec.DisplayCards;
import com.gtnhplanner.core.model.ResourceKey;
import com.gtnhplanner.core.model.Shown;
import com.gtnhplanner.data.flowchart.GsonTrees;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.importer.FfPlan.FfNode;
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

    /**
     * The board's display-only card for a plan card the game cannot run, at a board place: its name, ports and counts
     * as
     * the plan states them, and its port-rate pins where the solve counts it. One maker for every way a plan comes in
     * (a
     * file, the cloud), so a card is the same card whichever way it arrived. {@code own} is the plan's card as a tree,
     * {@code recipes} the plan's recipes (at least the one the card runs); {@code mark} is the report's size before the
     * card was tried as one of the game's, so what said it was left out now says it is shown.
     */
    public static Node show(final Map<String, Object> own, @Nullable final Object recipes, final FfNode card,
        @Nullable final FfRecipe recipe, final Uuid id, final int x, final int y, final FfCards.Ports ports,
        final ImportReport report, final int mark) {
        final Node node = DisplayCards
            .card(own, DisplayCards.recipeOf(own, recipes), reason(card.enabled(), recipe), id, x, y);
        final String name = recipe == null ? card.recipeId() : recipe.name();
        // Its rates a second as the plan pins them, on a card the solve runs.
        if (node.shown.solved()) FfCards.portRates(ports, node, recipe, card.overrides(), card, 0, name, report);
        report.shown(mark, name, reportLine(node.shown.solved()));
        return node;
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
