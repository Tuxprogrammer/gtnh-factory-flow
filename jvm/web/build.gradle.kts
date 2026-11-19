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
    ":core-machines",
    ":core-power",
    ":core-layout",
    ":core-solver",
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
    // javax.annotation.Nullable on an export that can return null: the TypeScript declarations are generated from it.
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
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

// The modules (modules.txt): each is its own TeaVM compilation per target, from the same sources, with its own main class and
// exports classes, so a page loads only what it uses. TeaVM 0.16 has no code splitting, so two modules share no state and
// each carries its own copy of the code it reaches in core-base (README.md, "Modules"). Every class in the exports package
// is exported by the module that names it; the module that says * takes the rest.
class WebModule(val name: String, val main: String, val exports: List<String>)
val exportsDir = file("src/main/java/com/gtnhplanner/web/exports")
val allExportClasses = exportsDir.listFiles { f -> f.name.endsWith(".java") }!!
    .map { it.name.removeSuffix(".java") }.sorted()
val webModules: List<WebModule> = run {
    val rows = file("modules.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { it.split(Regex("\\s+")) }
    val named = rows.flatMap { it.drop(2) }.filter { it != "*" }.toSet()
    rows.map { r ->
        WebModule(r[0], "com.gtnhplanner.web." + r[1], r.drop(2).flatMap { if (it == "*") allExportClasses.filter { c -> c !in named } else listOf(it) }
            .map { "com.gtnhplanner.web.exports.$it" }.sorted())
    }
}
val plain = providers.gradleProperty("plain").isPresent // readable names, for debugging
val firstModule = webModules.first()
// BALANCED, not AGGRESSIVE: the same speed on the conformance run (within its noise) in 22 to 34 percent fewer gzipped bytes on the
// wire (README.md, "Compiler options"). -PwebOptimization=AGGRESSIVE|NONE rebuilds with another level to compare.
val webOptimization = providers.gradleProperty("webOptimization").map { OptimizationLevel.valueOf(it) }
    .orElse(OptimizationLevel.BALANCED)

teavm {
    js {
        mainClass.set(firstModule.main)
        preservedClasses.addAll(firstModule.exports)
        moduleType.set(JSModuleType.ES2015)
        obfuscated.set(!plain)
        sourceMap.set(true)
        optimization.set(webOptimization)
        targetFileName.set("${firstModule.name}.js")
    }
    wasmGC {
        mainClass.set(firstModule.main)
        preservedClasses.addAll(firstModule.exports)
        obfuscated.set(!plain)
        optimization.set(webOptimization)
        targetFileName.set("${firstModule.name}.wasm")
    }
}

// TeaVM takes the first class of a name on its classpath, and the plugin puts the project's own classes last, so a
// class here that replaces one in a jar (a classlib class, an ojAlgo class) would be ignored. Put them first.
// The plugin registers one task per target, for the first module. The other modules get a task of the same type per target,
// configured from the first one's (same classpath, optimisation, obfuscation, output directory) with their own main class,
// exports and file name. They write beside the first module's files: TeaVM writes only its own.
val extraGenerateTasks = mutableListOf<String>()
afterEvaluate {
    tasks.withType<org.teavm.gradle.tasks.TeaVMTask>().configureEach {
        val original = classpath.from.toTypedArray()
        classpath.setFrom(sourceSets["main"].output.classesDirs, *original)
    }
    for (module in webModules.drop(1)) {
        val wasm = tasks.named<org.teavm.gradle.tasks.GenerateWasmGCTask>("generateWasmGC").get()
        val name = "generate${module.name.replaceFirstChar { it.uppercase() }}WasmGC"
        tasks.register<org.teavm.gradle.tasks.GenerateWasmGCTask>(name) {
            group = "build"
            dependsOn("classes", "checkOverridesWasmGC")
            classpath.setFrom(wasm.classpath)
            mainClass.set(module.main)
            preservedClasses.set(module.exports)
            targetFileName.set("${module.name}.wasm")
            outputDir.set(wasm.outputDir)
            debugInformation.set(wasm.debugInformation)
            fastGlobalAnalysis.set(wasm.fastGlobalAnalysis)
            optimization.set(wasm.optimization)
            properties.set(wasm.properties)
            outOfProcess.set(wasm.outOfProcess)
            processMemory.set(wasm.processMemory)
            strict.set(wasm.strict)
            obfuscated.set(wasm.obfuscated)
            debugInfoLevel.set(wasm.debugInfoLevel)
            debugInfoLocation.set(wasm.debugInfoLocation)
            sourceMap.set(wasm.sourceMap)
            sourceFilePolicy.set(wasm.sourceFilePolicy)
            minDirectBuffersSize.set(wasm.minDirectBuffersSize)
            sharedBuffer.set(wasm.sharedBuffer)
        }
        extraGenerateTasks += name
        val js = tasks.named<org.teavm.gradle.tasks.GenerateJavaScriptTask>("generateJavaScript").get()
        val jsName = "generate${module.name.replaceFirstChar { it.uppercase() }}JavaScript"
        tasks.register<org.teavm.gradle.tasks.GenerateJavaScriptTask>(jsName) {
            group = "build"
            dependsOn("classes", "checkOverridesJavaScript")
            classpath.setFrom(js.classpath)
            mainClass.set(module.main)
            preservedClasses.set(module.exports)
            targetFileName.set("${module.name}.js")
            outputDir.set(js.outputDir)
            debugInformation.set(js.debugInformation)
            fastGlobalAnalysis.set(js.fastGlobalAnalysis)
            optimization.set(js.optimization)
            properties.set(js.properties)
            outOfProcess.set(js.outOfProcess)
            processMemory.set(js.processMemory)
            strict.set(js.strict)
            obfuscated.set(js.obfuscated)
            moduleType.set(js.moduleType)
            sourceMap.set(js.sourceMap)
            entryPointName.set(js.entryPointName)
            sourceFilePolicy.set(js.sourceFilePolicy)
            maxTopLevelNames.set(js.maxTopLevelNames)
        }
        extraGenerateTasks += jsName
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

// The random comparison's cases, made on the JVM by the core's own classes (src/test/java/.../RandomCases.java).
// tools/web-core/run.mjs --random replays them against each compiled target.
tasks.register<JavaExec>("webRandomCases") {
    description = "Writes the random conformance cases (JSON lines) for tools/web-core/run.mjs"
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.gtnhplanner.web.RandomCases")
    val out = layout.buildDirectory.file("random/cases.jsonl")
    outputs.file(out)
    args(out.get().asFile.absolutePath, providers.gradleProperty("randomSeed").getOrElse("20261119"),
        providers.gradleProperty("randomScale").getOrElse("1"))
}

// What tools/ci/web-core.sh builds: both targets, the Wasm one with its runtime file.
tasks.register("webCore") {
    description = "Builds the core for the browser: WebAssembly GC and JavaScript, checked for overrides"
    group = "build"
    dependsOn("buildWasmGC", "generateJavaScript")
    // The other modules' tasks exist after evaluation (see above).
    dependsOn(provider { extraGenerateTasks.toList() })
}
