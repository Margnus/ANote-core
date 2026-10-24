package dev.notecore.document

import dev.notecore.canvas.prototype.*

/** C0 single-owner reference. Page directory and canonical scene publish together, with one root revision.
 * Receipts are MEMORY_ONLY. Full-map copies / identity tombstones / bounded journal are not production storage.
 */
class MemoryDocumentAuthority @Throws(IllegalArgumentException::class) constructor(initial: DocumentRoot, private val budget: DocumentBudget = DocumentBudget(),
                              private val ownerCheck: () -> Unit = {}) {
    internal companion object {
        const val COMMAND_HEADER_BYTES = 4096L
        const val POINT_BYTES = 28L
        private fun objectCharge(value: StrokeObject): Long = 256L + value.id.value.length * 2 + value.layerId.value.length * 2 + value.samples.size.toLong() * 28
        private fun liveCharge(root: DocumentRoot): Long = 512L + (root.id.value.length + root.title.length) * 2 + root.pages().sumOf { 192L + it.id.value.length * 2 + it.layerId.value.length * 2 } +
            root.scene.objects().sumOf(::objectCharge)
        /** Same initial-state formulas as the authority, without constructing a mutable owner. */
        fun initialCapacityFailure(root: DocumentRoot, budget: DocumentBudget): DocumentRejection? = when {
            root.pageCount > budget.maxPages -> DocumentRejection.PAGE_CAPACITY
            root.scene.objectCount > budget.maxObjects -> DocumentRejection.OBJECT_CAPACITY
            liveCharge(root) > budget.liveBytes -> DocumentRejection.LIVE_BYTE_CAPACITY
            root.pages().sumOf { 48L + it.id.value.length * 2 + 48L + it.layerId.value.length * 2 } +
                root.scene.objects().sumOf { 80L + it.id.value.length * 2L } > budget.identityBytes -> DocumentRejection.IDENTITY_CAPACITY
            else -> null
        }
    }
    private fun objectIdentityCharge(id: ObjectId): Long = 80L + id.value.length * 2L
    private data class JournalEntry(val command: DocumentCommand, val receipt: DocumentReceipt, val charge: Long)
    private data class State(val root: DocumentRoot, val journal: Map<CommandId, JournalEntry>,
                             val pages: Set<PageId>, val layers: Set<LayerId>, val objects: Set<ObjectId>,
                             val epochs: Map<ObjectId, Long>, val undo: List<DocumentDelta> = emptyList(),
                             val redo: List<DocumentDelta> = emptyList(), val evictedHistoryCount: Long = 0)
    private var state = State(initial, emptyMap(), initial.pages().map { it.id }.toSet(), initial.pages().map { it.layerId }.toSet(),
        initial.scene.objects().map { it.id }.toSet(), initial.scene.objects().associate { it.id to it.objectRevision })
    init {
        ownerCheck()
        require(initial.title.hasValidUnicode()) { "Invalid Unicode title" }
        require(initialCapacityFailure(initial, budget) == null)
    }
    /** Allocation probe over the original budgeted tombstones/journal; no secondary identity index.
     * Caller supplies an already validated generated identity. Deleted/undone identities stay reserved.
     */
    internal fun identityWasUsed(value: String): Boolean {
        ownerCheck(); val current = state
        return PageId(value) in current.pages || LayerId(value) in current.layers ||
            ObjectId(value) in current.objects || CommandId(value) in current.journal
    }
    fun snapshot(): DocumentRoot { ownerCheck(); return state.root }
    fun status(): DocumentAuthorityStatus {
        ownerCheck(); val current = state
        return DocumentAuthorityStatus(liveCharge(current.root), current.journal.values.sumOf { it.charge }, identityCharge(current),
            current.journal.size, current.root.pageCount, current.root.scene.objectCount,
            current.undo.size, current.redo.size, (current.undo + current.redo).sumOf { it.charge },
            current.undo.lastOrNull()?.operationId, current.redo.lastOrNull()?.operationId, current.evictedHistoryCount)
    }
    /** Existing authority's live-payload gate, not a reservation of history, journal or system heap. */
    internal fun additionalStrokeSampleCapacity(objectId: ObjectId, layerId: LayerId): Int {
        ownerCheck()
        if (state.root.scene.objectCount >= budget.maxObjects) return 0
        val header = 256L + (objectId.value.length + layerId.value.length) * 2L
        val remaining = (budget.liveBytes - liveCharge(state.root) - header).coerceAtLeast(0L)
        return (remaining / POINT_BYTES).coerceAtMost(1_000_000L).toInt()
    }
    internal data class StrokeInputCapacity(val points: Int, val limitingReason: DocumentRejection)
    /** Single-owner planning for one new stroke on the current root; no reservation or mutable fork. */
    internal fun strokeInputCapacity(objectId: ObjectId, pageId: PageId): StrokeInputCapacity {
        ownerCheck(); val current = state; val root = current.root
        fun refused(reason: DocumentRejection) = StrokeInputCapacity(0, reason)
        if (root.revision == Long.MAX_VALUE) return refused(DocumentRejection.REVISION_EXHAUSTED)
        if (current.journal.size >= budget.journalEntries) return refused(DocumentRejection.JOURNAL_CAPACITY)
        val journal = ((budget.journalBytes - current.journal.values.sumOf { it.charge } - COMMAND_HEADER_BYTES).coerceAtLeast(0L) / POINT_BYTES).coerceAtMost(1_000_000L).toInt()
        if (journal == 0) return refused(DocumentRejection.JOURNAL_CAPACITY)
        val page = root.findPage(pageId) ?: return refused(DocumentRejection.MISSING_PAGE)
        val live = additionalStrokeSampleCapacity(objectId, page.layerId)
        if (live == 0) return refused(DocumentRejection.LIVE_BYTE_CAPACITY)
        if (objectId in current.objects) return refused(DocumentRejection.REUSED_IDENTITY)
        if (objectIdentityCharge(objectId) > budget.identityBytes - identityCharge(current))
            return refused(DocumentRejection.IDENTITY_CAPACITY)
        val fixedHistory = DocumentDelta.projectedStrokeCharge(root, page, objectId, 0)
        val history = ((budget.historyBytes - fixedHistory).coerceAtLeast(0L) / POINT_BYTES).coerceAtMost(1_000_000L).toInt()
        val points = minOf(journal, live, history)
        val reason = when (points) {
            journal -> DocumentRejection.JOURNAL_CAPACITY
            live -> DocumentRejection.LIVE_BYTE_CAPACITY
            else -> DocumentRejection.HISTORY_CAPACITY
        }
        return StrokeInputCapacity(points, reason)
    }
    private fun identityCharge(value: State): Long = value.pages.sumOf { 48L + it.value.length * 2 } +
        value.layers.sumOf { 48L + it.value.length * 2 } + value.objects.sumOf(::objectIdentityCharge)
    private fun commandCharge(command: DocumentCommand): Long = COMMAND_HEADER_BYTES + when (command) {
        is DocumentCommand.Canvas -> when (val action = command.command) {
            is CanvasCommand.AddStroke -> action.samples.size.toLong() * POINT_BYTES
            else -> 0L
        }
        is DocumentCommand.CopyPage -> command.objectMappingCount * 768L
        is DocumentCommand.PreparedCanvas -> command.patch.changes().sumOf {
            1024L + (it.before?.samples?.size ?: 0).toLong() * 28 + (it.after?.samples?.size ?: 0).toLong() * 28
        }
        else -> 0L
    }
    private fun reject(reason: DocumentRejection, scene: Rejection? = null) = DocumentCommit.Rejected(reason, scene)

    fun commit(command: DocumentCommand): DocumentCommit {
        ownerCheck()
        val old = state; val root = old.root
        old.journal[command.id]?.let { return if (it.command == command) DocumentCommit.Accepted(it.receipt) else reject(DocumentRejection.REUSED_OPERATION_ID) }
        if (command is DocumentCommand.Rename && (command.title.length > 120 || !command.title.hasValidUnicode())) return reject(DocumentRejection.INVALID_TITLE)
        if (command.documentId != root.id) return reject(DocumentRejection.WRONG_DOCUMENT)
        if (command.baseRevision != root.revision) return reject(DocumentRejection.STALE_ROOT)
        if (root.revision == Long.MAX_VALUE) return reject(DocumentRejection.REVISION_EXHAUSTED)
        val commandBytes = commandCharge(command)
        if (old.journal.size >= budget.journalEntries || commandBytes > budget.journalBytes - old.journal.values.sumOf { it.charge })
            return reject(DocumentRejection.JOURNAL_CAPACITY)
        val nextRevision = root.revision + 1
        val pages = root.pages().toMutableList()
        var title = root.title
        val changes = mutableListOf<ObjectChange>()
        var historyDelta: DocumentDelta? = null
        val undoing = command is DocumentCommand.Undo
        var canvasPatch: ScenePatch? = null
        val affected = linkedSetOf<PageId>()
        val newPages = mutableSetOf<PageId>(); val newLayers = mutableSetOf<LayerId>(); val newObjects = mutableSetOf<ObjectId>()
        fun insertionIndex(before: PageId?): Int = if (before == null) pages.size else pages.indexOfFirst { it.id == before }
        fun addPage(page: PageDescriptor, before: PageId?): DocumentRejection? {
            if (page.id in old.pages || page.layerId in old.layers) return DocumentRejection.REUSED_IDENTITY
            if (pages.size >= budget.maxPages) return DocumentRejection.PAGE_CAPACITY
            val index = insertionIndex(before); if (index < 0) return DocumentRejection.MISSING_PAGE
            pages.add(index, page.copy(contentRevision = nextRevision)); newPages.add(page.id); newLayers.add(page.layerId); affected.add(page.id)
            return null
        }
        when (command) {
            is DocumentCommand.Undo, is DocumentCommand.Redo -> {
                val expected = when (command) {
                    is DocumentCommand.Undo -> command.expectedOperationId
                    is DocumentCommand.Redo -> command.expectedOperationId
                    else -> error("history command")
                }
                val delta = (if (undoing) old.undo else old.redo).lastOrNull() ?: return reject(DocumentRejection.HISTORY_EMPTY)
                if (delta.operationId != expected) return reject(DocumentRejection.HISTORY_TARGET)
                historyDelta = delta
                val expectedOrder = if (undoing) delta.afterOrder else delta.beforeOrder
                val targetOrder = if (undoing) delta.beforeOrder else delta.afterOrder
                val expectedTitle = if (undoing) delta.afterTitle else delta.beforeTitle
                if (root.pages().map { it.id } != expectedOrder || root.title != expectedTitle) return reject(DocumentRejection.HISTORY_CONFLICT)
                title = if (undoing) delta.beforeTitle else delta.afterTitle
                val restoredPages = pages.associateBy { it.id }.toMutableMap()
                for (change in delta.pages) {
                    val expectedPage = if (undoing) change.after else change.before
                    val targetPage = if (undoing) change.before else change.after
                    if (!samePageContent(root.findPage(change.id), expectedPage)) return reject(DocumentRejection.HISTORY_CONFLICT)
                    if (targetPage == null) restoredPages.remove(change.id)
                    else restoredPages[change.id] = targetPage.copy(contentRevision = nextRevision)
                }
                pages.clear()
                for (id in targetOrder) pages.add(restoredPages[id] ?: return reject(DocumentRejection.HISTORY_CONFLICT))
                for (change in delta.objects) {
                    val expectedObject = if (undoing) change.after else change.before
                    val targetObject = if (undoing) change.before else change.after
                    val id = (change.before ?: change.after!!).id
                    val current = root.scene.find(id)
                    if (!sameObjectContent(current, expectedObject)) return reject(DocumentRejection.HISTORY_CONFLICT)
                    val epoch = old.epochs[id] ?: return reject(DocumentRejection.HISTORY_CONFLICT)
                    if (epoch == Long.MAX_VALUE) return reject(DocumentRejection.REVISION_EXHAUSTED)
                    changes.add(ObjectChange(current, targetObject?.copy(objectRevision = epoch + 1)))
                }
                affected.addAll(delta.affected)
            }
            is DocumentCommand.Rename -> {
                title = command.title.trim().take(120).ifBlank { "未命名笔记" }
                if (title == root.title) return reject(DocumentRejection.NO_CHANGE)
            }
            is DocumentCommand.InsertPage -> {
                if (command.page.contentRevision != 0L) return reject(DocumentRejection.INVALID_PAGE)
                addPage(command.page, command.before)?.let { return reject(it) }
            }
            is DocumentCommand.MovePage -> {
                val index = pages.indexOfFirst { it.id == command.pageId }; if (index < 0) return reject(DocumentRejection.MISSING_PAGE)
                if (command.before == command.pageId) return reject(DocumentRejection.NO_CHANGE)
                if (command.before != null && pages.none { it.id == command.before }) return reject(DocumentRejection.MISSING_PAGE)
                val moved = pages.removeAt(index); pages.add(insertionIndex(command.before), moved)
                if (pages == root.pages()) return reject(DocumentRejection.NO_CHANGE)
                affected.add(command.pageId) // Membership order changed, page content revision did not.
            }
            is DocumentCommand.DeletePage -> {
                val page = root.findPage(command.pageId) ?: return reject(DocumentRejection.MISSING_PAGE)
                root.pageObjects(page.id).forEach { changes.add(ObjectChange(it, null)) }
                if (pages.size == 1) {
                    if (changes.isEmpty() && page.paper == PaperKind.BLANK) return reject(DocumentRejection.NO_CHANGE)
                    pages[0] = page.copy(paper = PaperKind.BLANK, contentRevision = nextRevision)
                } else pages.removeAll { it.id == page.id }
                affected.add(page.id)
            }
            is DocumentCommand.SetPaper -> {
                val page = root.findPage(command.pageId) ?: return reject(DocumentRejection.MISSING_PAGE)
                val replacement = try { page.copy(width = command.width, height = command.height, paper = command.paper, contentRevision = nextRevision) }
                    catch (_: IllegalArgumentException) { return reject(DocumentRejection.INVALID_PAGE) }
                if (page.width == replacement.width && page.height == replacement.height && page.paper == replacement.paper) return reject(DocumentRejection.NO_CHANGE)
                pages[pages.indexOf(page)] = replacement; affected.add(page.id)
            }
            is DocumentCommand.CopyPage -> {
                val source = root.findPage(command.source) ?: return reject(DocumentRejection.MISSING_PAGE)
                if (source.contentRevision != command.expectedPageRevision) return reject(DocumentRejection.STALE_PAGE)
                val objects = root.pageObjects(source.id); val mapping = command.bindings()
                if (mapping.keys != objects.map { it.id }.toSet() || mapping.values.toSet().size != mapping.size) return reject(DocumentRejection.COPY_MAPPING)
                if (mapping.values.any { it in old.objects }) return reject(DocumentRejection.REUSED_IDENTITY)
                if (objects.size > budget.maxObjects - root.scene.objectCount) return reject(DocumentRejection.OBJECT_CAPACITY)
                // Reject projected payload before constructing copied object headers or scene maps.
                val extra = objects.sumOf { 256L + mapping.getValue(it.id).value.length * 2 + command.newLayerId.value.length * 2 + it.samples.size.toLong() * 28 }
                if (extra > budget.liveBytes - liveCharge(root)) return reject(DocumentRejection.LIVE_BYTE_CAPACITY)
                addPage(source.copy(id = command.newPageId, layerId = command.newLayerId, contentRevision = 0), command.before)?.let { return reject(it) }
                for (obj in objects) {
                    val copy = obj.copy(id = mapping.getValue(obj.id), layerId = command.newLayerId, objectRevision = 1)
                    changes.add(ObjectChange(null, copy)); newObjects.add(copy.id)
                }
            }
            is DocumentCommand.PreparedCanvas -> {
                val page = root.findPage(command.pageId) ?: return reject(DocumentRejection.MISSING_PAGE)
                val proposed = command.patch.changes()
                if (proposed.isEmpty()) return reject(DocumentRejection.NO_CHANGE)
                for (change in proposed) {
                    if (change.before?.let { it.layerId != page.layerId } == true || change.after?.let { it.layerId != page.layerId } == true)
                        return reject(DocumentRejection.WRONG_LAYER)
                    val after = change.after
                    if (change.before == null && after != null) {
                        if (after.id in old.objects) return reject(DocumentRejection.REUSED_IDENTITY)
                        if (after.objectRevision != 1L) return reject(DocumentRejection.SCENE_REJECTED, Rejection.STALE_OBJECT)
                        newObjects.add(after.id)
                    }
                }
                changes.addAll(proposed); canvasPatch = command.patch
                pages[pages.indexOf(page)] = page.copy(contentRevision = nextRevision); affected.add(page.id)
            }
            is DocumentCommand.Canvas -> {
                val page = root.findPage(command.pageId) ?: return reject(DocumentRejection.MISSING_PAGE)
                when (val action = command.command) {
                    is CanvasCommand.AddStroke -> {
                        if (action.target.layerId != page.layerId) return reject(DocumentRejection.WRONG_LAYER)
                        if (action.objectId in old.objects) return reject(DocumentRejection.REUSED_IDENTITY)
                        if (root.scene.objectCount >= budget.maxObjects) return reject(DocumentRejection.OBJECT_CAPACITY)
                        val extra = 256L + action.objectId.value.length * 2 + action.target.layerId.value.length * 2 + action.samples.size.toLong() * 28
                        if (extra > budget.liveBytes - liveCharge(root)) return reject(DocumentRejection.LIVE_BYTE_CAPACITY)
                        newObjects.add(action.objectId)
                    }
                    is CanvasCommand.DeleteStroke -> if (root.scene.find(action.objectId)?.let { it.layerId != page.layerId } == true) return reject(DocumentRejection.WRONG_LAYER)
                    is CanvasCommand.TransformStroke -> if (root.scene.find(action.objectId)?.let { it.layerId != page.layerId } == true) return reject(DocumentRejection.WRONG_LAYER)
                }
                val prepared = SceneReducer.prepare(root.scene, command.command)
                if (prepared is PrepareResult.Rejected) return reject(DocumentRejection.SCENE_REJECTED, prepared.reason)
                canvasPatch = (prepared as PrepareResult.Prepared).patch
                changes.addAll(canvasPatch.changes())
                pages[pages.indexOf(page)] = page.copy(contentRevision = nextRevision); affected.add(page.id)
            }
        }
        // Validate all budgets / identity tombstones before allocating the next canonical scene map.
        val prospectiveObjects = root.scene.objectCount + changes.count { it.before == null } - changes.count { it.after == null }
        if (prospectiveObjects > budget.maxObjects) return reject(DocumentRejection.OBJECT_CAPACITY)
        val prospectiveLive = liveCharge(root) + (title.length - root.title.length) * 2 + changes.sumOf { (it.after?.let(::objectCharge) ?: 0L) - (it.before?.let(::objectCharge) ?: 0L) } +
            pages.sumOf { 192L + it.id.value.length * 2 + it.layerId.value.length * 2 } - root.pages().sumOf { 192L + it.id.value.length * 2 + it.layerId.value.length * 2 }
        if (prospectiveLive > budget.liveBytes) return reject(DocumentRejection.LIVE_BYTE_CAPACITY)
        if (pages.size > budget.maxPages) return reject(DocumentRejection.PAGE_CAPACITY)
        val epochs = if (changes.isEmpty()) null else old.epochs.toMutableMap()
        for (change in changes) {
            val id = (change.before ?: change.after!!).id
            val previous = old.epochs[id] ?: 0L
            if (previous == Long.MAX_VALUE) return reject(DocumentRejection.REVISION_EXHAUSTED)
            val next = change.after?.objectRevision ?: (previous + 1)
            if (next <= previous) return reject(DocumentRejection.SCENE_REJECTED, Rejection.STALE_OBJECT)
            epochs!![id] = next
        }
        val nextIds = old.copy(pages = if (newPages.isEmpty()) old.pages else old.pages + newPages,
            layers = if (newLayers.isEmpty()) old.layers else old.layers + newLayers,
            objects = if (newObjects.isEmpty()) old.objects else old.objects + newObjects, epochs = epochs?.toMap() ?: old.epochs)
        if (identityCharge(nextIds) > budget.identityBytes) return reject(DocumentRejection.IDENTITY_CAPACITY)
        val patch = canvasPatch ?: when (val prepared = SceneReducer.prepareChanges(root.scene, command.id, changes)) {
            is PrepareResult.Rejected -> return reject(DocumentRejection.SCENE_REJECTED, prepared.reason)
            is PrepareResult.Prepared -> prepared.patch
        }
        // Original commands have passed canonical preparation above. Reject oversized history before
        // copying the complete scene map. External prepared patches keep their existing CAS-first check.
        if (historyDelta == null && command !is DocumentCommand.PreparedCanvas &&
            DocumentDelta.projectedCharge(root, title, pages, changes, affected) > budget.historyBytes)
            return reject(DocumentRejection.HISTORY_CAPACITY)
        val application = SceneReducer.apply(root.scene, patch)
        if (application is SceneApplication.Rejected) return reject(DocumentRejection.SCENE_REJECTED, application.reason)
        var scene = (application as SceneApplication.Applied).snapshot
        historyDelta?.let { delta ->
            val positions = if (undoing) delta.beforePositions else delta.afterPositions
            // Only newly restored members need placement; transforms keep their existing draw order.
            val restored = changes.filter { it.before == null }.map { it.after!!.id }.toSet()
            if (restored.isNotEmpty()) {
                val order = scene.objects().map { it.id }.filter { it !in restored }.toMutableList()
                for ((id, position) in positions.filterKeys { it in restored }.entries.sortedBy { it.value }) order.add(position, id)
                scene = SceneSnapshot.reordered(scene, order)
            }
        }
        val nextRoot = DocumentRoot.compose(title, scene, pages)
        val nextUndo: List<DocumentDelta>
        val nextRedo: List<DocumentDelta>
        var evicted = old.evictedHistoryCount
        val traversal = historyDelta
        if (traversal != null) {
            nextUndo = if (undoing) old.undo.dropLast(1) else old.undo + traversal
            nextRedo = if (undoing) old.redo + traversal else old.redo.dropLast(1)
        } else {
            val delta = DocumentDelta(command.id, root, nextRoot, changes, affected)
            if (delta.charge > budget.historyBytes) return reject(DocumentRejection.HISTORY_CAPACITY)
            val retained = old.undo.toMutableList()
            var retainedBytes = retained.sumOf { it.charge }
            while (retained.size >= budget.historyEntries || delta.charge > budget.historyBytes - retainedBytes) {
                retainedBytes -= retained.removeAt(0).charge
                evicted++
            }
            nextUndo = retained + delta
            nextRedo = emptyList() // A failed edit never reaches this publication; its redo branch survives.
        }
        val receipt = DocumentReceipt(command.id, nextRevision, affected)
        val nextJournal = old.journal + (command.id to JournalEntry(command, receipt, commandBytes))
        state = nextIds.copy(root = nextRoot, journal = nextJournal, undo = nextUndo, redo = nextRedo,
            evictedHistoryCount = evicted) // Only publication point; never partially mutates metadata/scene/history.
        return DocumentCommit.Accepted(receipt)
    }
}
