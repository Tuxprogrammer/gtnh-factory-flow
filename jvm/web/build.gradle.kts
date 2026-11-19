// web: the core compiled for the browser, Node and Workers with TeaVM, from the same sources the mod shades.
// Two targets from one build: WebAssembly GC (what the website runs) and JavaScript (the fallback for engines
// without Wasm GC). Nothing here is shipped by the mod; `build` does not run TeaVM, tools/ci/web-core.sh does
// (:web:webCore). Rules, the export convention and the override check: README.md beside this file.
import org.teavm.gradle.api.JSModuleType
import org.teavm.gradle.api.OptimizationLevel

plugins {
    java
    // Resolved from the plugin portal / Maven Central (settings.gradle.kts pluginManagement); no repository is added.
    id("org.teavm") version "0.16.0"
}

// The core modules in the web build. Adding one is this line, plus an exports class for it in
// src/main/java/com/gtnhplanner/web/exports (every class there is exported without being listed anywhere).
val coreModules = listOf(
    ":core-base",
)

val teavmVersion = "0.16.0"

repositories { mavenCentral() }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

dependencies {
    coreModules.forEach { implementation(project(it)) }
    implementation(teavm.libs.jsoApis)
    // The classlib overrides (src/main/java/org/teavm/...) compile against TeaVM's own classes; they are not shipped.
    compileOnly("org.teavm:teavm-classlib:$teavmVersion")
    compileOnly("org.teavm:teavm-interop:$teavmVersion")
    compileOnly("org.teavm:teavm-core:$teavmVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging { events("failed") }
}

// Every class in the exports package is exported: its public static @JSExport methods become the module's functions.
val exportsDir = file("src/main/java/com/gtnhplanner/web/exports")
val exportClasses = exportsDir.listFiles { f -> f.name.endsWith(".java") }!!
    .map { "com.gtnhplanner.web.exports." + it.name.removeSuffix(".java") }
    .sorted()
val entry = "com.gtnhplanner.web.WebCore"
val plain = providers.gradleProperty("plain").isPresent // readable names, for debugging

teavm {
    js {
        mainClass.set(entry)
        preservedClasses.addAll(exportClasses)
        moduleType.set(JSModuleType.ES2015)
        obfuscated.set(!plain)
        sourceMap.set(true)
        optimization.set(OptimizationLevel.AGGRESSIVE)
        targetFileName.set("core.js")
    }
    wasmGC {
        mainClass.set(entry)
        preservedClasses.addAll(exportClasses)
        obfuscated.set(!plain)
        optimization.set(OptimizationLevel.AGGRESSIVE)
        targetFileName.set("core.wasm")
    }
}

// TeaVM takes the first class of a name on its classpath, and the plugin puts the project's own classes last, so a
// class here that replaces one in a jar (a classlib class, an ojAlgo class) would be ignored. Put them first.
afterEvaluate {
    tasks.withType<org.teavm.gradle.tasks.TeaVMTask>().configureEach {
        val original = classpath.from.toTypedArray()
        classpath.setFrom(sourceSets["main"].output.classesDirs, *original)
    }
}

// The override check: fails when a class of this module that replaces a JDK or library class is not the first one on
// the classpath TeaVM compiles from, or when the set of replaced classes is not the one overrides.txt lists (a plugin
// upgrade that moves a replaced class would make its override a plain new class, never used). It reads the classpath of
// each TeaVM task as the task will see it. check/OverrideCheck.java is single-file Java, run on the JDK 25 toolchain.
val java25 = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
    .map { it.executablePath.asFile.absolutePath }
val checkSource = file("check/OverrideCheck.java")
val overridesFile = file("overrides.txt")
val overrideChecks = listOf("generateJavaScript", "generateWasmGC").map { generate ->
    tasks.register<Exec>("checkOverrides${generate.removePrefix("generate")}") {
        description = "Fails when a class override is not the one $generate compiles in"
        group = "verification"
        // The task's classpath object itself: depending on the task (a provider of it) would make a cycle.
        val teavmClasspath = tasks.named<org.teavm.gradle.tasks.TeaVMTask>(generate).get().classpath
        val classesDirs = sourceSets["main"].output.classesDirs
        dependsOn("classes")
        inputs.files(classesDirs).withPropertyName("classes")
        inputs.files(teavmClasspath).withPropertyName("teavmClasspath")
        inputs.files(checkSource, overridesFile).withPropertyName("rules")
        val report = layout.buildDirectory.file("overrides/$generate.txt")
        outputs.file(report)
        // Locals, not the script's own vals: a task action that reaches a script property cannot be cached.
        val exec = this
        val launcher = java25
        val source = checkSource.absolutePath
        val rules = overridesFile.absolutePath
        doFirst {
            exec.executable = launcher.get()
            exec.args = listOf(
                source, "--overrides", rules,
                "--own", classesDirs.files.single().absolutePath, "--report", report.get().asFile.absolutePath,
            ) + teavmClasspath.files.flatMap { listOf("--classpath", it.absolutePath) }
        }
    }
}
tasks.named("check") { dependsOn(overrideChecks) }
tasks.named("generateJavaScript") { dependsOn("checkOverridesJavaScript") }
tasks.named("generateWasmGC") { dependsOn("checkOverridesWasmGC") }

// What tools/ci/web-core.sh builds: both targets, the Wasm one with its runtime file.
tasks.register("webCore") {
    description = "Builds the core for the browser: WebAssembly GC and JavaScript, checked for overrides"
    group = "build"
    dependsOn("buildWasmGC", "generateJavaScript")
}
