// core-base: ids, order keys, quantities, formatting and the seams every other core module uses.
dependencies {
    // javax.annotation.Nullable, as the mod uses it; annotations only, not shipped.
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    // Gson as Minecraft 1.7.10 ships it, to read the shared test vectors.
    testImplementation("com.google.code.gson:gson:2.2.4")
}
