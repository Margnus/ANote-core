package dev.notecore.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import dev.notecore.document.*
import java.io.File
import org.json.JSONObject

/** Actual original View/Store on the declared minimum OS. Bitmap pixels are not hardware/latency evidence. */
internal object MinimumApiHostChecks {
    fun verify(host: Activity): String {
        check(android.os.Build.VERSION.SDK_INT == 26)
        val directory = File(host.cacheDir, "minimum-api-${java.util.UUID.randomUUID()}")
        val store = NoteStore.isolated(host, directory)
        val bitmap = Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888)
        try {
            val note = store.create(); val first = note.currentPageId
            check(note.selectPen(0xffff0000.toInt(), 8.0))
            check(note.beginStroke(100.0,150.0,.5f,1) && note.endStroke(200.0,150.0,.5f,2))
            check(note.selectPen(0xff0000ff.toInt(), 8.0))
            check(note.beginStroke(300.0,300.0,.5f,3) && note.endStroke(300.0,300.0,.5f,4))
            val view = InkCanvasView(host,note,{},{ error(it) }).apply { renderStore=store; layout(0,0,595,842) }
            fun draw() { view.draw(Canvas(bitmap)); check(store.inkChargedBytes == 0L) }
            draw(); check(bitmap.getPixel(150,150)==0xffff0000.toInt() && bitmap.getPixel(300,300)==0xff0000ff.toInt())
            check(note.undo()); draw(); check(bitmap.getPixel(300,300)==0xffffffff.toInt())
            check(note.redo()); draw(); check(bitmap.getPixel(300,300)==0xff0000ff.toInt())
            check(note.insertPage()); draw(); check(bitmap.getPixel(150,150)==0xffffffff.toInt())
            check(note.selectPage(first)); draw(); check(bitmap.getPixel(150,150)==0xffff0000.toInt())
            check(PreviewDocumentReader.read(note.capture().encode()).root != null)
            return JSONObject().put("api",26).put("original_view_store",true)
                .put("line_dot_undo_redo_page_pixels",true).put("surface_render_bytes",store.inkChargedBytes)
                .put("hardware_canvas_verified",false).put("physical_device_verified",false).toString()
        } finally { bitmap.recycle(); store.stopTestBackend() }
    }
}
