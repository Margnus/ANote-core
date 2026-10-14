import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import org.gradle.api.tasks.testing.Test

plugins { kotlin("multiplatform") }

kotlin {
    jvm()
    if (providers.gradleProperty("enableAppleTargets").orNull == "true") {
        iosArm64 { binaries.framework { baseName = "NoteCanvasPrototype"; isStatic = true } }
        iosSimulatorArm64 { binaries.framework { baseName = "NoteCanvasPrototype"; isStatic = true } }
    }
    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmTest {
            resources.srcDir("../fixtures/input")
            dependencies { implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0") }
        }
    }
    jvmToolchain(17)
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
}

tasks.register<JavaExec>("runPrototype") {
    group = "application"
    description = "Run the in-memory C1 scene contract demonstration."
    dependsOn("jvmMainClasses")
    val mainCompilation = kotlin.targets.getByName<KotlinJvmTarget>("jvm").compilations.getByName("main")
    classpath(mainCompilation.output.allOutputs, configurations.named("jvmRuntimeClasspath"))
    mainClass.set("dev.notecore.canvas.prototype.PrototypeDemoKt")
}
