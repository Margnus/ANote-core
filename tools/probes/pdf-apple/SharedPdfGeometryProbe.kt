package dev.notecore.document

import dev.notecore.canvas.prototype.PointD

/** Test-only friend of original shared-core klib; does not export a new SDK facade. */
fun main(args: Array<String>) {
    val source = listOf(PointD(56.0,66.0), PointD(324.0,66.0), PointD(56.0,214.0), PointD(324.0,214.0))
    val rows = args.mapIndexed { index, encoded ->
        val values = encoded.split(',').map(String::toDouble)
        require(values.size == 6)
        val g = checkNotNull(PdfPageGeometryRules.resolve(
            PdfCropBox(values[0], values[1], values[2], values[3]), values[4].toInt(), values[5]).geometry)
        val f = g.pdfToPage
        val anchors = source.map { p ->
            val mapped = f.map(p)
            check(g.pageToPdf.map(mapped).let { kotlin.math.abs(it.x-p.x)<1e-8 && kotlin.math.abs(it.y-p.y)<1e-8 })
            "[${mapped.x},${mapped.y}]"
        }
        """{"index":$index,"width":${g.width},"height":${g.height},"rotation":${g.rotation},"user_unit":${g.userUnit},"pdf_to_page":[${f.a},${f.b},${f.c},${f.d},${f.tx},${f.ty}],"anchors":[${anchors.joinToString(",")}]}"""
    }
    println("[${rows.joinToString(",\n")}]")
}
