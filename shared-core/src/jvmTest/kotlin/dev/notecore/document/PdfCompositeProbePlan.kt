package dev.notecore.document

import dev.notecore.canvas.prototype.CommandId
import dev.notecore.canvas.prototype.LayerId
import dev.notecore.canvas.prototype.SceneId
import dev.notecore.canvas.prototype.SceneSnapshot
import java.io.File
import java.security.MessageDigest

/** Packages shared export tasks for the isolated platform file writers. */
object PdfCompositeProbePlan {
    @JvmStatic fun main(args: Array<String>) {
        check(args.size == 2)
        val fixture = File(args[0]).readBytes()
        val sha = MessageDigest.getInstance("SHA-256").digest(fixture).joinToString("") { "%02x".format(it) }
        val source = PdfSourcePages(sha, 4)
        fun page(id: String, width: Double, height: Double) = PageDescriptor(PageId(id), LayerId("layer-$id"), width, height)
        val pages = listOf(page("rotated", 180.0, 300.0), page("blank", 200.0, 120.0),
            page("excluded", 180.0, 300.0), page("first", 300.0, 180.0))
        val scene = SceneSnapshot.empty(SceneId("pdf-composite-probe"))
        val root = DocumentRoot.compose("Composite", scene, pages)
        val catalog = PdfPageCatalog("local", root.id, 0, true, listOf(source), listOf(
            PdfPageRef(pages[0].id, sha, 3), PdfPageRef(pages[2].id, sha, 1), PdfPageRef(pages[3].id, sha, 0),
        ))
        val qualities = (1..2).map { scale ->
            val request = PdfCompositeExportRequest(CommandId("export-$scale"), PdfCompositeTarget.PDF_DOCUMENT,
                listOf(PdfPageRange(4, 4), PdfPageRange(1, 2), PdfPageRange(2, 2)), scale.toDouble(),
                maxPagePixels = 216_000, PdfCompositeRasterRetention.UNTIL_EXPORT_FINISHED,
                maxRetainedArgbBytes = 528_000L * scale * scale)
            val result = PdfCompositeExportRules.plan(root, catalog, "local", 0, request) { reference ->
                check(reference.sourceIndex != 1) { "An excluded page must not be read" }
                PdfSourcePageGeometry(source, reference.sourceIndex, PdfCropBox(40.0, 50.0, 340.0, 230.0),
                    reference.sourceIndex * 90, 1.0)
            }
            val plan = checkNotNull(result.plan) { "Export plan rejected: $result" }
            check(PdfCompositeExportRules.revalidate(plan, request.operationId).plan == plan)
            check(PdfCompositeExportRules.revalidate(plan, CommandId("cancelled")).failure == PdfCompositeExportFailure.CANCELLED)
            val tasks = plan.tasks.joinToString(",\n") { task ->
                """      {"page_id":"${task.page.id.value}","output_number":${task.outputNumber},"display_number":${task.displayNumber},"source_index":${task.background?.reference?.sourceIndex},"page_width":${task.page.width.toInt()},"page_height":${task.page.height.toInt()},"pixel_width":${task.pixelWidth},"pixel_height":${task.pixelHeight},"argb_bytes":${task.argbByteUpperBound}}"""
            }
            """    {"scale":$scale,"retained_argb_bytes":${plan.retainedArgbByteUpperBound},"tasks":[
                |$tasks
                |    ]}""".trimMargin()
        }
        val output = File(args[1])
        output.parentFile.mkdirs()
        output.writeText("""{"source_sha256":"$sha","source_page_count":${source.pageCount},"qualities":[
            |${qualities.joinToString(",\n")}
            |]}
            |""".trimMargin())
    }
}
