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
     * ({@code https://gtnhplanner.com/#p=gtnh1....}).
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
        final com.google.gson.JsonObject root = FfPlanParser.root(FfPlanCode.toJson(text));
        final FfConverter.Result result = FfConverter.convert(FfPlanParser.read(root), index, maker);
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
        result.graph().site.putAll(
            com.gtnhplanner.core.codec.SiteCarry
                .carry(com.gtnhplanner.data.flowchart.GsonTrees.map(root), result.placed()));
        return result;
    }
}
