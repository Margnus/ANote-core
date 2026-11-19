package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.math.abs

internal enum class LassoPasteFailure {
    WRONG_DOCUMENT, STALE_ROOT, SOURCE_PAGE_MISSING, TARGET_PAGE_MISSING,
    EMPTY_SELECTION, INVALID_SELECTION, MISSING_OBJECT, WRONG_PAGE,
    INVALID_MAPPING, MAPPING_CONFLICT, INVALID_OFFSET, CAPACITY,
}

internal data class LassoPastePlan(
    val documentId: SceneId,
    val expectedRootRevision: Long,
    val sourcePageId: PageId,
    val targetPageId: PageId,
    private val objects: List<StrokeObject>,
) {
    fun newObjects(): List<StrokeObject> = objects.toList()
}

internal data class LassoPasteResult(val plan: LassoPastePlan? = null, val failure: LassoPasteFailure? = null)

internal object LassoPasteRules {
    fun copy(root: DocumentRoot, documentId: SceneId, expectedRevision: Long,
             sourcePageId: PageId, selection: List<ObjectId>,
             targetPageId: PageId, idMapping: Map<ObjectId, ObjectId>,
             pasteOffset: PointD = PointD(0.0, 0.0)): LassoPasteResult {
        if (root.id != documentId) return LassoPasteResult(failure = LassoPasteFailure.WRONG_DOCUMENT)
        if (expectedRevision < 0 || root.revision != expectedRevision) return LassoPasteResult(failure = LassoPasteFailure.STALE_ROOT)
        val sourcePage = root.findPage(sourcePageId)
            ?: return LassoPasteResult(failure = LassoPasteFailure.SOURCE_PAGE_MISSING)
        val targetPage = root.findPage(targetPageId)
            ?: return LassoPasteResult(failure = LassoPasteFailure.TARGET_PAGE_MISSING)
        if (selection.isEmpty()) return LassoPasteResult(failure = LassoPasteFailure.EMPTY_SELECTION)
        if (selection.size > 100_000) return LassoPasteResult(failure = LassoPasteFailure.CAPACITY)
        if (selection.any { it.value.isBlank() || it.value.length > 128 } || selection.toSet().size != selection.size)
            return LassoPasteResult(failure = LassoPasteFailure.INVALID_SELECTION)
        if (!pasteOffset.x.isFinite() || !pasteOffset.y.isFinite() ||
            abs(pasteOffset.x) > 1e9 || abs(pasteOffset.y) > 1e9)
            return LassoPasteResult(failure = LassoPasteFailure.INVALID_OFFSET)
        if (idMapping.keys.toSet() != selection.toSet())
            return LassoPasteResult(failure = LassoPasteFailure.INVALID_MAPPING)
        if (idMapping.values.toSet().size != idMapping.values.size)
            return LassoPasteResult(failure = LassoPasteFailure.MAPPING_CONFLICT)
        val existingIds = root.scene.let { scene -> buildSet { scene.forEachSceneObject { add(it.id) } } }
        if (idMapping.values.any { it in existingIds })
            return LassoPasteResult(failure = LassoPasteFailure.MAPPING_CONFLICT)
        if (idMapping.values.any { it.value.isBlank() || it.value.length > 128 || !it.value.hasValidUnicode() })
            return LassoPasteResult(failure = LassoPasteFailure.INVALID_MAPPING)

        val chosen = selection.toSet()
        val newObjects = mutableListOf<StrokeObject>()
        for (stroke in root.pageObjects(sourcePageId)) {
            if (stroke.id !in chosen) continue
            val newId = idMapping[stroke.id]
                ?: return LassoPasteResult(failure = LassoPasteFailure.INVALID_MAPPING)
            val adjusted = Affine2D(
                a = stroke.transform.a, b = stroke.transform.b,
                c = stroke.transform.c, d = stroke.transform.d,
                tx = stroke.transform.tx + pasteOffset.x,
                ty = stroke.transform.ty + pasteOffset.y,
            )
            newObjects.add(StrokeObject(newId, 1, targetPage.layerId,
                stroke.brush, stroke.samples, adjusted))
        }
        if (newObjects.size != chosen.size) {
            val found = newObjects.map { it.id }.toSet()
            if (chosen.any { root.scene.findObject(it) == null })
                return LassoPasteResult(failure = LassoPasteFailure.MISSING_OBJECT)
            if (idMapping.keys.any { key -> root.scene.findObject(key)?.layerId != sourcePage.layerId })
                return LassoPasteResult(failure = LassoPasteFailure.WRONG_PAGE)
            return LassoPasteResult(failure = LassoPasteFailure.WRONG_PAGE)
        }
        return LassoPasteResult(LassoPastePlan(root.id, root.revision, sourcePageId, targetPageId, newObjects))
    }
}
