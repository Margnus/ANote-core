package dev.notecore.canvas.prototype

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Deterministic bridge fixture: shared output contours vs independent analytic oracle.
 * Native consumers exercise their own real path rasterizers, not a copy of our point-in-polygon.
 */
object BrushPathFixtures {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.single())
        data class Case(val name: String, val kind: BrushKind, val points: List<Pair<Double, Double>>, val transform: Affine2D = Affine2D(), val argb: Int = -1)
        val cases = listOf(
            Case("monoline-corners", BrushKind.MONOLINE, listOf(10.0 to 10.0, 45.0 to 10.0, 12.0 to 44.0, 45.0 to 44.0)),
            Case("pressure-taper", BrushKind.PRESSURE, listOf(8.0 to 10.0, 50.0 to 25.0, 15.0 to 42.0)),
            Case("highlighter-reverse", BrushKind.HIGHLIGHTER, listOf(10.0 to 16.0, 50.0 to 16.0, 10.0 to 16.0, 50.0 to 36.0, 10.0 to 36.0)),
            Case("pressure-contained", BrushKind.PRESSURE, listOf(25.0 to 25.0, 25.0 to 25.0, 26.0 to 25.0)),
            Case("pressure-affine-reflection", BrushKind.PRESSURE, listOf(10.0 to 10.0, 42.0 to 20.0, 20.0 to 42.0), Affine2D(a = -0.8, b = 0.2, c = 0.25, d = 1.0, tx = 55.0)),
            Case("highlighter-self-cross", BrushKind.HIGHLIGHTER, listOf(12.0 to 12.0, 48.0 to 48.0, 12.0 to 48.0, 48.0 to 12.0), argb = 0x80ffff00.toInt()),
        )
        val output = cases.map { c ->
            val n = c.points.size
            val samples = StrokeSamples.copyOf(c.points.map { it.first }.toDoubleArray(), c.points.map { it.second }.toDoubleArray(),
                FloatArray(n) { (it % 3) / 2f }, LongArray(n) { it.toLong() })
            val brush = BrushSpec(c.kind, 1, 10.0, c.argb)
            val path = (BrushPathPoc.build(samples, brush, c.transform, sceneTolerance = 0.01) as BrushPathBuild.Ready).path
            val oracle = (BrushEnvelopePoc.build(samples, brush, c.transform) as BrushEnvelopeBuild.Ready).envelope
            // Exclude a 1.2px neighborhood of boundaries; both AA on/off consumers
            // must agree in stable regions. This is NOT an edge/AA equivalence claim.
            val rows = (0 until 64).map { y -> buildString {
                for (x in 0 until 64) {
                    val center = PointD(x + 0.5, y + 0.5); val expected = oracle.containsScene(center)
                    val stable = listOf(-1.2, 0.0, 1.2).all { dx -> listOf(-1.2, 0.0, 1.2).all { dy -> oracle.containsScene(PointD(center.x + dx, center.y + dy)) == expected } }
                    if (stable) check(path.covers(center) == expected) { "${c.name} $center" }
                    append(if (!stable) '?' else if (expected) '#' else '.')
                }
            } }
            check(rows.sumOf { it.count { ch -> ch == '#' } } > 10)
            check(rows.sumOf { it.count { ch -> ch == '.' } } > 1000)
            buildJsonObject {
                put("name", c.name); put("kind", c.kind.name); put("alpha", path.sourceAlpha)
                put("vertices", path.vertexCount); put("logical_bytes", path.logicalBytes)
                put("source_samples", samples.size); put("scene_tolerance", path.maxSceneDeviation)
                putJsonArray("contours") { for (contour in 0 until path.contourCount) addJsonArray {
                    for (i in path.contourStart(contour) until path.contourEnd(contour)) addJsonArray { add(path.x(i)); add(path.y(i)) }
                } }
                putJsonArray("oracle_mask") { rows.forEach { add(it) } }
            }
        }
        val document = buildJsonObject {
            put("schema", "brush-path-native-poc-1"); put("rule", "swept-discs-draft-1"); put("fill_rule", "nonzero-once")
            put("width", 64); put("height", 64); put("physical_device_measured", false)
            put("scope", "Shared contour output vs analytic coverage at stable pixel centers; '?' excludes edge/AA comparison")
            putJsonObject("source_sha256") {
                listOf("canvas-core/src/commonMain/kotlin/dev/notecore/canvas/prototype/BrushPathPoc.kt",
                    "canvas-core/src/commonMain/kotlin/dev/notecore/canvas/prototype/BrushEnvelopePoc.kt",
                    "canvas-core/src/commonMain/kotlin/dev/notecore/canvas/prototype/Geometry.kt",
                    "canvas-core/src/commonMain/kotlin/dev/notecore/canvas/prototype/Stroke.kt",
                    "canvas-core/src/commonTest/kotlin/dev/notecore/canvas/prototype/BrushPathPocTest.kt",
                    "canvas-core/src/jvmTest/kotlin/dev/notecore/canvas/prototype/BrushPathFixtures.kt").forEach { name ->
                    val bytes = File(root, name).readText(Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
                    put(name, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                }
            }
            put("cases", JsonArray(output))
        }
        File(root, "fixtures/geometry/brush-paths.json").writeText(Json.encodeToString(JsonObject.serializer(), document) + "\n", Charsets.UTF_8)
        println("Generated 6 common contour/oracle fixtures for native nonzero-winding offscreen tests")
    }
}
