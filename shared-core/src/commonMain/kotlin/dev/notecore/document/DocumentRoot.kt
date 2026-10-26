package dev.notecore.document

import dev.notecore.canvas.prototype.*

data class PageId(val value: String) { init { require(value.isNotBlank() && value.length <= 128) } }
enum class PaperKind { BLANK, RULED, GRID, DOTS }
data class PageDescriptor(val id: PageId, val layerId: LayerId, val width: Double, val height: Double,
                          val paper: PaperKind = PaperKind.BLANK, val contentRevision: Long = 0) {
    init { require(width.isFinite() && height.isFinite() && width in 1.0..14_400.0 && height in 1.0..14_400.0 && contentRevision >= 0) }
}

/** One canonical scene plus immutable page membership. Root revision is the scene's sole commit sequence. */
class DocumentRoot private constructor(val title: String, val scene: SceneSnapshot, pages: List<PageDescriptor>) {
    private val directory = pages.toList()
    val id: SceneId get() = scene.id
    val revision: Long get() = scene.revision
    val pageCount: Int get() = directory.size
    init {
        require(title.length <= 120 && directory.isNotEmpty())
        require(directory.map { it.id }.toSet().size == directory.size && directory.map { it.layerId }.toSet().size == directory.size)
        require(directory.all { it.contentRevision <= revision })
        val layers = directory.map { it.layerId }.toSet()
        scene.forEachObject { require(it.layerId in layers) { "Orphan canvas object" } }
    }
    fun pages(): List<PageDescriptor> = directory.toList()
    fun page(index: Int): PageDescriptor = directory[index]
    fun findPage(id: PageId): PageDescriptor? = directory.firstOrNull { it.id == id }
    fun pageObjects(id: PageId): List<StrokeObject> {
        val page = findPage(id) ?: return emptyList()
        var count = 0
        scene.forEachObject { if (it.layerId == page.layerId) count++ }
        if (count == 0) return emptyList()
        // Allocate only the selected page's exact reference capacity, never an intermediate all-scene list.
        val objects = ArrayList<StrokeObject>(count)
        scene.forEachObject { if (it.layerId == page.layerId) objects.add(it) }
        return objects
    }
    companion object {
        fun empty(id: SceneId, title: String, firstPage: PageDescriptor): DocumentRoot = DocumentRoot(title, SceneSnapshot.empty(id), listOf(firstPage))
        /** Restore/compose immutable canonical values; caller must create only one mutable authority. */
        fun compose(title: String, scene: SceneSnapshot, pages: List<PageDescriptor>): DocumentRoot = DocumentRoot(title, scene, pages)
    }
}
