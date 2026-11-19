// The core modules (core/<name>, projects :core-*) are plain Java: no game, Forge or GTNH plugin on their classpath.
// They compile for Java 21, the newest level the web compile (TeaVM) is measured on; the mod shades them into its jar
// and downgrades them to Java 8 with its own classes (mod/addon.gradle).
//
// Every core module's `check` (so `build`) runs the purity gate, core/purity/PurityCheck.java: it fails when the
// module's main code or its classpath uses what core/purity/rules.txt names (the game, reflection, threads, java.net,
// files, AWT, zip, java.security, String.format, the JDK's float spelling and parsing, java.util.UUID, system
// properties, the default locale, Lombok, log4j) and core/purity/allow.txt does not excuse. :core-base's check also
// runs the gate's self-test on the planted violations in core/purity/planted.
val purity = file("core/purity")

subprojects {
    if (!name.startsWith("core-")) return@subprojects
    apply(plugin = "java-library")
    repositories { mavenCentral() }
    extensions.configure<JavaPluginExtension> { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
        options.encoding = "UTF-8"
    }
    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.10.2")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher:1.10.2")
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { events("failed") }
    }

    // The gate runs on JDK 25 for its class-file API, whatever JDK runs Gradle.
    val java25 = extensions.getByType<JavaToolchainService>()
        .launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
        .map { it.executablePath.asFile.absolutePath }
    val main = extensions.getByType<SourceSetContainer>().getByName("main")
    fun coordinates(configuration: String) = configurations.getByName(configuration).incoming.artifacts.resolvedArtifacts
        .map { set -> set.map { it.id.componentIdentifier.displayName } }
    val deps = coordinates("compileClasspath").zip(coordinates("runtimeClasspath")) { a, b -> (a + b).distinct().sorted() }
    val moduleName = name
    val gate = purity.absolutePath
    val jvmRoot = rootDir.absolutePath
    val sources = main.java.srcDirs.single().absolutePath
    val classes = main.java.classesDirectory.map { it.asFile.absolutePath }
    val report = layout.buildDirectory.file("purity/report.txt")
    val purityCheck = tasks.register<Exec>("purityCheck") {
        description = "Fails when this core module uses what core/purity/rules.txt forbids and allow.txt does not excuse"
        group = "verification"
        dependsOn("classes")
        inputs.files(main.output.classesDirs).withPropertyName("classes")
        inputs.files(main.java.srcDirs).withPropertyName("sources")
        inputs.files(purity.resolve("PurityCheck.java"), purity.resolve("rules.txt"), purity.resolve("allow.txt"))
            .withPropertyName("gate")
        inputs.property("dependencies", deps)
        outputs.file(report)
        val exec = this
        doFirst {
            exec.executable = java25.get()
            exec.args = listOf(
                "$gate/PurityCheck.java", "--root", jvmRoot, "--module", moduleName, "--sources", sources,
                "--classes", classes.get(), "--rules", "$gate/rules.txt", "--allow", "$gate/allow.txt",
                "--report", report.get().asFile.absolutePath,
            ) + deps.get().flatMap { listOf("--dep", it) }
        }
    }
    tasks.named("check") { dependsOn(purityCheck) }

    if (name == "core-base") {
        val selfTestReport = layout.buildDirectory.file("purity/self-test.txt")
        val selfTest = tasks.register<Exec>("puritySelfTest") {
            description = "Checks that the purity gate finds every planted violation in core/purity/planted, and no more"
            group = "verification"
            inputs.dir(purity.resolve("planted")).withPropertyName("planted")
            inputs.files(purity.resolve("PurityCheck.java"), purity.resolve("rules.txt")).withPropertyName("gate")
            outputs.file(selfTestReport)
            val exec = this
            doFirst {
                exec.executable = java25.get()
                exec.args = listOf(
                    "$gate/PurityCheck.java", "--self-test", "$gate/planted", "--rules", "$gate/rules.txt",
                    "--report", selfTestReport.get().asFile.absolutePath,
                )
            }
        }
        tasks.named("check") { dependsOn(selfTest) }
    }
}
