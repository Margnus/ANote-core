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
    init { require(invalidField(maxPages, maxObjects, liveBytes, journalEntries, journalBytes, identityBytes,
        historyEntries, historyBytes, inputBytes) == null) }
    companion object {
        /** Raw immutable values only: no owner, authority, reservation, clamping or exception recovery.
         * A valid general budget may still be unsupported or insufficient for a preview notebook.
         */
        fun validated(maxPages: Int = 128, maxObjects: Int = 5000, liveBytes: Long = 8L * 1024 * 1024,
                      journalEntries: Int = 128, journalBytes: Long = 8L * 1024 * 1024,
                      identityBytes: Long = 2L * 1024 * 1024, historyEntries: Int = 128,
                      historyBytes: Long = 8L * 1024 * 1024, inputBytes: Long = 2L * 1024 * 1024): DocumentBudgetCreation {
            val failure = invalidField(maxPages, maxObjects, liveBytes, journalEntries, journalBytes, identityBytes,
                historyEntries, historyBytes, inputBytes)
            if (failure != null) return DocumentBudgetCreation(null, failure)
            return DocumentBudgetCreation(DocumentBudget(maxPages, maxObjects, liveBytes, journalEntries,
                journalBytes, identityBytes, historyEntries, historyBytes, inputBytes), null)
        }
        private fun invalidField(maxPages: Int, maxObjects: Int, liveBytes: Long, journalEntries: Int,
                                 journalBytes: Long, identityBytes: Long, historyEntries: Int,
                                 historyBytes: Long, inputBytes: Long): DocumentBudgetFailure? = when {
            maxPages !in 1..4096 -> DocumentBudgetFailure.MAX_PAGES
            maxObjects !in 1..100_000 -> DocumentBudgetFailure.MAX_OBJECTS
            liveBytes < 0 -> DocumentBudgetFailure.LIVE_BYTES
            journalEntries !in 1..16_384 -> DocumentBudgetFailure.JOURNAL_ENTRIES
            journalBytes < 0 -> DocumentBudgetFailure.JOURNAL_BYTES
            identityBytes < 0 -> DocumentBudgetFailure.IDENTITY_BYTES
            historyEntries !in 1..16_384 -> DocumentBudgetFailure.HISTORY_ENTRIES
            historyBytes < 0 -> DocumentBudgetFailure.HISTORY_BYTES
            inputBytes < 0 -> DocumentBudgetFailure.INPUT_BYTES
            else -> null
        }
    }
}
enum class DocumentBudgetFailure { MAX_PAGES, MAX_OBJECTS, LIVE_BYTES, JOURNAL_ENTRIES, JOURNAL_BYTES,
    IDENTITY_BYTES, HISTORY_ENTRIES, HISTORY_BYTES, INPUT_BYTES }
/** Exactly one of budget/failure is non-null. Does not create a document or promise physical heap. */
class DocumentBudgetCreation internal constructor(val budget: DocumentBudget?, val failure: DocumentBudgetFailure?)
enum class DocumentRejection { WRONG_DOCUMENT, STALE_ROOT, REVISION_EXHAUSTED, MISSING_PAGE, WRONG_LAYER, STALE_PAGE,
    REUSED_OPERATION_ID, REUSED_IDENTITY, COPY_MAPPING, INVALID_PAGE, INVALID_TITLE, SCENE_REJECTED, NO_CHANGE,
    PAGE_CAPACITY, OBJECT_CAPACITY, LIVE_BYTE_CAPACITY, JOURNAL_CAPACITY, IDENTITY_CAPACITY,
    HISTORY_EMPTY, HISTORY_TARGET, HISTORY_CONFLICT, HISTORY_CAPACITY, CLOSED, INVALID_IDENTITY }
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
