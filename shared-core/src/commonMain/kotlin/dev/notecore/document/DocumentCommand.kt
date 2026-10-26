package dev.notecore.document

import dev.notecore.canvas.prototype.*

/** Unfrozen root operations. One document identity/base revision and one operation-id namespace. */
sealed interface DocumentCommand {
    val id: CommandId
    val documentId: SceneId
    val baseRevision: Long
    data class Undo(override val id: CommandId, override val documentId: SceneId, override val baseRevision: Long,
                    val expectedOperationId: CommandId) : DocumentCommand
    data class Redo(override val id: CommandId, override val documentId: SceneId, override val baseRevision: Long,
                    val expectedOperationId: CommandId) : DocumentCommand
    data class Rename(override val id: CommandId, override val documentId: SceneId, override val baseRevision: Long,
                      val title: String) : DocumentCommand
    data class InsertPage(override val id: CommandId, override val documentId: SceneId, override val baseRevision: Long,
                          val page: PageDescriptor, val before: PageId? = null) : DocumentCommand
    data class MovePage(override val id: CommandId, override val documentId: SceneId, override val baseRevision: Long,
                        val pageId: PageId, val before: PageId? = null) : DocumentCommand
    data class DeletePage(override val id: CommandId, override val documentId: SceneId, override val baseRevision: Long,
                          val pageId: PageId) : DocumentCommand
    data class SetPaper(override val id: CommandId, override val documentId: SceneId, override val baseRevision: Long,
                        val pageId: PageId, val width: Double, val height: Double, val paper: PaperKind) : DocumentCommand
    data class Canvas(val pageId: PageId, val command: CanvasCommand) : DocumentCommand {
        override val id: CommandId get() = command.id
        override val documentId: SceneId get() = command.sceneId
        override val baseRevision: Long get() = command.baseRevision
    }
    /** Prepared canvas-session patch keeps its original base and full payload through transport retries. */
    class PreparedCanvas(val pageId: PageId, val patch: ScenePatch) : DocumentCommand {
        override val id: CommandId get() = patch.commandId
        override val documentId: SceneId get() = patch.sceneId
        override val baseRevision: Long get() = patch.baseRevision
        override fun equals(other: Any?): Boolean = other is PreparedCanvas && id == other.id && pageId == other.pageId && patch.sameContent(other.patch)
        override fun hashCode(): Int = listOf(pageId, patch.commandId, patch.sceneId, patch.baseRevision, patch.dirtyBounds, patch.changes()).hashCode()
    }
    class CopyPage(override val id: CommandId, override val documentId: SceneId, override val baseRevision: Long,
                   val source: PageId, val expectedPageRevision: Long, val newPageId: PageId, val newLayerId: LayerId,
                   objectIds: Map<ObjectId, ObjectId>, val before: PageId? = null) : DocumentCommand {
        private val mapping = objectIds.also { require(it.size <= 100_000) }.toMap()
        val objectMappingCount: Int get() = mapping.size
        internal fun bindings(): Map<ObjectId, ObjectId> = mapping
        fun objectIds(): Map<ObjectId, ObjectId> = mapping.toMap()
        override fun equals(other: Any?): Boolean = other is CopyPage && id == other.id && documentId == other.documentId &&
            baseRevision == other.baseRevision && source == other.source && expectedPageRevision == other.expectedPageRevision &&
            newPageId == other.newPageId && newLayerId == other.newLayerId && mapping == other.mapping && before == other.before
        override fun hashCode(): Int = listOf(id, documentId, baseRevision, source, expectedPageRevision, newPageId, newLayerId, mapping, before).hashCode()
    }
}

data class DocumentBudget(val maxPages: Int = 128, val maxObjects: Int = 5000, val liveBytes: Long = 8L * 1024 * 1024,
                          val journalEntries: Int = 128, val journalBytes: Long = 8L * 1024 * 1024,
                          val identityBytes: Long = 2L * 1024 * 1024,
                          val historyEntries: Int = 128, val historyBytes: Long = 8L * 1024 * 1024,
                          /** Per-notebook active input reservation; independent of retained document/history quotas. */
                          val inputBytes: Long = 2L * 1024 * 1024) {
    init { require(maxPages in 1..4096 && maxObjects in 1..100_000 && liveBytes >= 0 && journalEntries in 1..16_384 && journalBytes >= 0 && identityBytes >= 0)
        require(historyEntries in 1..16_384 && historyBytes >= 0 && inputBytes >= 0) }
}
enum class DocumentRejection { WRONG_DOCUMENT, STALE_ROOT, REVISION_EXHAUSTED, MISSING_PAGE, WRONG_LAYER, STALE_PAGE,
    REUSED_OPERATION_ID, REUSED_IDENTITY, COPY_MAPPING, INVALID_PAGE, INVALID_TITLE, SCENE_REJECTED, NO_CHANGE,
    PAGE_CAPACITY, OBJECT_CAPACITY, LIVE_BYTE_CAPACITY, JOURNAL_CAPACITY, IDENTITY_CAPACITY,
    HISTORY_EMPTY, HISTORY_TARGET, HISTORY_CONFLICT, HISTORY_CAPACITY, CLOSED }
class DocumentReceipt internal constructor(val operationId: CommandId, val rootRevision: Long, affectedPages: Set<PageId>) {
    private val pages = affectedPages.toSet()
    val durability: Durability get() = Durability.MEMORY_ONLY
    fun affectedPages(): Set<PageId> = pages.toSet()
}
sealed interface DocumentCommit {
    data class Accepted(val receipt: DocumentReceipt) : DocumentCommit
    data class Rejected(val reason: DocumentRejection, val sceneReason: Rejection? = null) : DocumentCommit
}
data class DocumentAuthorityStatus(val liveByteCharge: Long, val journalByteCharge: Long, val identityByteCharge: Long,
                                   val operationCount: Int, val pageCount: Int, val objectCount: Int,
                                   val undoCount: Int, val redoCount: Int, val historyByteCharge: Long,
                                   val nextUndoOperationId: CommandId?, val nextRedoOperationId: CommandId?,
                                   val evictedHistoryCount: Long)
