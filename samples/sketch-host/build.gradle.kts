import java.security.MessageDigest
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

plugins {
    id("com.android.application")
    kotlin("android")
}
android {
    namespace = "dev.notecore.sample.sketch"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.notecore.sample.sketch"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-unfrozen"
        testInstrumentationRunner = "dev.notecore.sample.sketch.SketchInstrumentation"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies { implementation(project(":shared-core")) }

// Stamp the exact consumer and core sources after successful compilation/lint; never rehash an old result into a new build.
tasks.register("verifySketchHost") {
    dependsOn("assembleDebug", "assembleDebugAndroidTest", "lintDebug")
    doLast {
        val evidence = rootProject.file("build/sketch-host").apply { mkdirs() }
        val projects = configurations.getByName("debugRuntimeClasspath").incoming.resolutionResult.allComponents
            .mapNotNull { (it.id as? ProjectComponentIdentifier)?.projectPath }.sorted()
        check(projects == listOf(":canvas-core", ":samples:sketch-host", ":shared-core"))
        evidence.resolve("runtime-projects.txt").writeText(projects.joinToString("\n", postfix = "\n"))
        val inputs = listOf("canvas-core/src/commonMain", "canvas-core/src/jvmAndAndroidMain", "canvas-core/src/androidMain", "shared-core/src/commonMain")
            .flatMap { rootProject.fileTree(it) { include("**/*.kt") }.files } + fileTree("src").files + listOf(
                buildFile, rootProject.buildFile, rootProject.file("settings.gradle.kts"), rootProject.file("gradle.properties"),
                rootProject.file("canvas-core/build.gradle.kts"), rootProject.file("shared-core/build.gradle.kts"),
                file("build/outputs/apk/debug/sketch-host-debug.apk"), file("build/outputs/apk/androidTest/debug/sketch-host-debug-androidTest.apk"),
                file("build/reports/lint-results-debug.xml"), evidence.resolve("runtime-projects.txt"))
        evidence.resolve("build-inputs.sha256").writeText(inputs.sortedBy { it.relativeTo(rootDir).invariantSeparatorsPath }.joinToString("\n", postfix = "\n") {
            val sha = MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b -> "%02x".format(b) }
            "$sha  ${it.relativeTo(rootDir).invariantSeparatorsPath}"
        })
    }
}
