// core-machines: the machine math (overclocks, parallels, hatches, the curated machine table, the special machines)
// and the tables it reads (handlers.json, fusion-startups.json), as the website computes them.
dependencies {
    "api"(project(":core-base"))
    // javax.annotation.Nullable, as the mod uses it; annotations only, not shipped.
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    // Gson as Minecraft 1.7.10 ships it; the game provides it at run time, so it is never shaded.
    compileOnly("com.google.code.gson:gson:2.2.4")
    testImplementation("com.google.code.gson:gson:2.2.4")
    testCompileOnly("com.google.code.findbugs:jsr305:3.0.2")
}

// The machine, bee and crop goldens and the reference coefficients still sit in the mod's test resources until the
// conformance tree takes them; only those files are put on this module's test classpath.
sourceSets.named("test") {
    resources {
        srcDir("../../mod/src/test/resources")
        include(
            "eec/**",
            "machine-goldens.jsonl.gz",
            "bee-goldens.jsonl.gz",
            "crop-goldens.jsonl.gz",
            "machines/reference-coefficients.json",
        )
    }
}
