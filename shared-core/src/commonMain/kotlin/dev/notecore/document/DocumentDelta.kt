package dev.notecore.document

import dev.notecore.canvas.prototype.*

/** Authority-owned sparse delta; no root/scene map is retained by a history entry. */
internal class DocumentDelta(operationId: CommandId, before: DocumentRoot, after: DocumentRoot,
                             changes: List<ObjectChange>, affected: Set<PageId>, val objectOrder: PageObjectOrder? = null) {
    val operationId = operationId
    val objects = changes.toList()
    private val membershipIds = changes.filter { it.before == null || it.after == null }.map { (it.before ?: it.after!!).id }.toSet()
    val beforePositions = memberPositions(before.scene, membershipIds)
    val afterPositions = memberPositions(after.scene, membershipIds)
    val affected = affected.toSet()
    val beforeOrder = before.pages().map { it.id }
    val afterOrder = after.pages().map { it.id }
    // Store only changed descriptors. Intermediate maps below never escape construction.
    val pages = pageChanges(before.pages(), after.pages())
    val beforeTitle = before.title
    val afterTitle = after.title
    // Conservative logical accounting: each side is charged even when immutable samples are shared.
    val charge: Long = chargeFor(beforeTitle, afterTitle, beforeOrder, afterOrder, this.affected, pages, objectChangesCharge(objects)) + (objectOrder?.charge ?: 0L)

    companion object {
        /** Metadata and pure order edits retain no membership positions. Scan without dense object lists. */
        private fun memberPositions(scene: SceneSnapshot, ids: Set<ObjectId>): Map<ObjectId, Int> {
            if (ids.isEmpty()) return emptyMap()
            val positions = mutableMapOf<ObjectId, Int>()
            var index = 0
            scene.forEachObject {
                if (it.id in ids) positions[it.id] = index
                index++
            }
            return positions
        }
        /** Same accounting as the accepted delta, before a new canonical scene/root is allocated. */
        fun projectedCharge(before: DocumentRoot, afterTitle: String, afterPages: List<PageDescriptor>,
            changes: List<ObjectChange>, affected: Set<PageId>): Long = chargeFor(
            before.title, afterTitle, before.pages().map { it.id }, afterPages.map { it.id },
            affected, pageChanges(before.pages(), afterPages), objectChangesCharge(changes))

        /** Planning only: zero points means fixed headers, not a canonical zero-point stroke. */
        fun projectedStrokeCharge(before: DocumentRoot, page: PageDescriptor, objectId: ObjectId, points: Int): Long {
            require(before.revision < Long.MAX_VALUE && points in 0..1_000_000)
            val order = before.pages().map { it.id }
            return chargeFor(before.title, before.title, order, order, setOf(page.id),
                listOf(PageChange(page.id, page, page.copy(contentRevision = before.revision + 1))),
                192L + objectPayloadCharge(objectId, page.layerId, points))
        }
        private fun objectPayloadCharge(id: ObjectId, layer: LayerId, points: Int): Long =
            256L + (id.value.length + layer.value.length) * 2L + points.toLong() * 28L
        private fun objectChangesCharge(objects: List<ObjectChange>): Long = objects.sumOf { change ->
            192L + listOfNotNull(change.before, change.after).sumOf { objectPayloadCharge(it.id, it.layerId, it.samples.size) }
        }
        private fun pageChanges(before: List<PageDescriptor>, after: List<PageDescriptor>): List<PageChange> {
            val left = before.associateBy { it.id }; val right = after.associateBy { it.id }
            return (before.map { it.id } + after.map { it.id }).distinct().mapNotNull { id ->
                if (left[id] == right[id]) null else PageChange(id, left[id], right[id])
            }
        }
        private fun chargeFor(beforeTitle: String, afterTitle: String, beforeOrder: List<PageId>, afterOrder: List<PageId>,
            affected: Set<PageId>, pages: List<PageChange>, objectBytes: Long): Long =
            512L + (beforeTitle.length + afterTitle.length) * 2L +
            (beforeOrder + afterOrder).sumOf { 48L + it.value.length * 2L } +
            affected.sumOf { 48L + it.value.length * 2L } +
            pages.sumOf { page -> 64L + listOfNotNull(page.before, page.after).sumOf { 192L + (it.id.value.length + it.layerId.value.length) * 2L } } +
            objectBytes
    }

}

internal data class PageChange(val id: PageId, val before: PageDescriptor?, val after: PageDescriptor?)

internal fun samePageContent(left: PageDescriptor?, right: PageDescriptor?): Boolean =
    if (left == null || right == null) left == right else left.copy(contentRevision = 0) == right.copy(contentRevision = 0)

internal fun sameObjectContent(left: StrokeObject?, right: StrokeObject?): Boolean =
    if (left == null || right == null) left == right else left.id == right.id && left.layerId == right.layerId &&
        left.brush == right.brush && left.transform == right.transform &&
        (left.samples === right.samples || left.samples == right.samples)

/** Only the changed page's identity order is retained, never a root or object payload. */
internal class PageObjectOrder(val pageId: PageId, before: List<ObjectId>, after: List<ObjectId>) {
    private val left = before.toList()
    private val right = after.toList()
    val charge: Long get() = 128L + pageId.value.length * 2L +
        left.sumOf { 48L + it.value.length * 2L } + right.sumOf { 48L + it.value.length * 2L }
    fun matches(root: DocumentRoot, undo: Boolean): Boolean {
        val page = root.findPage(pageId) ?: return false
        val expected = if (undo) right else left
        var index = 0
        var matches = true
        root.scene.forEachObject { if (it.layerId == page.layerId) {
            if (index >= expected.size || expected[index] != it.id) matches = false
            index++
        } }
        return matches && index == expected.size
    }
    fun apply(scene: SceneSnapshot, layer: LayerId, undo: Boolean): SceneSnapshot {
        val target = if (undo) left else right
        var index = 0
        val order = scene.objects().map { if (it.layerId == layer) target[index++] else it.id }
        check(index == target.size)
        return SceneSnapshot.reordered(scene, order)
    }
    companion object {
        /** Both sides have the same membership; admission needs no full-scene identity array. */
        fun projectedCharge(root: DocumentRoot, page: PageDescriptor): Long {
            var bytes = 128L + page.id.value.length * 2L
            root.scene.forEachObject { if (it.layerId == page.layerId) bytes += 2L * (48L + it.id.value.length * 2L) }
            return bytes
        }
    }
}
