package dev.notecore.canvas.prototype

import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Streaming input conformance only. No render/index/store benchmark is implied. */
class ScaleFixtureReplayTest {
    @Test fun standardPageInkPreserves100000Points() = replay("S1-ink-current-page", 1000, 100)
    @Test fun densePageInkPreserves1000000Points() = replay("S2-ink-dense-page", 10000, 100)
    @Test fun longStrokePreserves100000Points() = replay("S9-ink-long-stroke", 1, 100000)

    private fun replay(id: String, expectedStrokes: Int, expectedPerStroke: Int) {
        val manifest = Json.parseToJsonElement(File(System.getProperty("notecore.scale.manifest")).readText()).jsonObject
        assertEquals("synthetic", manifest.getValue("origin").jsonPrimitive.content)
        assertFalse(manifest.getValue("device_performance_measured").jsonPrimitive.boolean)
        val profile = manifest.getValue("profiles").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == id }
        val file = File(System.getProperty("notecore.scale.fixture.dir"), profile.getValue("file").jsonPrimitive.content)
        assertEquals(profile.getValue("bytes").jsonPrimitive.long, file.length())
        val digest = MessageDigest.getInstance("SHA-256")
        val bounds = doubleArrayOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY)
        var total = 0L
        DataInputStream(DigestInputStream(file.inputStream().buffered(), digest)).use { stream ->
            val header = ByteArray(16); stream.readFully(header)
            assertEquals("NCSCALE1", header.copyOfRange(0, 8).decodeToString())
            val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(expectedStrokes, h.getInt(8)); assertEquals(expectedPerStroke, h.getInt(12))
            val bytes = ByteArray(28)
            val record = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            repeat(expectedStrokes) { stroke ->
                val target = StrokeTarget(SceneId(id), LayerId("layer-${stroke % 3}"), BrushSpec(BrushKind.MONOLINE, 1, 2.0, -1))
                val session = InputReplaySession(100000)
                val expected = ArrayList<InputSample>(expectedPerStroke)
                var sequence = 0L
                var completed: CompletedInput? = null
                val batch = ArrayList<InputSample>(32)
                repeat(expectedPerStroke) { index ->
                    stream.readFully(bytes)
                    val p = InputSample(record.getDouble(0), record.getDouble(8), record.getFloat(16), record.getLong(20))
                    assertEquals(index * 4167L, p.timeMicros)
                    bounds[0] = minOf(bounds[0], p.x); bounds[1] = minOf(bounds[1], p.y)
                    bounds[2] = maxOf(bounds[2], p.x); bounds[3] = maxOf(bounds[3], p.y)
                    expected.add(p); batch.add(p); total++
                    if (index == 0 || batch.size == 32 || index == expectedPerStroke - 1) {
                        val phase = when (index) { 0 -> PointerPhase.DOWN; expectedPerStroke - 1 -> PointerPhase.UP; else -> PointerPhase.MOVE }
                        val result = session.consume(InputBatch(sequence++, p.timeMicros, 1, PointerTool.STYLUS, phase, batch), target)
                        assertFalse(result is InputResult.Rejected, "$id stroke=$stroke point=$index $result")
                        if (phase == PointerPhase.UP) completed = assertIs<InputResult.Completed>(result).stroke
                        batch.clear()
                    }
                }
                val accepted = assertNotNull(completed)
                assertEquals(target, accepted.target); assertEquals(expectedPerStroke, accepted.samples.size)
                expected.forEachIndexed { index, p ->
                    assertEquals(p.x, accepted.samples.x(index)); assertEquals(p.y, accepted.samples.y(index))
                    assertEquals(p.pressure, accepted.samples.pressure(index)); assertEquals(p.timeMicros, accepted.samples.timeMicros(index))
                }
                session.close()
            }
            assertEquals(-1, stream.read()) // truncation throws, trailing records also fail
        }
        assertEquals(profile.getValue("total_points").jsonPrimitive.long, total)
        profile.getValue("bounds_scene_points").jsonArray.forEachIndexed { i, value -> assertEquals(value.jsonPrimitive.double, bounds[i]) }
        assertEquals(profile.getValue("sha256").jsonPrimitive.content, digest.digest().joinToString("") { "%02x".format(it) })
    }
}
