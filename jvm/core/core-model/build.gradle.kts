// core-model: the plan model both hosts edit (com.gtnhplanner.data.flowchart: Graph, Node, Port, Drawer, groups, notes,
// waypoints, tasks), a card's machine settings (data.MachineConfig), and the seams to the host that holds the plans
// (core.model.PlanHost), knows the resources (core.model.ResourceRegistry) and reads the recipes
// (core.model.RecipeSource).
dependencies {
    // The solver's input and answer types the model names (ChoiceKey, BalanceMode, DrawerTypes); brings core-base.
    "api"(project(":core-solver"))
    // A power card's model (core-power), and the machine settings, effects and recipe record (core-machines, which
    // core-power brings).
    "api"(project(":core-power"))
    // javax.annotation.Nullable and Nonnull, as the mod uses them; annotations only, not shipped.
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    testCompileOnly("com.google.code.findbugs:jsr305:3.0.2")
    // Waypoints and Tasks read and write their JSON with Gson's tree API until the codec has its own (P8). Gson as
    // Minecraft 1.7.10 ships it: the game provides it to the mod, so it is not shipped from here.
    compileOnly("com.google.code.gson:gson:2.2.4")
    testImplementation("com.google.code.gson:gson:2.2.4")
}
