import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

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

tasks.register<JavaExec>("runPrototype") {
    group = "application"
    description = "Run the in-memory C1 scene contract demonstration."
    dependsOn("jvmMainClasses")
    val mainCompilation = kotlin.targets.getByName<KotlinJvmTarget>("jvm").compilations.getByName("main")
    classpath(mainCompilation.output.allOutputs, configurations.named("jvmRuntimeClasspath"))
    mainClass.set("dev.notecore.canvas.prototype.PrototypeDemoKt")
}
