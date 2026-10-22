package dev.notecore.app

import android.graphics.*
import android.os.Build
import android.view.*
import android.widget.FrameLayout
import java.util.UUID

/** Static child is below the window's dynamic InkCanvasView, through SurfaceView's hole punch. */
@android.annotation.SuppressLint("ViewConstructor")
@android.annotation.TargetApi(33)
internal class InkSurfaceHost(val ink: InkCanvasView, store: NoteStore) : FrameLayout(ink.context), SurfaceHolder.Callback {
    val identity = UUID.randomUUID().toString()
    private val underlay = SurfaceView(context)
    var child: SurfaceControl? = null; private set
    init {
        underlay.holder.setFormat(PixelFormat.TRANSLUCENT)
        underlay.holder.addCallback(this)
        addView(underlay,LayoutParams(-1,-1)); addView(ink,LayoutParams(-1,-1))
        ink.renderStore = store; ink.surfaceHost = this
    }
    override fun surfaceCreated(holder: SurfaceHolder) { }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (Build.VERSION.SDK_INT < 33 || width <= 0 || height <= 0) return
        retireChild()
        try {
            // Destination infrastructure is owned by Android; no leased source Bitmap enters it.
            val canvas = holder.lockHardwareCanvas()
            try { canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR) } finally { holder.unlockCanvasAndPost(canvas) }
            child = SurfaceControl.Builder().setName("NoteCore-static-ink").setBufferSize(width,height)
                .setParent(underlay.surfaceControl).build()
            ink.invalidate()
        } catch (_: Throwable) { retireChild() } // Complete Path remains visible.
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) { retireChild() }
    private fun retireChild() {
        ink.renderStore?.detachInk(ink)
        child?.let { target ->
            try {
                if (target.isValid) SurfaceControl.Transaction().use { it.setBuffer(target,null).setVisibility(target,false).reparent(target,null).apply() }
            } catch (_: Throwable) {
                // Source responsibility stays in NoteStore until its real release receipt.
                ink.invalidate()
            } finally { target.release() }
        }
        child = null
    }
}
