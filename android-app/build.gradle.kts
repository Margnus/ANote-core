import java.security.MessageDigest
import org.gradle.api.tasks.testing.Test

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
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets.getByName("androidTest").assets.srcDir("../fixtures/geometry")
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
// Pin the API-35 test model as a Gradle input; no runtime SDK fetching or shared Maven cache mutation.
val robolectricSdk by configurations.creating { isCanBeConsumed = false; isTransitive = false }
dependencies {
    implementation(project(":shared-core"))
    testImplementation(kotlin("test-junit"))
    // Isolated encoded-fixture metadata reader; no production import dependency.
    testImplementation("androidx.exifinterface:exifinterface:1.4.2")
    testImplementation("org.robolectric:robolectric:4.17")
    add(robolectricSdk.name, "org.robolectric:android-all-instrumented:15-robolectric-13954326-i7")
}

val prepareRobolectricSdk by tasks.registering(Copy::class) {
    from(robolectricSdk)
    into(rootProject.file(".android-tools/robolectric-sdk"))
}
tasks.withType<Test>().configureEach {
    dependsOn(prepareRobolectricSdk)
    inputs.files(robolectricSdk)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", rootProject.file(".android-tools/robolectric-sdk").absolutePath)
    systemProperty("notecore.brush.fixtures", rootProject.file("fixtures/brush/brush-golden.json").absolutePath)
    inputs.files(rootProject.file("fixtures/brush/brush-golden.json"), rootProject.file("fixtures/brush/brush-manifest.json"))
    val images = rootProject.file("build/android-brush-images/$name")
    systemProperty("notecore.brush.images", images.absolutePath)
    outputs.dir(images)
    val imageFixtures = rootProject.file("fixtures/image-orientation")
    systemProperty("notecore.image.fixtures", imageFixtures.absolutePath)
    val imageOutputs = rootProject.file("build/image-orientation-native/$name")
    systemProperty("notecore.image.outputs", imageOutputs.absolutePath)
    inputs.files(rootProject.fileTree(imageFixtures))
    outputs.dir(imageOutputs)
    val budgetOutputs = rootProject.file("build/image-decode-budget/$name")
    systemProperty("notecore.image.budget.outputs", budgetOutputs.absolutePath)
    outputs.dir(budgetOutputs)
    if (providers.gradleProperty("enablePdfGeometryNativeProbe").orNull == "true") {
        val pdfFixture = rootProject.file("output/pdf/crop-rotations.pdf")
        systemProperty("notecore.pdf.fixture", pdfFixture.absolutePath)
        systemProperty("notecore.pdf.recipe", rootProject.file("fixtures/pdf/recipe.json").absolutePath)
        val pdfImages = rootProject.file("build/pdf-native-images/$name")
        systemProperty("notecore.pdf.images", pdfImages.absolutePath)
        inputs.files(pdfFixture, rootProject.file("fixtures/pdf/recipe.json"))
        outputs.dir(pdfImages)
    }
}

tasks.register("verifyNativeBrush") {
    dependsOn("testDebugUnitTest")
    doLast {
        val inputs = rootProject.fileTree("canvas-core/src/commonMain") { include("**/*.kt") }.files +
            rootProject.fileTree("canvas-core/src/jvmAndAndroidMain") { include("**/*.kt") }.files +
            rootProject.fileTree("shared-core/src/commonMain") { include("**/*.kt") }.files +
            fileTree("src/main").files + fileTree("src/test").files +
            rootProject.fileTree("fixtures/brush") { include("*.json") }.files +
            fileTree("build/test-results/testDebugUnitTest") { include("TEST-*.xml") }.files +
            rootProject.fileTree("build/android-brush-images/testDebugUnitTest") { include("*.png") }.files + listOf(
                buildFile, rootProject.buildFile, rootProject.file("settings.gradle.kts"), rootProject.file("gradle.properties"),
                rootProject.file("canvas-core/build.gradle.kts"), rootProject.file("shared-core/build.gradle.kts"),
                rootProject.file("tools/collect_android_brush_verification.py"),
                rootProject.file(".android-tools/robolectric-sdk/android-all-instrumented-15-robolectric-13954326-i7.jar"))
        val stamp = rootProject.file("reports/android-brush-inputs.sha256")
        stamp.parentFile.mkdirs()
        stamp.writeText(inputs.sortedBy { it.relativeTo(rootDir).invariantSeparatorsPath }.joinToString("\n", postfix = "\n") {
            val sha = MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b -> "%02x".format(b) }
            "$sha  ${it.relativeTo(rootDir).invariantSeparatorsPath}"
        })
    }
}

tasks.register("verifyPreviewAndroid") {
    dependsOn("assembleDebug", "lintDebug")
    doLast {
        val inputs = rootProject.fileTree("shared-core/src/commonMain") { include("**/*.kt") }.files +
            rootProject.fileTree("canvas-core/src/commonMain") { include("**/*.kt") }.files +
            rootProject.fileTree("canvas-core/src/jvmAndAndroidMain") { include("**/*.kt") }.files +
            rootProject.fileTree("canvas-core/src/androidMain") { include("**/*.kt") }.files +
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

tasks.register("verifyBrushPathTestBuild") {
    dependsOn("assembleDebug", "assembleDebugAndroidTest")
    doLast {
        val inputs = fileTree("src").files + rootProject.fileTree("canvas-core/src/commonMain").files +
            rootProject.fileTree("canvas-core/src/jvmAndAndroidMain").files + rootProject.fileTree("canvas-core/src/androidMain").files + rootProject.fileTree("shared-core/src/commonMain").files +
            rootProject.fileTree("fixtures/geometry").files + listOf(buildFile, rootProject.buildFile,
                rootProject.file("canvas-core/build.gradle.kts"), rootProject.file("shared-core/build.gradle.kts"),
                rootProject.file("settings.gradle.kts"), rootProject.file("gradle.properties"),
                file("build/outputs/apk/debug/android-app-debug.apk"),
                file("build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk"))
        rootProject.file("reports/android-brush-build-inputs.sha256").writeText(inputs.sortedBy { it.relativeTo(rootDir).invariantSeparatorsPath }.joinToString("\n", postfix = "\n") {
            val digest = MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b -> "%02x".format(b) }
            "$digest  ${it.relativeTo(rootDir).invariantSeparatorsPath}"
        })
    }
}

// Explicit environment probe: SDK35 host PdfRenderer is currently unsupported; never part of default tests.
if (providers.gradleProperty("enablePdfGeometryNativeProbe").orNull == "true") {
    android.sourceSets.getByName("test").java.srcDir("../tools/probes/pdf-native")
}
