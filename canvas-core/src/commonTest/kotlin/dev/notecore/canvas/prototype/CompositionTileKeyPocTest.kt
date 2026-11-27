package dev.notecore.canvas.prototype

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CompositionTileKeyPocTest {
    private val frame = FrameToken(SceneId("composition"), 1, 1, 1, 1, 1)
    private val grid = BrushTileGridPoc(PointD(0.0, 0.0), 32, 32, 1.0, 1)

    @Test fun unresolvedPixelsStayWithinTheirRequestAndResolvedPixelsKeepCanonicalOrder() {
        val next = frame.copy(requestSequence = 2)
        val unresolved = CompositionTileKeyPoc.capture(frame, grid, "cpu", listOf("image", null))
        assertEquals(unresolved, CompositionTileKeyPoc.capture(frame, grid, "cpu", listOf("image", null)))
        assertNotEquals(unresolved, CompositionTileKeyPoc.capture(next, grid, "cpu", listOf("image", null)))
        val resolved = CompositionTileKeyPoc.capture(frame, grid, "cpu", listOf("image", "text"))
        assertNotEquals(unresolved, resolved)
        assertEquals(resolved, CompositionTileKeyPoc.capture(next, grid, "cpu", listOf("image", "text")))
        assertNotEquals(resolved, CompositionTileKeyPoc.capture(next, grid, "cpu", listOf("text", "image")))
    }

    @Test fun captureKeepsTheOriginalDependenciesAndSeparatesOwnersAndConfigurations() {
        val dependencies = mutableListOf("image", "text")
        val resolved = CompositionTileKeyPoc.capture(frame, grid, "cpu", dependencies)
        dependencies[0] = "different-image"
        assertNotEquals(resolved, CompositionTileKeyPoc.capture(frame, grid, "cpu", dependencies))
        assertEquals(resolved, CompositionTileKeyPoc.capture(frame, grid, "cpu", listOf("image", "text")))
        assertNotEquals(resolved, CompositionTileKeyPoc.capture(frame.copy(sessionGeneration = 2),
            grid, "cpu", listOf("image", "text")))
        assertNotEquals(resolved, CompositionTileKeyPoc.capture(frame,
            grid.copy(origin = PointD(32.0, 0.0)), "cpu", listOf("image", "text")))
        assertNotEquals(resolved, CompositionTileKeyPoc.capture(frame, grid, "another-renderer", listOf("image", "text")))
    }
}
