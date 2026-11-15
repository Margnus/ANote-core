import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import org.gradle.api.tasks.testing.Test

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
        namespace = "dev.notecore.canvas"
        compileSdk = 36
        defaultConfig {
            minSdk = 26
            testApplicationId = "dev.notecore.canvas.poc.test"
            testInstrumentationRunner = "dev.notecore.canvas.prototype.CoreBrushInstrumentation"
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
        iosArm64 { binaries.framework { baseName = "NoteCanvasPrototype"; isStatic = true } }
        iosSimulatorArm64 { binaries.framework { baseName = "NoteCanvasPrototype"; isStatic = true } }
    }
    sourceSets {
        val jvmAndAndroidMain by creating { dependsOn(getByName("commonMain")) }
        getByName("jvmMain").dependsOn(jvmAndAndroidMain)
        if (providers.gradleProperty("enableAndroidTargets").orNull == "true") getByName("androidMain").dependsOn(jvmAndAndroidMain)
        if (providers.gradleProperty("enableAppleTargets").orNull == "true") {
            val iosMain by creating { dependsOn(getByName("commonMain")) }
            getByName("iosArm64Main").dependsOn(iosMain)
            getByName("iosSimulatorArm64Main").dependsOn(iosMain)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        val jvmAndAndroidTest by creating { dependsOn(getByName("commonTest")) }
        getByName("jvmTest").dependsOn(jvmAndAndroidTest)
        if (providers.gradleProperty("enableAndroidTargets").orNull == "true") {
            getByName("androidInstrumentedTest") {
                dependsOn(jvmAndAndroidTest)
                dependencies { implementation(kotlin("test-junit")); implementation("junit:junit:4.13.2") }
            }
        }
        jvmTest {
            resources.srcDir("../fixtures/input")
            resources.srcDir("../fixtures/geometry")
            dependencies { implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0") }
        }
        // These ports are supplied only by the original App integration build.
        if (sdkCandidate.get()) {
            getByName("commonMain").kotlin.exclude(
                "**/DocumentCloseCoordinator.kt",
                "**/OwnedWork.kt",
                "**/ResourceLeasePool.kt",
                "**/BrushRenderOwnerPoc.kt",
                "**/BrushTileCachePoc.kt",
                "**/BrushTilePixelsPoc.kt",
            )
            jvmAndAndroidMain.kotlin.exclude("**/BoundedOwnerExecutor.kt", "**/BrushRenderExecutorPoc.kt")
            getByName("jvmMain").kotlin.exclude("**/PrototypeDemo.kt")
            findByName("androidMain")?.kotlin?.exclude("**/AndroidInkFrameExperiment.kt", "**/AndroidBrushImagePoc.kt")
            findByName("iosMain")?.kotlin?.exclude("**/AppleBrushRenderExperiment.kt")
        }
    }
    jvmToolchain(17)
}

// Keep the independent candidate specification distinct from the existing mask goldens.
tasks.named<Copy>("jvmTestProcessResources") {
    from("../fixtures/brush") { into("brush") }
}

val generateScaleFixtures by tasks.registering(Exec::class) {
    val script = rootProject.file("tools/generate_scale_fixtures.py")
    val manifest = rootProject.file("fixtures/scale/manifest.json")
    val output = rootProject.file(".generated-fixtures/scale")
    inputs.files(script, manifest)
    outputs.dir(output)
    commandLine(providers.environmentVariable("PYTHON_BIN").orElse("python3").get(), script.absolutePath)
}

tasks.named<Test>("jvmTest") {
    dependsOn(generateScaleFixtures)
    inputs.files(rootProject.file("fixtures/scale/manifest.json"))
    inputs.dir(rootProject.file(".generated-fixtures/scale"))
    systemProperty("notecore.scale.fixture.dir", rootProject.file(".generated-fixtures/scale").absolutePath)
    systemProperty("notecore.scale.manifest", rootProject.file("fixtures/scale/manifest.json").absolutePath)
    systemProperty("notecore.brush.actual", rootProject.file("build/brush-golden-actual.json").absolutePath)
}

if (!sdkCandidate.get()) {
    tasks.register<JavaExec>("runPrototype") {
        group = "application"
        description = "Run the in-memory C1 scene contract demonstration."
        dependsOn("jvmMainClasses")
        val mainCompilation = kotlin.targets.getByName<KotlinJvmTarget>("jvm").compilations.getByName("main")
        classpath(mainCompilation.output.allOutputs, configurations.named("jvmRuntimeClasspath"))
        mainClass.set("dev.notecore.canvas.prototype.PrototypeDemoKt")
    }
}

tasks.register<JavaExec>("runSpatialIndexPoc") {
    group = "verification"
    description = "Opt-in M-3 desktop AABB experiment; not a physical device benchmark."
    dependsOn("jvmTestClasses", generateScaleFixtures)
    val testCompilation = kotlin.targets.getByName<KotlinJvmTarget>("jvm").compilations.getByName("test")
    classpath(testCompilation.output.allOutputs, configurations.named("jvmTestRuntimeClasspath"))
    mainClass.set("dev.notecore.canvas.prototype.SpatialIndexBenchmark")
    args(rootProject.projectDir.absolutePath)
}

tasks.register<JavaExec>("generateBrushPathFixtures") {
    group = "verification"
    dependsOn("jvmTestClasses")
    val tests = kotlin.targets.getByName<KotlinJvmTarget>("jvm").compilations.getByName("test")
    classpath(tests.output.allOutputs, configurations.named("jvmTestRuntimeClasspath"))
    mainClass.set("dev.notecore.canvas.prototype.BrushPathFixtures")
    args(rootProject.projectDir.absolutePath)
}

tasks.register<JavaExec>("runBrushPathCosts") {
    group = "verification"
    dependsOn("jvmTestClasses", generateScaleFixtures)
    val tests = kotlin.targets.getByName<KotlinJvmTarget>("jvm").compilations.getByName("test")
    classpath(tests.output.allOutputs, configurations.named("jvmTestRuntimeClasspath"))
    mainClass.set("dev.notecore.canvas.prototype.BrushPathCosts")
    args(rootProject.projectDir.absolutePath)
}

tasks.register<JavaExec>("runBrushTileCacheCosts") {
    group = "verification"
    description = "Synthetic viewport cache/reader accounting trace; not a heap or device benchmark."
    dependsOn("jvmTestClasses")
    val tests = kotlin.targets.getByName<KotlinJvmTarget>("jvm").compilations.getByName("test")
    classpath(tests.output.allOutputs, configurations.named("jvmTestRuntimeClasspath"))
    mainClass.set("dev.notecore.canvas.prototype.BrushTileCacheCosts")
    args(rootProject.projectDir.absolutePath)
}

tasks.register<JavaExec>("runCanonicalIndexBenchmark") {
    group = "verification"
    description = "Compare existing exact and broad-phase index candidates on canonical reference ink."
    dependsOn("jvmTestClasses", generateScaleFixtures)
    val testCompilation = kotlin.targets.getByName<KotlinJvmTarget>("jvm").compilations.getByName("test")
    classpath(testCompilation.output.allOutputs, configurations.named("jvmTestRuntimeClasspath"))
    mainClass.set("dev.notecore.canvas.prototype.CanonicalIndexBenchmark")
    args(rootProject.projectDir.absolutePath, rootProject.file("output/canonical-index-benchmark/result.json").absolutePath)
    doFirst { rootProject.file("output/canonical-index-benchmark").mkdirs() }
}
