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
