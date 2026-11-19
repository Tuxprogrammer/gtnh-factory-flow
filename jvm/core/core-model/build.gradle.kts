// core-model: the plan model both hosts edit (com.gtnhplanner.data.flowchart, moved from the mod a class at a time as
// each comes free of the game) and the seam to the host that holds the plans (core.model.PlanHost). The plan's cards,
// ports and graph follow once resources and recipes have seams of their own.
dependencies {
    // The solver's input and answer types the model names (ChoiceKey, BalanceMode, DrawerTypes); brings core-base.
    "api"(project(":core-solver"))
    // javax.annotation.Nullable and Nonnull, as the mod uses them; annotations only, not shipped.
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    testCompileOnly("com.google.code.findbugs:jsr305:3.0.2")
}
