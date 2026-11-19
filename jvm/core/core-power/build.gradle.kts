// core-power: the power sources (generator maths per family) and the workbook, fuel and flow-name tables they read.
dependencies {
    // Nothing here calls the machine math yet; the dependency keeps the module order (core-plan-power needs both).
    "api"(project(":core-machines"))
    // javax.annotation.Nullable, as the mod uses it; annotations only, not shipped.
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    // Gson as Minecraft 1.7.10 ships it; the game provides it at run time, so it is never shaded.
    compileOnly("com.google.code.gson:gson:2.2.4")
    testImplementation("com.google.code.gson:gson:2.2.4")
    testCompileOnly("com.google.code.findbugs:jsr305:3.0.2")
}
