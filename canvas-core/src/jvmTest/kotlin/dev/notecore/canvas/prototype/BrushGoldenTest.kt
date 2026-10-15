package dev.notecore.canvas.prototype

import kotlinx.serialization.json.*
import kotlin.test.*

class BrushGoldenTest {
    @Test fun threeBrushMasksMatchHandDerivedGoldenCoverage() {
        val text = javaClass.getResourceAsStream("/brush-golden.json")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val golden = Json.parseToJsonElement(text).jsonObject
        val cases = golden.getValue("cases").jsonArray
        assertEquals(3, cases.size)
        cases.forEach { value ->
            val c = value.jsonObject; val id = c.getValue("id").jsonPrimitive.content
            val points = c.getValue("points").jsonArray.map { it.jsonArray }
            val samples = StrokeSamples.copyOf(points.map { it[0].jsonPrimitive.double }.toDoubleArray(),
                points.map { it[1].jsonPrimitive.double }.toDoubleArray(),
                points.map { it[2].jsonPrimitive.float }.toFloatArray(), LongArray(points.size) { it.toLong() })
            val brush = BrushSpec(BrushKind.valueOf(c.getValue("brush").jsonPrimitive.content), 1, c.getValue("width").jsonPrimitive.double, -1)
            val envelope = assertIs<BrushEnvelopeBuild.Ready>(BrushEnvelopePoc.build(samples, brush)).envelope
            assertEquals(golden.getValue("rule_id").jsonPrimitive.content, envelope.ruleId)
            val origin = c.getValue("origin").jsonArray.map { it.jsonPrimitive.int }
            val alpha = c.getValue("alpha").jsonPrimitive.double
            c.getValue("mask").jsonArray.forEachIndexed { row, v ->
                v.jsonPrimitive.content.forEachIndexed { column, pixel ->
                    assertTrue(pixel == '.' || pixel == '#')
                    val p = PointD((origin[0] + column).toDouble(), (origin[1] + row).toDouble())
                    assertEquals(pixel == '#', envelope.containsScene(p), "$id row=$row column=$column")
                    assertEquals(if (pixel == '#') alpha else 0.0, envelope.alphaAtScene(p), "$id alpha row=$row column=$column")
                }
            }
        }
    }
}
