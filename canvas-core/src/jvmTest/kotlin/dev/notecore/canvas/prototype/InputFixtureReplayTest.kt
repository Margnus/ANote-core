package dev.notecore.canvas.prototype

import java.security.MessageDigest
import kotlinx.serialization.json.*
import kotlin.test.*

/** JSON exists only at the JVM fixture boundary; commonMain knows neither codecs nor files. */
class InputFixtureReplayTest {
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.long
    private fun JsonObject.points(key: String) = getValue(key).jsonArray.map { element ->
        val p = element.jsonObject
        require(p.getValue("tilt_radians").jsonPrimitive.double == 0.0) // draft has no tilt channel yet
        InputSample(p.getValue("x").jsonPrimitive.double, p.getValue("y").jsonPrimitive.double,
            p.getValue("pressure").jsonPrimitive.float, p.long("time_us"))
    }
    private fun resource(name: String) = checkNotNull(javaClass.classLoader.getResourceAsStream(name)).use { it.readBytes() }

    @Test fun jsonFixturesReplayThroughCommonInputAndSceneAuthority() {
        // The manifest describes canonical LF JSON, as does validate_fixture_catalog.py.
        // Windows checkouts may still contain CRLF before a fresh checkout applies .gitattributes.
        val bytes = resource("synthetic-traces.json").decodeToString().replace("\r\n", "\n").encodeToByteArray()
        val manifest = Json.parseToJsonElement(resource("manifest.json").decodeToString()).jsonObject
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(manifest.text("sha256"), digest)
        val doc = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        assertEquals("0.1-draft", doc.text("schema_version")); assertEquals("synthetic", doc.text("origin"))
        assertEquals(JsonNull, doc["measured_device"]); assertEquals(JsonNull, doc["measured_latency_us"])
        val units = doc.getValue("units").jsonObject
        assertEquals("scene-point", units.text("coordinates")); assertEquals("microsecond", units.text("time"))
        assertEquals("normalized-0-to-1", units.text("pressure")); assertEquals("stylus-only", doc.text("input_policy"))
        val cases = doc.getValue("cases").jsonArray
        assertEquals(manifest.long("cases").toInt(), cases.size)
        for (item in cases) {
            val case = item.jsonObject
            val capture = case.getValue("target_at_down").jsonObject
            val brush = capture.getValue("brush").jsonObject
            assertEquals("monoline", brush.text("kind"))
            val matrix = case.getValue("coordinate_snapshot").jsonObject
            for ((key, value) in mapOf("a" to 1.0, "b" to 0.0, "c" to 0.0, "d" to 1.0, "tx" to 0.0, "ty" to 0.0))
                assertEquals(value, matrix.getValue(key).jsonPrimitive.double) // fixtures are already in scene space
            var current = StrokeTarget(SceneId(capture.text("scene_id")), LayerId(capture.text("layer_id")),
                BrushSpec(BrushKind.MONOLINE, brush.long("algorithm_version").toInt(), brush.getValue("width").jsonPrimitive.double, -1))
            val input = InputReplaySession()
            val authority = MemorySceneCoordinator(SceneSnapshot.empty(current.sceneId))
            val submissions = StrokeSubmissionQueue(current.sceneId)
            var nextSubmission = 0L
            fun submitPending() {
                while (submissions.unconfirmedEntries > 0) {
                    val attempt = assertIs<DispatchResult.Ready>(submissions.dispatch(authority.snapshot())).attempt
                    assertEquals(CompletionUpdate.UPDATED, submissions.complete(attempt, authority.commit(attempt.patch)))
                    val collected = assertNotNull(submissions.collectConfirmed(attempt.id))
                    assertEquals(Durability.MEMORY_ONLY, collected.second.durability)
                }
            }
            var lastEventTime = -1L
            case.getValue("events").jsonArray.forEachIndexed { index, raw ->
                val event = raw.jsonObject
                assertEquals(index.toLong(), event.long("sequence"))
                assertTrue(event.long("time_us") >= lastEventTime); lastEventTime = event.long("time_us")
                when (event.text("type")) {
                    "pointer" -> {
                        val result = input.consume(InputBatch(event.long("sequence"), event.long("time_us"), event.long("pointer_id"),
                            when (event.text("tool")) { "stylus" -> PointerTool.STYLUS; "finger" -> PointerTool.FINGER; else -> error("Unknown tool") },
                            PointerPhase.valueOf(event.text("phase").uppercase()), event.points("real_samples"), event.points("predicted_samples")), current)
                        assertFalse(result is InputResult.Rejected, "${case.text("id")}: $result")
                        if (result is InputResult.Completed) {
                            val sequence = nextSubmission++
                            assertIs<AdmissionResult.Admitted>(submissions.enqueue(StrokeSubmission(
                                CommandId("op-$sequence"), ObjectId("stroke-$sequence"), result.stroke)))
                        }
                    }
                    "select_layer" -> current = current.copy(layerId = LayerId(event.text("layer_id")))
                    "complete_pending_commit" -> submitPending()
                    else -> error("Unknown event type")
                }
            }
            submitPending(); input.close()
            assertEquals(0L, submissions.chargedBytes); assertEquals(0, submissions.retainedEntries)
            val expected = case.getValue("expected").jsonObject
            val objects = authority.snapshot().objects()
            assertEquals(expected.long("completed_strokes").toInt(), objects.size, case.text("id"))
            assertEquals(expected.long("committed_real_samples").toInt(), objects.sumOf { it.samples.size }, case.text("id"))
            for (stroke in objects) assertEquals(expected.text("committed_layer"), stroke.layerId.value)
            assertEquals(false, expected.getValue("prediction_persisted").jsonPrimitive.boolean)
            // Unique point coordinates verify exact real-point preservation, not merely the point count.
            if (objects.isNotEmpty()) {
                val real = case.getValue("events").jsonArray.map { it.jsonObject }
                    .filter { it.text("type") == "pointer" && it.text("tool") == "stylus" }.flatMap { it.points("real_samples") }
                val samples = objects.single().samples
                real.forEachIndexed { i, p ->
                    assertEquals(p.x, samples.x(i)); assertEquals(p.y, samples.y(i))
                    assertEquals(p.pressure, samples.pressure(i)); assertEquals(p.timeMicros, samples.timeMicros(i))
                }
            }
        }
    }
}
