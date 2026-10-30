package dev.notecore.document

import dev.notecore.canvas.prototype.ObjectId
import dev.notecore.canvas.prototype.SceneId

internal enum class ObjectOrderDirection { FRONT, BACK }
internal enum class ObjectOrderFailure {
    WRONG_DOCUMENT, STALE_ROOT, MISSING_PAGE, INVALID_BUDGET, CAPACITY,
    INVALID_SELECTION, MISSING_OBJECT, WRONG_PAGE, NO_CHANGE,
}

/** Immutable proposed draw order, never an accepted command or a second scene authority. */
internal class ObjectOrderPlan(
    val documentId: SceneId, val expectedRootRevision: Long, val pageId: PageId, order: List<ObjectId>,
) {
    private val ids = order.toList()
    fun objectIds(): List<ObjectId> = ids.toList()
}
internal data class ObjectOrderResult(val plan: ObjectOrderPlan? = null, val failure: ObjectOrderFailure? = null)

internal object ObjectOrderRules {
    /** Count admission only. The eventual owner must also admit retained root/plan bytes before use. */
    fun plan(root: DocumentRoot, documentId: SceneId, expectedRevision: Long, pageId: PageId,
             selected: List<ObjectId>, direction: ObjectOrderDirection, maxObjects: Int): ObjectOrderResult {
        if (root.id != documentId) return ObjectOrderResult(failure = ObjectOrderFailure.WRONG_DOCUMENT)
        if (expectedRevision < 0 || root.revision != expectedRevision) return ObjectOrderResult(failure = ObjectOrderFailure.STALE_ROOT)
        val page = root.findPage(pageId) ?: return ObjectOrderResult(failure = ObjectOrderFailure.MISSING_PAGE)
        if (maxObjects !in 1..100_000) return ObjectOrderResult(failure = ObjectOrderFailure.INVALID_BUDGET)
        if (root.scene.objectCount > maxObjects || selected.size > maxObjects)
            return ObjectOrderResult(failure = ObjectOrderFailure.CAPACITY)
        if (selected.isEmpty() || selected.any { it.value.isBlank() || it.value.length > 128 || !it.value.hasValidUnicode() })
            return ObjectOrderResult(failure = ObjectOrderFailure.INVALID_SELECTION)
        val selection = selected.toSet()
        if (selection.size != selected.size) return ObjectOrderResult(failure = ObjectOrderFailure.INVALID_SELECTION)
        for (id in selection) {
            val item = root.scene.find(id) ?: return ObjectOrderResult(failure = ObjectOrderFailure.MISSING_OBJECT)
            if (item.layerId != page.layerId) return ObjectOrderResult(failure = ObjectOrderFailure.WRONG_PAGE)
        }
        val global = ArrayList<ObjectId>(root.scene.objectCount)
        val pageOrder = mutableListOf<ObjectId>()
        root.scene.forEachObject { item ->
            global.add(item.id)
            if (item.layerId == page.layerId) pageOrder.add(item.id)
        }
        // Selection request order cannot reverse selected objects or disturb untouched page slots.
        val chosen = pageOrder.filter { it in selection }
        val other = pageOrder.filter { it !in selection }
        val reordered = if (direction == ObjectOrderDirection.FRONT) other + chosen else chosen + other
        if (reordered == pageOrder) return ObjectOrderResult(failure = ObjectOrderFailure.NO_CHANGE)
        var cursor = 0
        val result = global.map { id -> if (root.scene.find(id)!!.layerId == page.layerId) reordered[cursor++] else id }
        return ObjectOrderResult(ObjectOrderPlan(root.id, root.revision, page.id, result))
    }

    /** Same-document revision must still belong to the original single authority; no fork adoption. */
    fun revalidate(root: DocumentRoot, plan: ObjectOrderPlan): ObjectOrderFailure? = when {
        root.id != plan.documentId -> ObjectOrderFailure.WRONG_DOCUMENT
        root.revision != plan.expectedRootRevision -> ObjectOrderFailure.STALE_ROOT
        root.findPage(plan.pageId) == null -> ObjectOrderFailure.MISSING_PAGE
        else -> null
    }
}
