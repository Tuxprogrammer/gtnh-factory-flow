// ojAlgo as the core uses it: the published jar with every call of a java.lang.Math or StrictMath function that
// core-base's ExactMath has (log10 and pow in its scaling and tolerances, among others) sent to ExactMath, every
// BigDecimal.valueOf(double) sent to core-base's NumberText.javaDecimal, and every HashMap and HashSet made a core-base
// JavaHashMap and JavaHashSet, by RewriteMath.java beside this file (JDK 25 class-file API). ojAlgo lays out an LP's
// rows and a row's terms in HashMap order, which the web compile's HashMap does not share with the JDK's; JavaHashMap
// iterates as JDK 19+'s HashMap does on every target, so the JVM's answers stay and the web's join them. On the JVM those functions are processor- and JDK-specific code that can differ from StrictMath in
// the last place (on the build box: pow on one argument in ten, pow(10, k) for 64 of the 801 k from -400 to 400), and
// BigDecimal.valueOf reads Double.toString, which Java 8 and 17 and the web compile spell differently; the web build
// sends the same calls to the same places (jvm/web ExactMathPlugin). So the mod, players' JDKs and the browser run one
// ojAlgo arithmetic. This project is a Java library (only a package-info of its own) whose jar is the rewritten one's entries:
// core-solver's api, so every consumer (the mod's shaded jar, the web build, the tests) gets it as a project artifact
// with the attributes the mod gives every core module (its deobfuscation transform then leaves it alone); nobody else
// declares ojAlgo. Its own sources are only a package-info, so the purity gate sees no ojAlgo code; RewrittenJarTest
// checks the rewrite reached the classes that matter. java-library, the repository and the toolchain come from the
// root build, as for every core module.

val ojalgoVersion = "57.4.0"
val ojalgo: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies {
    ojalgo("org.ojalgo:ojalgo:$ojalgoVersion")
    // The test reads the jar this project makes, and loads its classes, which call core-base.
    "testRuntimeOnly"(files(tasks.named("jar")))
    "testRuntimeOnly"(project(":core-base"))
}

val rewritten = layout.buildDirectory.file("rewritten/ojalgo-$ojalgoVersion-exactmath.jar")
val rewrite = tasks.register<Exec>("ojalgoExactMath") {
    description = "Writes ojAlgo with its maths, BigDecimal.valueOf, HashMap and HashSet sent to core-base"
    val published: FileCollection = files(ojalgo)
    val tool = file("RewriteMath.java")
    inputs.files(published).withPropertyName("ojalgo")
    inputs.file(tool).withPropertyName("tool")
    outputs.file(rewritten)
    val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
        .map { it.executablePath.asFile.absolutePath }
    val out = rewritten
    val exec = this
    doFirst {
        exec.executable = launcher.get()
        exec.args = listOf(tool.absolutePath, published.singleFile.absolutePath, out.get().asFile.absolutePath)
    }
}

// The jar is the rewritten jar's entries (and the package-info), and it is the only thing consumers see: the class and
// resource directory variants are removed, so a compile classpath takes the jar too.
tasks.named<Jar>("jar") {
    dependsOn(rewrite)
    from(rewritten.map { zipTree(it) })
    exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    archiveFileName.set("ojalgo-$ojalgoVersion-exactmath.jar")
}
for (name in listOf("apiElements", "runtimeElements")) {
    configurations.named(name) { outgoing.variants.clear() }
}
