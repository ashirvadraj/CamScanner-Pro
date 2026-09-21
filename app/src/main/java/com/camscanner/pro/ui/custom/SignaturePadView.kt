package com.camscanner.pro.ui.custom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class SignaturePadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val path = Path()
    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 8f
    }

    private var lastX = 0f
    private var lastY = 0f
    private var hasDrawn = false

    fun setInkColor(color: Int) {
        drawPaint.color = color
        invalidate()
    }

    fun clear() {
        path.reset()
        hasDrawn = false
        invalidate()
    }

    fun isEmpty(): Boolean = !hasDrawn

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(path, drawPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                path.moveTo(x, y)
                lastX = x
                lastY = y
                hasDrawn = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // Quadratic bezier curve for smooth ink strokes
                val midX = (lastX + x) / 2f
                val midY = (lastY + y) / 2f
                path.quadTo(lastX, lastY, midX, midY)
                lastX = x
                lastY = y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                path.lineTo(x, y)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Extracts signature as a transparent background Bitmap.
     */
    fun getSignatureBitmap(): Bitmap? {
        if (!hasDrawn || width <= 0 || height <= 0) return null
        val bm = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm)
        c.drawColor(Color.TRANSPARENT)
        c.drawPath(path, drawPaint)
        return bm
    }
}
