// core-codec: plan JSON as text and back (PlanJson), JSON in one spelling and its hash (Canon, Sha256), and the save's
// JSON of the plan model's notes, groups, board groups and drawers without reflection (data.flowchart.SaveCodec).
dependencies {
    "api"(project(":core-model"))
    // javax.annotation.Nullable, as the mod uses it; annotations only, not shipped.
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    testCompileOnly("com.google.code.findbugs:jsr305:3.0.2")
    // core-model's Waypoints and Tasks still read and write Gson trees (compileOnly there): the tests run them.
    testImplementation("com.google.code.gson:gson:2.2.4")
}
