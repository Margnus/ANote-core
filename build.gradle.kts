import java.security.MessageDigest
import org.gradle.api.tasks.wrapper.Wrapper

plugins {
    kotlin("multiplatform") version "2.2.21" apply false
}

val verifyFixtureCatalog by tasks.registering(Exec::class) {
    commandLine(providers.environmentVariable("PYTHON_BIN").orElse("python3").get(),
        rootProject.file("tools/validate_fixture_catalog.py").absolutePath)
}

tasks.register("verifyPrototype") {
    group = "verification"
    description = "Verify the C1 common-code contract prototype on JVM."
    dependsOn(":canvas-core:jvmTest")
    dependsOn(verifyFixtureCatalog)
    doLast {
        val inputs = fileTree("canvas-core/src") { include("**/*.kt") }.files +
            fileTree("fixtures") { include("**/*.json") }.files +
            fileTree("tools") { include("*.py") }.files + listOf(
            file("settings.gradle.kts"), file("build.gradle.kts"),
            file("canvas-core/build.gradle.kts"), file("gradle.properties"), file("ci/verify.sh"),
        ) + fileTree(".generated-fixtures/scale") { include("*.bin", "verified-manifest.json") }.files
        val testXml = fileTree("canvas-core/build/test-results/jvmTest") { include("TEST-*.xml") }.files
        check(testXml.isNotEmpty()) { "Expected real JVM test XML before stamping verification" }
        val records = (inputs + testXml).sortedBy { it.relativeTo(rootDir).invariantSeparatorsPath }.map { input ->
            val sha = MessageDigest.getInstance("SHA-256").digest(input.readBytes())
                .joinToString("") { "%02x".format(it) }
            "$sha  ${input.relativeTo(rootDir).invariantSeparatorsPath}"
        }
        file("reports/gradle-verification.sha256").apply {
            parentFile.mkdirs()
            writeText(records.joinToString("\n", postfix = "\n"))
        }
    }
}

tasks.wrapper {
    gradleVersion = "8.14"
    distributionType = Wrapper.DistributionType.BIN
    distributionSha256Sum = "61ad310d3c7d3e5da131b76bbf22b5a4c0786e9d892dae8c1658d4b484de3caa"
    networkTimeout.set(30_000)
}
