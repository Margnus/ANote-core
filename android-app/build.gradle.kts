import java.security.MessageDigest

plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "dev.notecore.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.notecore.preview"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-preview"
        testInstrumentationRunner = "dev.notecore.app.ViewportInstrumentation"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies { implementation(project(":shared-core")) }

tasks.register("verifyPreviewAndroid") {
    dependsOn("assembleDebug", "lintDebug")
    doLast {
        val inputs = rootProject.fileTree("shared-core/src/commonMain") { include("**/*.kt") }.files +
            rootProject.fileTree("canvas-core/src/commonMain") { include("**/*.kt") }.files +
            rootProject.fileTree("canvas-core/src/jvmAndAndroidMain") { include("**/*.kt") }.files +
            fileTree("src/main").files + listOf(buildFile, rootProject.buildFile,
                rootProject.file("settings.gradle.kts"), rootProject.file("gradle.properties"),
                rootProject.file("canvas-core/build.gradle.kts"), rootProject.file("shared-core/build.gradle.kts"),
                file("build/outputs/apk/debug/android-app-debug.apk"), file("build/reports/lint-results-debug.xml"))
        val stamp = rootProject.file("reports/android-build-inputs.sha256")
        stamp.parentFile.mkdirs()
        stamp.writeText(inputs.sortedBy { it.relativeTo(rootDir).invariantSeparatorsPath }.joinToString("\n", postfix = "\n") {
            val digest = MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b -> "%02x".format(b) }
            "$digest  ${it.relativeTo(rootDir).invariantSeparatorsPath}"
        })
    }
}
