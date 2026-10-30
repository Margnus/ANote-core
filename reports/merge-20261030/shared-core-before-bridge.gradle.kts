plugins { kotlin("multiplatform") }
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
    if (providers.gradleProperty("enableAndroidTargets").orNull == "true") androidTarget()
    if (providers.gradleProperty("enableAppleTargets").orNull == "true") {
        iosArm64 { binaries.framework { baseName = "NoteCanvasPrototype"; isStatic = true; export(project(":canvas-core")) } }
        iosSimulatorArm64 { binaries.framework { baseName = "NoteCanvasPrototype"; isStatic = true; export(project(":canvas-core")) } }
    }
    sourceSets {
        commonMain.dependencies { api(project(":canvas-core")) }
        commonTest.dependencies { implementation(kotlin("test")) }
        if (providers.gradleProperty("enableAndroidTargets").orNull == "true") {
            getByName("androidInstrumentedTest") {
                dependsOn(getByName("commonTest"))
                dependencies { implementation(kotlin("test-junit")); implementation("junit:junit:4.13.2") }
            }
        }
    }
    jvmToolchain(17)
}
