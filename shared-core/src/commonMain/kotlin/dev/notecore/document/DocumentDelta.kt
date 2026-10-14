package dev.notecore.document

import dev.notecore.canvas.prototype.*

/** Authority-owned sparse delta; no root/scene map is retained by a history entry. */
internal class DocumentDelta(operationId: CommandId, before: DocumentRoot, after: DocumentRoot,
                             changes: List<ObjectChange>, affected: Set<PageId>) {
    val operationId = operationId
    val objects = changes.toList()
    val affected = affected.toSet()
    val beforeOrder = before.pages().map { it.id }
    val afterOrder = after.pages().map { it.id }
    // Store only changed descriptors. Intermediate maps below never escape construction.
    val pages = run {
        val beforePages = before.pages().associateBy { it.id }
        val afterPages = after.pages().associateBy { it.id }
        (beforeOrder + afterOrder).distinct().mapNotNull { id ->
            val left = beforePages[id]; val right = afterPages[id]
            if (left == right) null else PageChange(id, left, right)
        }
    }
    val beforeTitle = before.title
    val afterTitle = after.title
    // Conservative logical accounting: each side is charged even when immutable samples are shared.
    val charge: Long = 512L + (beforeTitle.length + afterTitle.length) * 2L +
        (beforeOrder + afterOrder).sumOf { 48L + it.value.length * 2L } +
        this.affected.sumOf { 48L + it.value.length * 2L } +
        pages.sumOf { page -> 64L + listOfNotNull(page.before, page.after).sumOf { 192L + (it.id.value.length + it.layerId.value.length) * 2L } } +
        objects.sumOf { change -> 64L + listOfNotNull(change.before, change.after).sumOf { 256L + (it.id.value.length + it.layerId.value.length) * 2L + it.samples.size.toLong() * 28L } }
}

internal data class PageChange(val id: PageId, val before: PageDescriptor?, val after: PageDescriptor?)

internal fun samePageContent(left: PageDescriptor?, right: PageDescriptor?): Boolean =
    if (left == null || right == null) left == right else left.copy(contentRevision = 0) == right.copy(contentRevision = 0)

internal fun sameObjectContent(left: StrokeObject?, right: StrokeObject?): Boolean =
    if (left == null || right == null) left == right else left.id == right.id && left.layerId == right.layerId &&
        left.brush == right.brush && left.transform == right.transform &&
        (left.samples === right.samples || left.samples == right.samples)
