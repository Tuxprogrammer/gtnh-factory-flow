// core-solver: the solver kernel (balancer, its stages and alternatives), the records a solve reads and answers with,
// and the plan JSON reader and writer for those records. ojAlgo is the LP engine.
dependencies {
    "api"(project(":core-base"))
    "api"("org.ojalgo:ojalgo:57.4.0")
    // javax.annotation.Nullable, as the mod uses it; annotations only, not shipped.
    "compileOnly"("com.google.code.findbugs:jsr305:3.0.2")
    // SolveInputJson uses Gson's tree API only. Gson as Minecraft 1.7.10 ships it: the game provides it to the mod, so
    // it is not shipped from here (a host without it supplies its own).
    "compileOnly"("com.google.code.gson:gson:2.2.4")
    "testImplementation"("com.google.code.gson:gson:2.2.4")
    "testCompileOnly"("com.google.code.findbugs:jsr305:3.0.2")
    // The test sources' LauncherSessionListener (solver/TestThreads) lends the solver its worker threads.
    "testCompileOnly"("org.junit.platform:junit-platform-launcher:1.10.2")
}

tasks.named<Test>("test") {
    maxHeapSize = "4g"
    // Timings and digest dumps (tag bench) run only when asked: -Pbench=<label> --tests '*SolverBench*'.
    val bench = providers.gradleProperty("bench")
    useJUnitPlatform { if (!bench.isPresent) excludeTags("bench") }
    systemProperty("gtnhplanner.bench", bench.getOrElse(""))
    for (k in listOf("benchOnly" to "gtnhplanner.bench.only", "runs" to "gtnhplanner.bench.runs")) {
        providers.gradleProperty(k.first).orNull?.let { systemProperty(k.second, it) }
    }
    // -PoneThread solves with no worker threads (the core's default, as the web build solves); otherwise the tests lend
    // the solver a ForkJoinPool as the mod does (solver/TestThreads).
    if (providers.gradleProperty("oneThread").isPresent) systemProperty("gtnhplanner.solver.oneThread", "true")
    // -PwriteGoldens writes the solver goldens (conformance/solver/corpus/goldens) afresh from the solver as it is.
    if (providers.gradleProperty("writeGoldens").isPresent) systemProperty("gtnhplanner.solver.writeGoldens", "true")
    testLogging { events("passed", "skipped", "failed") }
}
