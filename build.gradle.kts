import java.security.MessageDigest
import org.gradle.api.tasks.wrapper.Wrapper

plugins {
    kotlin("multiplatform") version "2.2.21" apply false
    kotlin("android") version "2.2.21" apply false
    id("com.android.library") version "8.10.1" apply false
    id("com.android.application") version "8.10.1" apply false
}

val verifyFixtureCatalog by tasks.registering(Exec::class) {
    commandLine(providers.environmentVariable("PYTHON_BIN").orElse("python3").get(),
        rootProject.file("tools/validate_fixture_catalog.py").absolutePath)
}

tasks.register("verifyPrototype") {
    group = "verification"
    description = "Verify the C1 common-code contract prototype on JVM."
    dependsOn(":canvas-core:jvmTest", ":shared-core:jvmTest")
    dependsOn(verifyFixtureCatalog)
    doLast {
        val inputs = fileTree("canvas-core/src") { include("**/*.kt") }.files +
            fileTree("shared-core/src") { include("**/*.kt") }.files +
            fileTree("fixtures") { include("**/*.json") }.files +
            fileTree("tools") { include("*.py") }.files + listOf(
            file("settings.gradle.kts"), file("build.gradle.kts"),
            file("canvas-core/build.gradle.kts"), file("shared-core/build.gradle.kts"), file("gradle.properties"), file("ci/verify.sh"),
        ) + fileTree(".generated-fixtures/scale") { include("*.bin", "verified-manifest.json") }.files
        val testXml = fileTree("canvas-core/build/test-results/jvmTest") { include("TEST-*.xml") }.files +
            fileTree("shared-core/build/test-results/jvmTest") { include("TEST-*.xml") }.files
        check(testXml.isNotEmpty()) { "Expected real JVM test XML before stamping verification" }
        // Text inputs are digested with CRLF normalized to LF so the stamp is
        // reproducible on a Windows checkout; binary fixtures stay byte-exact.
        val textExtensions = setOf("kt", "kts", "py", "json", "md", "sh", "properties", "xml")
        val records = (inputs + testXml).sortedBy { it.relativeTo(rootDir).invariantSeparatorsPath }.map { input ->
            val bytes = input.readBytes()
            val normalized = if (input.extension.lowercase() in textExtensions) {
                String(bytes, Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
            } else bytes
            val sha = MessageDigest.getInstance("SHA-256").digest(normalized)
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
