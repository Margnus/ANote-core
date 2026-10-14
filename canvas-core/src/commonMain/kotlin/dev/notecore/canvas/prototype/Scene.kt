package dev.notecore.canvas.prototype

/** A small immutable reference snapshot. Map copying is not the planned production store. */
class SceneSnapshot private constructor(val id: SceneId, val revision: Long, private val entries: Map<ObjectId, StrokeObject>, trusted: Unit) {
    internal constructor(id: SceneId, revision: Long, objects: Map<ObjectId, StrokeObject>) : this(id, revision, objects.toMap(), Unit)
    val objectCount: Int get() = entries.size
    fun find(id: ObjectId): StrokeObject? = entries[id]
    fun objects(): List<StrokeObject> = entries.values.toList()
    internal fun copyEntries(): MutableMap<ObjectId, StrokeObject> = entries.toMutableMap()
    companion object {
        fun empty(id: SceneId): SceneSnapshot = SceneSnapshot(id, 0, emptyMap(), Unit)
        /** Validated codec/fixture entry, not a mutable authority or a claim of persisted durability. */
        fun restored(id: SceneId, revision: Long, objects: List<StrokeObject>): SceneSnapshot {
            require(revision >= 0 && objects.size <= 100_000)
            require(objects.map { it.id }.toSet().size == objects.size)
            for (obj in objects) { require(obj.transform.inverseOrNull() != null); obj.bounds() }
            return SceneSnapshot(id, revision, objects.associateBy { it.id }, Unit)
        }
        internal fun applying(scene: SceneSnapshot, changes: List<ObjectChange>): SceneSnapshot {
            // Metadata barriers share the privately owned immutable map; object edits copy it once.
            if (changes.isEmpty()) return SceneSnapshot(scene.id, scene.revision + 1, scene.entries, Unit)
            val next = scene.entries.toMutableMap()
            for (change in changes) {
                val id = change.after?.id ?: change.before!!.id
                if (change.after == null) next.remove(id) else next[id] = change.after
            }
            return SceneSnapshot(scene.id, scene.revision + 1, next, Unit) // next has no external mutable alias.
        }
    }
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
    fun sameContent(other: ScenePatch): Boolean = sceneId == other.sceneId &&
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

sealed interface SceneApplication {
    data class Applied(val snapshot: SceneSnapshot) : SceneApplication
    data class Rejected(val reason: Rejection) : SceneApplication
}

object SceneReducer {
    /** Pure composition for an outer document authority; never owns mutable scene state or a receipt journal. */
    fun prepareChanges(scene: SceneSnapshot, commandId: CommandId, changes: List<ObjectChange>,
                       metadataDirtyBounds: BoundsD? = null): PrepareResult {
        if (scene.revision == Long.MAX_VALUE) return PrepareResult.Rejected(Rejection.REVISION_EXHAUSTED)
        var dirty = metadataDirtyBounds
        val seen = mutableSetOf<ObjectId>()
        try {
            for (change in changes) {
                val id = change.after?.id ?: change.before?.id ?: return PrepareResult.Rejected(Rejection.INVALID_GEOMETRY)
                if (!seen.add(id) || (change.before != null && change.after != null && change.before.id != change.after.id))
                    return PrepareResult.Rejected(Rejection.INVALID_GEOMETRY)
                if (scene.find(id) != change.before) return PrepareResult.Rejected(Rejection.STALE_OBJECT)
                if (change.before != null && change.after != null && change.after.objectRevision <= change.before.objectRevision)
                    return PrepareResult.Rejected(Rejection.STALE_OBJECT)
                change.before?.bounds()?.let { dirty = dirty?.union(it) ?: it }
                change.after?.bounds()?.let { dirty = dirty?.union(it) ?: it }
            }
            return PrepareResult.Prepared(ScenePatch(commandId, scene.id, scene.revision, changes, dirty ?: BoundsD(0.0, 0.0, 0.0, 0.0)))
        } catch (_: IllegalArgumentException) { return PrepareResult.Rejected(Rejection.INVALID_GEOMETRY) }
    }
    fun apply(scene: SceneSnapshot, patch: ScenePatch): SceneApplication {
        if (patch.sceneId != scene.id) return SceneApplication.Rejected(Rejection.WRONG_SCENE)
        if (patch.baseRevision != scene.revision) return SceneApplication.Rejected(Rejection.STALE_SCENE)
        if (scene.revision == Long.MAX_VALUE) return SceneApplication.Rejected(Rejection.REVISION_EXHAUSTED)
        val changes = patch.changes(); val seen = mutableSetOf<ObjectId>()
        for (change in changes) {
            val id = change.after?.id ?: change.before?.id ?: return SceneApplication.Rejected(Rejection.INVALID_GEOMETRY)
            if (!seen.add(id) || (change.before != null && change.after != null && change.before.id != change.after.id))
                return SceneApplication.Rejected(Rejection.INVALID_GEOMETRY)
            if (scene.find(id) != change.before) return SceneApplication.Rejected(Rejection.STALE_OBJECT)
            if (change.before != null && change.after != null && change.after.objectRevision <= change.before.objectRevision)
                return SceneApplication.Rejected(Rejection.STALE_OBJECT)
        }
        // ScenePatch's internal constructor is reached through validating preparation, with immutable payloads.
        // Recheck CAS / object headers, but do not rescan real samples to recompute already validated bounds.
        return SceneApplication.Applied(SceneSnapshot.applying(scene, changes))
    }

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
        val application = SceneReducer.apply(current, patch)
        if (application is SceneApplication.Rejected) return CommitResult.Rejected(application.reason)
        val snapshot = (application as SceneApplication.Applied).snapshot
        val receipt = CommitReceipt(patch.commandId, snapshot.revision, Durability.MEMORY_ONLY)
        journal[patch.commandId] = patch to receipt
        current = snapshot
        return CommitResult.Accepted(receipt)
    }
}
