// core-view: what both hosts draw: the shared tokens (colours, tones, mixes, tiers, geometry) and the view records
// that follow them. Tokens.java is generated from tokens/tokens.json at build time and is not committed.
extra["tokensPackage"] = "com.gtnhplanner.core.view"
apply(from = rootProject.file("gradle/tokens.gradle.kts"))
// The settings schema and the sound rule table (settings/*.json) are generated into the same package.
extra["settingsPackage"] = "com.gtnhplanner.core.view"
apply(from = rootProject.file("gradle/settings.gradle.kts"))

dependencies {
    // The card logic reads power sources and models (core-power), the machine maths' result (core-machines) and the
    // core's id type (core-base); core-power brings the other two.
    "api"(project(":core-power"))
    // javax.annotation.Nullable, as the mod uses it; annotations only, not shipped. The tests read it by reflection
    // (the view record conventions), so it is on their runtime path.
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    testImplementation("com.google.code.findbugs:jsr305:3.0.2")
    // Gson as Minecraft 1.7.10 ships it, to read tokens.json in the test.
    testImplementation("com.google.code.gson:gson:2.2.4")
}

tasks.named<Test>("test") {
    // -PwriteVectors rewrites conformance/format/mod-out.json from Fmt (FmtConformanceTest.writeVectors).
    if (providers.gradleProperty("writeVectors").isPresent) {
        systemProperty("gtnh.writeVectors", "true")
    }
}
