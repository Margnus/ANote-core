import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

plugins { kotlin("multiplatform") }
val sdkCandidate = providers.gradleProperty("sdkCandidate").map(String::toBoolean).orElse(false)

if (sdkCandidate.get()) {
    layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("sdk-candidate/$name"))
    apply(plugin = "maven-publish")
    group = "dev.notecore.probe"
    version = "0.0.0-c1"
    extensions.configure<PublishingExtension> {
        repositories {
            maven {
                name = "SdkCandidate"
                url = uri(rootProject.layout.buildDirectory.dir("sdk-candidate-repository"))
            }
        }
    }
}

if (providers.gradleProperty("enableAndroidTargets").orNull == "true") {
    apply(plugin = "com.android.library")
    extensions.configure<com.android.build.gradle.LibraryExtension> {
        namespace = "dev.notecore.document"
        compileSdk = 36
        defaultConfig {
            minSdk = 26
            testApplicationId = "dev.notecore.document.poc.test"
            testInstrumentationRunner = "dev.notecore.document.DocumentOrderInstrumentation"
        }
        compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    }
}
kotlin {
    jvm()
    if (providers.gradleProperty("enableAndroidTargets").orNull == "true") {
        androidTarget {
            if (sdkCandidate.get()) publishLibraryVariants("release")
        }
    }
    if (providers.gradleProperty("enableAppleTargets").orNull == "true") {
        iosArm64 { binaries.framework { baseName = "NoteCanvasPrototype"; isStatic = true; export(project(":canvas-core")) } }
        iosSimulatorArm64 { binaries.framework { baseName = "NoteCanvasPrototype"; isStatic = true; export(project(":canvas-core")) } }
    }
    sourceSets {
        commonMain.dependencies { api(project(":canvas-core")) }
        commonTest.dependencies { implementation(kotlin("test")) }
        if (providers.gradleProperty("enableAppleTargets").orNull == "true") {
            // The explicit Android instrumented-test edge disables the automatic hierarchy.
            // Keep the original NSData input bridge in both Apple framework targets as well.
            val iosMain = maybeCreate("iosMain").apply { dependsOn(getByName("commonMain")) }
            getByName("iosArm64Main").dependsOn(iosMain)
            getByName("iosSimulatorArm64Main").dependsOn(iosMain)
        }
        if (providers.gradleProperty("enableAndroidTargets").orNull == "true") {
            getByName("androidInstrumentedTest") {
                dependsOn(getByName("commonTest"))
                dependencies { implementation(kotlin("test-junit")); implementation("junit:junit:4.13.2") }
            }
        }
        // These ports are supplied only by the original App integration build.
        if (sdkCandidate.get()) {
            getByName("commonMain").kotlin.exclude(
                "**/PreviewSaveLedger.kt",
                "**/PreviewStreamReader.kt",
                "dev/notecore/document/PreviewAppRecoveryPreflight.kt",
                "dev/notecore/library/**",
            )
            findByName("androidMain")?.kotlin?.exclude("**/PreviewEditorAdmission.kt", "**/AndroidPreviewTextCodec.kt")
            findByName("iosMain")?.kotlin?.exclude("**/ApplePreviewEditorAdmission.kt", "**/ApplePreviewTextCodec.kt")
        }
    }
    jvmToolchain(17)
}

tasks.register<JavaExec>("writePdfCompositeProbePlan") {
    group = "verification"
    description = "Generate isolated Android and Apple composite PDF tasks from the shared planner."
    dependsOn("jvmTestClasses")
    val tests = kotlin.targets.getByName<KotlinJvmTarget>("jvm").compilations.getByName("test")
    classpath(tests.output.allOutputs, configurations.named("jvmTestRuntimeClasspath"))
    mainClass.set("dev.notecore.document.PdfCompositeProbePlan")
    args(rootProject.file("android-app/src/androidTest/assets/pdf/crop-rotations.pdf").absolutePath,
        rootProject.file("build/pdf-composite-plan.json").absolutePath)
}
