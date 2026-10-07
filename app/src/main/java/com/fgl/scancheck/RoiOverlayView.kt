package com.fgl.scancheck

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Draws the aiming frame on top of the camera preview.
 * Only text inside this frame is read and checked.
 */
class RoiOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Frame width as a fraction of the view width (0.1–1.0). */
    var widthFrac = 0.8f
        set(v) { field = v.coerceIn(0.1f, 1f); invalidate() }

    /** Frame height as a fraction of the view height (0.1–1.0). */
    var heightFrac = 0.3f
        set(v) { field = v.coerceIn(0.1f, 1f); invalidate() }

    /** Vertical position of the frame centre (0 = top, 1 = bottom). */
    var centerYFrac = 0.5f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    /** When false the whole picture is read and no frame is drawn. */
    var roiEnabled = true
        set(v) { field = v; invalidate() }

    var frameColor = Color.WHITE
        set(v) { field = v; invalidate() }

    private val density = resources.displayMetrics.density
    private val dimPaint = Paint().apply { color = 0x99000000.toInt() }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f * density
        strokeCap = Paint.Cap.ROUND
    }

    /** The frame in this view's pixel coordinates (same as the PreviewView). */
    fun roiRect(): RectF {
        val w = width.toFloat()
        val h = height.toFloat()
        val rw = w * widthFrac
        val rh = h * heightFrac
        val cy = (h * centerYFrac).coerceIn(rh / 2f, h - rh / 2f)
        return RectF((w - rw) / 2f, cy - rh / 2f, (w + rw) / 2f, cy + rh / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!roiEnabled || width == 0 || height == 0) return
        val r = roiRect()
        val w = width.toFloat()
        val h = height.toFloat()

        // Darken everything outside the frame.
        canvas.drawRect(0f, 0f, w, r.top, dimPaint)
        canvas.drawRect(0f, r.bottom, w, h, dimPaint)
        canvas.drawRect(0f, r.top, r.left, r.bottom, dimPaint)
        canvas.drawRect(r.right, r.top, w, r.bottom, dimPaint)

        framePaint.color = frameColor
        cornerPaint.color = frameColor
        canvas.drawRect(r, framePaint)

        // Corner marks.
        val c = 18f * density
        canvas.drawLine(r.left, r.top, r.left + c, r.top, cornerPaint)
        canvas.drawLine(r.left, r.top, r.left, r.top + c, cornerPaint)
        canvas.drawLine(r.right, r.top, r.right - c, r.top, cornerPaint)
        canvas.drawLine(r.right, r.top, r.right, r.top + c, cornerPaint)
        canvas.drawLine(r.left, r.bottom, r.left + c, r.bottom, cornerPaint)
        canvas.drawLine(r.left, r.bottom, r.left, r.bottom - c, cornerPaint)
        canvas.drawLine(r.right, r.bottom, r.right - c, r.bottom, cornerPaint)
        canvas.drawLine(r.right, r.bottom, r.right, r.bottom - c, cornerPaint)

        // Small centre cross for aiming.
        val m = 8f * density
        canvas.drawLine(r.centerX() - m, r.centerY(), r.centerX() + m, r.centerY(), framePaint)
        canvas.drawLine(r.centerX(), r.centerY() - m, r.centerX(), r.centerY() + m, framePaint)
    }
}
