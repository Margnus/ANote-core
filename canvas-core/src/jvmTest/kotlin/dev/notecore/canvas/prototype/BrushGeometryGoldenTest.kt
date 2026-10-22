package dev.notecore.canvas.prototype

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Checked-in specification values are read only. The builder never generates its own expectations. */
class BrushGeometryGoldenTest {
    private fun resource(name: String): String = checkNotNull(javaClass.classLoader.getResourceAsStream("brush/$name"))
        .use { it.readBytes().decodeToString().replace("\r\n", "\n") }
    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content
    private fun JsonArray.double(index: Int): Double = get(index).jsonPrimitive.double

    @Test fun versionTwoMatchesAllStoredVerticesContoursColorsAndBoundsAfterManifestVerification() {
        val manifest = Json.parseToJsonElement(resource("brush-manifest.json")).jsonObject
        val text = resource(manifest.text("file"))
        val digest = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(manifest.text("sha256"), digest)
        val catalog = Json.parseToJsonElement(text).jsonObject
        assertEquals("synthetic-specification-golden", catalog.text("origin"))
        assertEquals(BrushGeometryBuilder.ALGORITHM_VERSION, catalog.getValue("algorithm_version").jsonPrimitive.int)
        val cases = catalog.getValue("cases").jsonArray
        assertEquals(manifest.getValue("cases").jsonPrimitive.int, cases.size)
        assertEquals(6, cases.map { it.jsonObject.text("id") }.toSet().size)
        val tolerance = manifest.getValue("numeric_absolute_tolerance").jsonPrimitive.double
        assertEquals(1e-9, tolerance)
        val actual = mutableListOf<JsonObject>()
        for (element in cases) {
            val case = element.jsonObject; val brush = case.getValue("brush").jsonObject
            val rows = case.getValue("samples").jsonArray.map { it.jsonArray }
            val samples = StrokeSamples.copyOf(rows.map { it.double(0) }.toDoubleArray(), rows.map { it.double(1) }.toDoubleArray(),
                rows.map { it[2].jsonPrimitive.float }.toFloatArray(), rows.map { it[3].jsonPrimitive.long }.toLongArray())
            val spec = BrushSpec(BrushKind.valueOf(brush.text("kind")), brush.getValue("algorithm_version").jsonPrimitive.int,
                brush.getValue("width").jsonPrimitive.double, brush.getValue("argb").jsonPrimitive.int)
            val geometry = assertIs<BrushGeometryResult.Ready>(BrushGeometryBuilder.build(samples, spec), case.text("id")).geometry
            val expected = case.getValue("expected").jsonObject; val origin = expected.getValue("origin").jsonArray
            assertEquals(origin.double(0), geometry.origin.x, tolerance); assertEquals(origin.double(1), geometry.origin.y, tolerance)
            assertEquals(expected.getValue("argb").jsonPrimitive.int, geometry.argb)
            assertEquals(expected.text("fill_rule"), geometry.fillRule.name); assertEquals(expected.text("blend_mode"), geometry.blendMode.name)
            val offsets = expected.getValue("contour_offsets").jsonArray.map { it.jsonPrimitive.int }
            assertEquals(offsets.size - 1, geometry.contourCount)
            for (i in 0 until geometry.contourCount) {
                assertEquals(offsets[i], geometry.contourStart(i)); assertEquals(offsets[i + 1], geometry.contourEnd(i))
            }
            val vertices = expected.getValue("vertices").jsonArray
            assertEquals(vertices.size, geometry.vertexCount)
            for (i in vertices.indices) {
                val point = vertices[i].jsonArray
                assertEquals(point.double(0), geometry.x(i), tolerance, "${case.text("id")} vertex $i x")
                assertEquals(point.double(1), geometry.y(i), tolerance, "${case.text("id")} vertex $i y")
            }
            val bounds = expected.getValue("bounds").jsonArray
            val measured = listOf(geometry.bounds.left, geometry.bounds.top, geometry.bounds.right, geometry.bounds.bottom)
            for (i in measured.indices) assertEquals(bounds.double(i), measured[i], tolerance)
            actual.add(buildJsonObject {
                put("id", case.text("id")); put("argb", geometry.argb); put("fill_rule", geometry.fillRule.name)
                put("blend_mode", geometry.blendMode.name); put("source_points", geometry.sourcePointCount)
                putJsonArray("origin") { add(geometry.origin.x); add(geometry.origin.y) }
                putJsonArray("bounds") { measured.forEach { add(it) } }
                putJsonArray("contour_offsets") { for (i in 0 until geometry.contourCount) add(geometry.contourStart(i)); add(geometry.vertexCount) }
                putJsonArray("vertices") { for (i in 0 until geometry.vertexCount) add(buildJsonArray { add(geometry.x(i)); add(geometry.y(i)) }) }
            })
        }
        // Inspectable actual numeric output; ignored build artifact, never a new expected fixture.
        System.getProperty("notecore.brush.actual")?.let { path ->
            File(path).apply { parentFile.mkdirs(); writeText(Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(actual))) }
        }
    }
}
