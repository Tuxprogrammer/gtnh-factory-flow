// core-view: what both hosts draw: the shared tokens (colours, tones, mixes, tiers, geometry) and the view records
// that follow them. Tokens.java is generated from tokens/tokens.json at build time and is not committed.
extra["tokensPackage"] = "com.gtnhplanner.core.view"
apply(from = rootProject.file("gradle/tokens.gradle.kts"))
// The settings schema and the sound rule table (settings/*.json) are generated into the same package.
extra["settingsPackage"] = "com.gtnhplanner.core.view"
apply(from = rootProject.file("gradle/settings.gradle.kts"))

dependencies {
    // Gson as Minecraft 1.7.10 ships it, to read tokens.json in the test.
    testImplementation("com.google.code.gson:gson:2.2.4")
}
