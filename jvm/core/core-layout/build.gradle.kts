// core-layout: the wire router and the live-drag rule (the website's, in Java: core.layout), the Arrange (the website's,
// in Java: core.layout.arrange), and the mod's measures, crossing hops and Unstack (layout, moved from the mod). Plain
// Java, no dependencies.
dependencies {
    // javax.annotation.Nullable, as the mod uses it; annotations only, not shipped.
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    testCompileOnly("com.google.code.findbugs:jsr305:3.0.2")
    // Gson as Minecraft 1.7.10 ships it, to read the conformance data.
    testImplementation("com.google.code.gson:gson:2.2.4")
}

// The router bench (tag bench) runs only when asked: -Pbench --tests '*RouterBench*' [-Pruns=N] [-Pboard=<name part>]
// [-Pjfr] (a flight recording in build/bench/profile.jfr).
tasks.withType<Test>().configureEach {
    // LiveDragTest holds the live-drag numbers to the website's copy until the website calls this one.
    inputs.file(rootProject.file("../src/components/flow/FactoryFlow.tsx")).withPropertyName("websiteBoard")
    useJUnitPlatform { if (!project.hasProperty("bench")) excludeTags("bench") }
    if (project.hasProperty("bench")) outputs.upToDateWhen { false }
    project.findProperty("runs")?.let { systemProperty("gtnh.bench.runs", it) }
    project.findProperty("board")?.let { systemProperty("gtnh.bench.board", it) }
    project.findProperty("drag")?.let { systemProperty("gtnh.bench.drag", it) }
    project.findProperty("passes")?.let { systemProperty("gtnh.bench.passes", it) }
    if (project.hasProperty("jfr")) jvmArgs(
        "-XX:FlightRecorderOptions:stackdepth=256",
        "-XX:StartFlightRecording=filename=build/bench/profile.jfr,settings=profile",
    )
}

// The router's grid and lane constants come from the one tokens file (core-view/tokens/tokens.json), generated into
// com.gtnhplanner.core.layout.Tokens. Only the router's own groups: card and drawer sizes are the host's, carried in
// each board's boxes, since the two hosts' card geometry differs on purpose.
extra["tokensPackage"] = "com.gtnhplanner.core.layout"
extra["tokensGroups"] = listOf("grid", "wire")
apply(from = rootProject.file("gradle/tokens.gradle.kts"))
