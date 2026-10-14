package dev.notecore.canvas.prototype

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PresentationContractTest {
    private val initial = FrameToken(SceneId("page-a"), 3, 4, 1, 1, 10)

    @Test fun intermediateContentChangeInvalidatesOldFrame() {
        val gate = FramePublicationGate(initial)
        val latest = initial.copy(sceneRevision = 4, requestSequence = 11)
        assertTrue(gate.expect(latest))
        assertFalse(gate.canPublish(initial))
        assertTrue(gate.canPublish(latest))
    }

    @Test fun detachedSurfaceCannotAcceptLateFrameOrBeReopened() {
        val gate = FramePublicationGate(initial)
        gate.close(); gate.close()
        assertFalse(gate.canPublish(initial))
        assertFalse(gate.expect(initial.copy(surfaceGeneration = 2)))
    }

    @Test fun viewportSceneSessionAndSurfaceAllInvalidateEarlierResults() {
        val replacements = listOf(
            initial.copy(viewportVersion = 5, requestSequence = 11),
            initial.copy(sceneId = SceneId("page-b"), requestSequence = 11),
            initial.copy(sessionGeneration = 2, requestSequence = 0),
            initial.copy(surfaceGeneration = 2, requestSequence = 0),
        )
        replacements.forEach {
            val gate = FramePublicationGate(initial)
            assertTrue(gate.expect(it)); assertFalse(gate.canPublish(initial)); assertTrue(gate.canPublish(it))
        }
    }

    @Test fun oldRequestCannotRegressCurrentExpectation() {
        val gate = FramePublicationGate(initial)
        val latest = initial.copy(requestSequence = 20)
        assertTrue(gate.expect(latest))
        assertFalse(gate.expect(initial))
        assertFalse(gate.expect(latest.copy(sceneRevision = 2, requestSequence = 21)))
        assertTrue(gate.canPublish(latest))
    }

    @Test fun geometryCacheIncludesObjectAndAlgorithmRevision() {
        val first = GeometryCacheKey(SceneId("page-a"), ObjectId("middle-object"), 1, BrushKind.PRESSURE, 1)
        assertNotEquals(first, first.copy(objectRevision = 2))
        assertNotEquals(first, first.copy(algorithmVersion = 2))
    }

    @Test fun repeatedLocalObjectIdsAcrossHostsCannotShareGeometry() {
        val key = GeometryCacheKey(SceneId("host-a-scene"), ObjectId("local-1"), 1, BrushKind.MONOLINE, 1)
        assertNotEquals(key, key.copy(sceneId = SceneId("host-b-scene")))
    }
}
