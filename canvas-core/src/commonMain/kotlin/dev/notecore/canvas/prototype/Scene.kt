package dev.notecore.canvas.prototype

/** A small immutable reference snapshot. Map copying is not the planned production store. */
class SceneSnapshot internal constructor(val id: SceneId, val revision: Long, objects: Map<ObjectId, StrokeObject>) {
    private val entries = objects.toMap()
    val objectCount: Int get() = entries.size
    fun find(id: ObjectId): StrokeObject? = entries[id]
    fun objects(): List<StrokeObject> = entries.values.toList()
    internal fun copyEntries(): MutableMap<ObjectId, StrokeObject> = entries.toMutableMap()
    companion object { fun empty(id: SceneId): SceneSnapshot = SceneSnapshot(id, 0, emptyMap()) }
}

sealed interface CanvasCommand {
    val id: CommandId
    val sceneId: SceneId
    val baseRevision: Long

    data class AddStroke(
        override val id: CommandId, override val baseRevision: Long,
        val target: StrokeTarget, val objectId: ObjectId, val samples: StrokeSamples,
    ) : CanvasCommand { override val sceneId: SceneId get() = target.sceneId }

    data class TransformStroke(
        override val id: CommandId, override val sceneId: SceneId, override val baseRevision: Long,
        val objectId: ObjectId, val expectedObjectRevision: Long, val transform: Affine2D,
    ) : CanvasCommand

    data class DeleteStroke(
        override val id: CommandId, override val sceneId: SceneId, override val baseRevision: Long,
        val objectId: ObjectId, val expectedObjectRevision: Long,
    ) : CanvasCommand
}

data class ObjectChange(val before: StrokeObject?, val after: StrokeObject?)

class ScenePatch internal constructor(
    val commandId: CommandId, val sceneId: SceneId, val baseRevision: Long,
    changes: List<ObjectChange>, val dirtyBounds: BoundsD,
) {
    private val items = changes.toList()
    fun changes(): List<ObjectChange> = items.toList()
    internal fun sameContent(other: ScenePatch): Boolean = sceneId == other.sceneId &&
        baseRevision == other.baseRevision && dirtyBounds == other.dirtyBounds && items == other.items
}

enum class Rejection {
    WRONG_SCENE, STALE_SCENE, MISSING_OBJECT, DUPLICATE_OBJECT, STALE_OBJECT,
    INVALID_GEOMETRY, REVISION_EXHAUSTED, REUSED_COMMAND_ID, JOURNAL_FULL,
}
sealed interface PrepareResult {
    data class Prepared(val patch: ScenePatch) : PrepareResult
    data class Rejected(val reason: Rejection) : PrepareResult
}

object SceneReducer {
    fun prepare(scene: SceneSnapshot, command: CanvasCommand): PrepareResult {
        if (command.sceneId != scene.id) return PrepareResult.Rejected(Rejection.WRONG_SCENE)
        if (command.baseRevision != scene.revision) return PrepareResult.Rejected(Rejection.STALE_SCENE)
        if (scene.revision == Long.MAX_VALUE) return PrepareResult.Rejected(Rejection.REVISION_EXHAUSTED)
        try {
            val change = when (command) {
                is CanvasCommand.AddStroke -> {
                    if (scene.find(command.objectId) != null) return PrepareResult.Rejected(Rejection.DUPLICATE_OBJECT)
                    ObjectChange(null, StrokeObject(command.objectId, 1, command.target.layerId, command.target.brush, command.samples))
                }
                is CanvasCommand.TransformStroke -> {
                    val old = scene.find(command.objectId) ?: return PrepareResult.Rejected(Rejection.MISSING_OBJECT)
                    if (old.objectRevision != command.expectedObjectRevision) return PrepareResult.Rejected(Rejection.STALE_OBJECT)
                    if (old.objectRevision == Long.MAX_VALUE) return PrepareResult.Rejected(Rejection.REVISION_EXHAUSTED)
                    if (command.transform.inverseOrNull() == null) return PrepareResult.Rejected(Rejection.INVALID_GEOMETRY)
                    ObjectChange(old, old.copy(objectRevision = old.objectRevision + 1, transform = command.transform))
                }
                is CanvasCommand.DeleteStroke -> {
                    val old = scene.find(command.objectId) ?: return PrepareResult.Rejected(Rejection.MISSING_OBJECT)
                    if (old.objectRevision != command.expectedObjectRevision) return PrepareResult.Rejected(Rejection.STALE_OBJECT)
                    ObjectChange(old, null)
                }
            }
            val dirty = change.before?.bounds()?.let { old -> change.after?.bounds()?.let(old::union) ?: old }
                ?: change.after!!.bounds()
            return PrepareResult.Prepared(ScenePatch(command.id, scene.id, scene.revision, listOf(change), dirty))
        } catch (_: IllegalArgumentException) {
            return PrepareResult.Rejected(Rejection.INVALID_GEOMETRY)
        }
    }
}

enum class Durability { MEMORY_ONLY }
data class CommitReceipt(val commandId: CommandId, val sceneRevision: Long, val durability: Durability)
sealed interface CommitResult {
    data class Accepted(val receipt: CommitReceipt) : CommitResult
    data class Rejected(val reason: Rejection) : CommitResult
}

/** Single-owner C1 host reference. Does not implement disk durability, multithreading or undo. */
class MemorySceneCoordinator(initial: SceneSnapshot, private val journalCapacity: Int = 1_000) {
    private var current = initial
    private val journal = mutableMapOf<CommandId, Pair<ScenePatch, CommitReceipt>>()
    init { require(journalCapacity > 0) }
    fun snapshot(): SceneSnapshot = current

    fun commit(patch: ScenePatch): CommitResult {
        journal[patch.commandId]?.let { (acceptedPatch, receipt) ->
            return if (acceptedPatch.sameContent(patch)) CommitResult.Accepted(receipt)
            else CommitResult.Rejected(Rejection.REUSED_COMMAND_ID)
        }
        if (patch.sceneId != current.id) return CommitResult.Rejected(Rejection.WRONG_SCENE)
        if (patch.baseRevision != current.revision) return CommitResult.Rejected(Rejection.STALE_SCENE)
        if (journal.size >= journalCapacity) return CommitResult.Rejected(Rejection.JOURNAL_FULL)
        if (current.revision == Long.MAX_VALUE) return CommitResult.Rejected(Rejection.REVISION_EXHAUSTED)
        val next = current.copyEntries()
        for (change in patch.changes()) {
            val id = change.after?.id ?: change.before!!.id
            if (next[id] != change.before) return CommitResult.Rejected(Rejection.STALE_OBJECT)
            if (change.after == null) next.remove(id) else next[id] = change.after
        }
        val snapshot = SceneSnapshot(current.id, current.revision + 1, next)
        val receipt = CommitReceipt(patch.commandId, snapshot.revision, Durability.MEMORY_ONLY)
        journal[patch.commandId] = patch to receipt
        current = snapshot
        return CommitResult.Accepted(receipt)
    }
}
