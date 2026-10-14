package dev.notecore.canvas.prototype

/** Content identity and presentation identity are deliberately separate. */
data class FrameToken(
    val sceneId: SceneId, val sceneRevision: Long, val viewportVersion: Long,
    val sessionGeneration: Long, val surfaceGeneration: Long, val requestSequence: Long,
) {
    init {
        require(sceneRevision >= 0 && viewportVersion >= 0 && sessionGeneration >= 0 &&
            surfaceGeneration >= 0 && requestSequence >= 0)
    }
}

/** Single surface-owner gate. Actual buffer disposal/leases are deferred to the backend phase. */
class FramePublicationGate(initial: FrameToken) {
    private var expected: FrameToken? = initial
    fun expect(token: FrameToken): Boolean {
        val old = expected ?: return false
        if (token == old) return true
        if (token.sessionGeneration < old.sessionGeneration) return false
        if (token.sessionGeneration == old.sessionGeneration) {
            if (token.surfaceGeneration < old.surfaceGeneration) return false
            if (token.surfaceGeneration == old.surfaceGeneration) {
                if (token.requestSequence <= old.requestSequence || token.viewportVersion < old.viewportVersion) return false
                if (token.sceneId == old.sceneId && token.sceneRevision < old.sceneRevision) return false
            }
        }
        expected = token
        return true
    }
    fun canPublish(token: FrameToken): Boolean = expected == token
    fun close() { expected = null }
}

data class GeometryCacheKey(
    val sceneId: SceneId, val objectId: ObjectId, val objectRevision: Long,
    val brushKind: BrushKind, val algorithmVersion: Int,
) {
    init { require(objectRevision > 0 && algorithmVersion > 0) }
}
