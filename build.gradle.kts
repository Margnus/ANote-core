import java.security.MessageDigest
import org.gradle.api.tasks.wrapper.Wrapper

plugins {
    kotlin("multiplatform") version "2.2.21" apply false
}

tasks.register("verifyPrototype") {
    group = "verification"
    description = "Verify the C1 common-code contract prototype on JVM."
    dependsOn(":canvas-core:jvmTest")
    doLast {
        val inputs = fileTree("canvas-core/src") { include("**/*.kt") }.files + listOf(
            file("settings.gradle.kts"), file("build.gradle.kts"),
            file("canvas-core/build.gradle.kts"), file("gradle.properties"),
        )
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
