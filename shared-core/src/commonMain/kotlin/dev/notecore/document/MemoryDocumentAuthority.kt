package dev.notecore.document

import dev.notecore.canvas.prototype.*

/** C0 single-owner reference. Page directory and canonical scene publish together, with one root revision.
 * Receipts are MEMORY_ONLY. Full-map copies / identity tombstones / bounded journal are not production storage.
 */
class MemoryDocumentAuthority(initial: DocumentRoot, private val budget: DocumentBudget = DocumentBudget(),
                              private val ownerCheck: () -> Unit = {}) {
    private data class JournalEntry(val command: DocumentCommand, val receipt: DocumentReceipt, val charge: Long)
    private data class State(val root: DocumentRoot, val journal: Map<CommandId, JournalEntry>,
                             val pages: Set<PageId>, val layers: Set<LayerId>, val objects: Set<ObjectId>)
    private var state = State(initial, emptyMap(), initial.pages().map { it.id }.toSet(), initial.pages().map { it.layerId }.toSet(),
        initial.scene.objects().map { it.id }.toSet())
    init {
        ownerCheck()
        require(initial.pageCount <= budget.maxPages && initial.scene.objectCount <= budget.maxObjects)
        require(liveCharge(initial) <= budget.liveBytes && identityCharge(state) <= budget.identityBytes)
    }
    fun snapshot(): DocumentRoot { ownerCheck(); return state.root }
    fun status(): DocumentAuthorityStatus {
        ownerCheck(); val current = state
        return DocumentAuthorityStatus(liveCharge(current.root), current.journal.values.sumOf { it.charge }, identityCharge(current),
            current.journal.size, current.root.pageCount, current.root.scene.objectCount)
    }
    private fun objectCharge(value: StrokeObject): Long = 256L + value.id.value.length * 2 + value.layerId.value.length * 2 + value.samples.size.toLong() * 28
    private fun liveCharge(root: DocumentRoot): Long = 512L + root.title.length * 2 + root.pages().sumOf { 192L + it.id.value.length * 2 + it.layerId.value.length * 2 } +
        root.scene.objects().sumOf(::objectCharge)
    private fun identityCharge(value: State): Long = value.pages.sumOf { 48L + it.value.length * 2 } +
        value.layers.sumOf { 48L + it.value.length * 2 } + value.objects.sumOf { 48L + it.value.length * 2 }
    private fun commandCharge(command: DocumentCommand): Long = 4096L + when (command) {
        is DocumentCommand.Canvas -> when (val action = command.command) {
            is CanvasCommand.AddStroke -> action.samples.size.toLong() * 28
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
        if (command is DocumentCommand.Rename && command.title.length > 120) return reject(DocumentRejection.INVALID_TITLE)
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
        val nextIds = State(root, old.journal, old.pages + newPages, old.layers + newLayers, old.objects + newObjects)
        if (identityCharge(nextIds) > budget.identityBytes) return reject(DocumentRejection.IDENTITY_CAPACITY)
        val patch = canvasPatch ?: when (val prepared = SceneReducer.prepareChanges(root.scene, command.id, changes)) {
            is PrepareResult.Rejected -> return reject(DocumentRejection.SCENE_REJECTED, prepared.reason)
            is PrepareResult.Prepared -> prepared.patch
        }
        val application = SceneReducer.apply(root.scene, patch)
        if (application is SceneApplication.Rejected) return reject(DocumentRejection.SCENE_REJECTED, application.reason)
        val nextRoot = DocumentRoot.compose(title, (application as SceneApplication.Applied).snapshot, pages)
        val receipt = DocumentReceipt(command.id, nextRevision, affected)
        val nextJournal = old.journal + (command.id to JournalEntry(command, receipt, commandBytes))
        state = nextIds.copy(root = nextRoot, journal = nextJournal) // Only publication point; never partially mutates metadata/scene.
        return DocumentCommit.Accepted(receipt)
    }
}
