// core-base: ids, order keys, quantities, number text and the seams every other core module uses.
dependencies {
    // javax.annotation.Nullable, as the mod uses it; annotations only, not shipped.
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    // Gson as Minecraft 1.7.10 ships it, to read the shared test vectors.
    testImplementation("com.google.code.gson:gson:2.2.4")
}

tasks.named<Test>("test") {
    // -PwriteVectors rewrites src/test/resources/uuid/uuid-vectors.json from java.util.UUID (UuidTest.writeVectors).
    if (providers.gradleProperty("writeVectors").isPresent) {
        systemProperty("gtnh.writeVectors", file("src/test/resources").absolutePath)
    }
}
