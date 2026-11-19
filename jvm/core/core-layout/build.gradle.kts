// core-layout: the wire router (the website's, in Java: core.layout), its measures and crossing hops, Unstack and
// Arrange (layout, layout.arrange, moved from the mod). Plain Java, no dependencies.
dependencies {
    // javax.annotation.Nullable, as the mod uses it; annotations only, not shipped.
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    testCompileOnly("com.google.code.findbugs:jsr305:3.0.2")
    // Gson as Minecraft 1.7.10 ships it, to read the conformance data.
    testImplementation("com.google.code.gson:gson:2.2.4")
}

// Where the shared test data is (conformance/ at the repository root). A stand-in until the conformance stream's
// root build sets gtnh.conformance on every test task.
tasks.withType<Test>().configureEach {
    if (!systemProperties.containsKey("gtnh.conformance")) {
        systemProperty("gtnh.conformance", rootDir.parentFile.resolve("conformance").absolutePath)
    }
    inputs.dir(rootDir.parentFile.resolve("conformance/layout")).optional()
}
