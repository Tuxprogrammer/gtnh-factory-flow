package com.gtnhplanner.importer;

/**
 * The importer's front door, without the game: pasted text in, a GTNH Planner graph and a report out. The game's own
 * entry
 * points, which bring NEI's recipes and add the result as a plan slot, are in {@code importer.game.FactoryFlowImport}.
 */
public final class FfImport {

    private FfImport() {}

    /**
     * The plan in pasted text: Factory Flow's JSON download, a plan code ({@code gtnh1....}) or a plan link
     * ({@code https://<site>/#p=gtnh1....}).
     *
     * @throws FfImportException when the text is none of these; the message is for the player
     */
    public static FfPlan read(final String text) {
        return FfPlanParser.parse(FfPlanCode.toJson(text));
    }

    /**
     * Reads and converts a plan against the given recipes and node maker.
     *
     * @throws FfImportException when the text is not a plan
     */
    public static FfConverter.Result importFromText(final String text, final RecipeIndex index, final NodeMaker maker) {
        return importFromText(text, index, maker, null);
    }

    /**
     * Reads and converts a plan, its setting pins read onto the board with the export's names for its machines
     * ({@code world}, as a shared plan reads them: cloud/map/PlanPins). With no {@code world} the pins only ride along
     * in what the board keeps for the plan.
     *
     * @throws FfImportException when the text is not a plan
     */
    public static FfConverter.Result importFromText(final String text, final RecipeIndex index, final NodeMaker maker,
        @javax.annotation.Nullable final com.gtnhplanner.library.PlanExport.World world) {
        // Items the game renamed since the plan was saved are read under their new ids.
        final com.google.gson.JsonObject root = PlanRenames.apply(FfPlanParser.root(FfPlanCode.toJson(text)), index);
        final java.util.Map<String, Object> tree = com.gtnhplanner.data.flowchart.GsonTrees.map(root);
        // With the plan's tree: a card the board cannot run comes in as a display-only card.
        final FfConverter.Result result = FfConverter.convert(FfPlanParser.read(root), index, maker, tree);
        // The rate unit it was shared in: the board opens it in that (the website's applyPlanView).
        result.graph()
            .viewWhenShown(PlanView.rateUnit(root));
        // Its description, which the board shows on its face.
        if (root.has("description") && root.get("description")
            .isJsonPrimitive())
            result.graph()
                .setDescription(
                    root.get("description")
                        .getAsString());
        // Its task board, as the plan spells it.
        com.gtnhplanner.data.flowchart.Tasks.read(root, result.graph());
        // What the board has no place for, kept to go back out with the plan (core-codec SiteCarry).
        final java.util.Map<String, Object> site = com.gtnhplanner.core.codec.SiteCarry.carry(tree, result.placed());
        if (world != null) {
            // Its setting pins on the board, read as a shared plan's are: the board writes them all back (a machine
            // with no card here as site:<key>), so the plan's own copy is not kept beside them.
            com.gtnhplanner.cloud.map.PlanPins
                .readPins(root.get("settingPins"), java.util.Map.of(), result.graph(), world);
            if (site.get("plan") instanceof final java.util.Map<?, ?> own) {
                own.remove("settingPins");
                if (own.isEmpty()) site.remove("plan");
            }
        }
        result.graph().site.putAll(site);
        return result;
    }
}
