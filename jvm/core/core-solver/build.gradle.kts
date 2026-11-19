// core-solver: the solver kernel (balancer, its stages and alternatives), the records a solve reads and answers with,
// and the plan JSON reader and writer for those records. ojAlgo is the LP engine.
// ojAlgo as the core uses it: the published jar with every call of a java.lang.Math or StrictMath function that
// core-base's ExactMath has (log10 and pow in its scaling and tolerances, among others) sent to ExactMath, by
// ../exact-math/RewriteMath.java (JDK 25 class-file API). On the JVM those functions are processor- and JDK-specific
// code that can differ from StrictMath in the last place (on the build box: pow on one argument in ten, pow(10, k) for
// 64 of the 801 k from -400 to 400), and the web build sends the same calls to ExactMath (jvm/web ExactMathPlugin), so
// without this the solver's answers would differ between players' JDKs and between the game and the browser. Every
// consumer (the mod's shaded jar, the web build, the tests) gets this jar through core-solver's api; nobody else
// declares ojAlgo.
val ojalgoVersion = "57.4.0"
val ojalgo: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies { ojalgo("org.ojalgo:ojalgo:$ojalgoVersion") }
val ojalgoExact = layout.buildDirectory.file("ojalgo/ojalgo-$ojalgoVersion-exactmath.jar")
val rewriteOjalgo = tasks.register<Exec>("ojalgoExactMath") {
    description = "Writes ojAlgo with its java.lang.Math calls sent to core-base's ExactMath"
    val published: FileCollection = files(ojalgo)
    val tool = rootProject.file("core/exact-math/RewriteMath.java")
    inputs.files(published).withPropertyName("ojalgo")
    inputs.file(tool).withPropertyName("tool")
    outputs.file(ojalgoExact)
    val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
        .map { it.executablePath.asFile.absolutePath }
    val out = ojalgoExact
    val exec = this
    doFirst {
        exec.executable = launcher.get()
        exec.args = listOf(tool.absolutePath, published.singleFile.absolutePath, out.get().asFile.absolutePath)
    }
}

dependencies {
    "api"(project(":core-base"))
    // ojAlgo with its java.lang.Math calls sent to core-base's ExactMath: see ojalgoExactMath above.
    "api"(files(ojalgoExact).builtBy(rewriteOjalgo))
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
    // -PcasesReport=<file> writes where the kernel and the website differ on the website's cases (WebsiteCasesTest).
    providers.gradleProperty("casesReport").orNull?.let { systemProperty("gtnhplanner.solver.casesReport", it) }
    testLogging { events("passed", "skipped", "failed") }
}
