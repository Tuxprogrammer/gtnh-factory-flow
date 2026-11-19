// core-layout: the wire router (the website's, in Java: core.layout), its measures and crossing hops, Unstack and
// Arrange (layout, layout.arrange, moved from the mod). Plain Java, no dependencies.
dependencies {
    // javax.annotation.Nullable, as the mod uses it; annotations only, not shipped.
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    testCompileOnly("com.google.code.findbugs:jsr305:3.0.2")
    // Gson as Minecraft 1.7.10 ships it, to read the conformance data.
    testImplementation("com.google.code.gson:gson:2.2.4")
}
