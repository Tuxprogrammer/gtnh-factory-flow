// The conformance tree (../conformance): test data both the website and the game must agree on, read by tests of every
// project here. Each Test task gets its directory as the system property gtnh.conformance (and declares it as an input,
// so a changed file reruns the suite), and every project's test source set gets the helper that reads it
// (conformance-support/.../Conformance.java). Tests never reach the tree through the classpath. See ../conformance/README.md.
val conformanceDir: File = rootDir.resolve("../conformance").canonicalFile
subprojects {
    plugins.withId("java") {
        extensions.getByType<SourceSetContainer>()["test"].java.srcDir(rootDir.resolve("conformance-support"))
    }
    tasks.withType<Test>().configureEach {
        systemProperty("gtnh.conformance", conformanceDir.path)
        inputs.dir(conformanceDir).withPropertyName("conformance").withPathSensitivity(PathSensitivity.RELATIVE)
    }
}

// The core modules (core/<name>, projects :core-*) are plain Java: no game, Forge or GTNH plugin on their classpath.
// They compile for Java 21, the newest level the web compile (TeaVM) is measured on; the mod shades them into its jar
// and downgrades them to Java 8 with its own classes (mod/addon.gradle).
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
}
