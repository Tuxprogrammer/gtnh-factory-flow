
pluginManagement {
    repositories {
        maven {
            // RetroFuturaGradle
            name = "GTNH Maven"
            url = uri("https://nexus.gtnewhorizons.com/repository/public/")
            mavenContent {
                includeGroup("com.gtnewhorizons")
                includeGroupByRegex("com\\.gtnewhorizons\\..+")
            }
        }
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
    }
}

plugins {
    id("com.gtnewhorizons.gtnhsettingsconvention") version("2.0.26")
}

rootProject.name = "gtnh-planner-jvm"

// The mod, the web build of the core (web/, TeaVM), and every plain-Java core module: a directory under core/ with a
// build file, named by its directory (core/core-base is :core-base). A new module needs only its directory.
include("mod")
include("web")
file("core").listFiles { f -> f.isDirectory && File(f, "build.gradle.kts").isFile }
    ?.sortedBy { it.name }
    ?.forEach { dir ->
        include(dir.name)
        project(":${dir.name}").projectDir = dir
    }

// The settings plugin puts Spotless on every project's buildscript classpath; projects other than the mod declare no
// buildscript repositories to resolve it from.
gradle.beforeProject { buildscript.repositories { gradlePluginPortal(); mavenCentral() } }
